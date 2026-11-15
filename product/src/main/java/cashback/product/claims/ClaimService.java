package cashback.product.claims;

import cashback.common.Json;
import cashback.product.audit.Audit;
import cashback.product.claims.Claims.Decision;
import cashback.product.claims.Claims.Outcome;
import cashback.product.claims.Claims.Ticket;
import cashback.product.ledger.Records;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * The claim lifecycle: submit, decide, a person resolves what the desk sent to support, and a claim is filed with the
 * network exactly once. Every step is in the audit trail.
 */
public final class ClaimService {

    public enum Status { FILING, FILED, ANSWERED, WAITING, REJECTED, NEEDS_SUPPORT }

    public record Resolution(String actor, Outcome verdict, String reason, long eligibleCents, long cashbackCents,
                             long atMillis) {
    }

    public record ClaimRecord(Ticket ticket, Decision decision, Status status, Resolution resolution,
                              String networkReference, String orderKey, long version) {

        public Outcome finalOutcome() {
            return resolution != null ? resolution.verdict() : decision.outcome();
        }

        public long filedCashbackCents() {
            if (status != Status.FILED) {
                return 0;
            }
            return resolution != null ? resolution.cashbackCents() : decision.cashbackCents();
        }

        ClaimRecord next(Status s, Resolution r, String ref) {
            return new ClaimRecord(ticket, decision, s, r, ref, orderKey, version + 1);
        }

        public Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ticket", ticket.toJson());
            m.put("decision", decision.toJson());
            m.put("status", status.name());
            if (resolution != null) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("actor", resolution.actor());
                r.put("verdict", resolution.verdict().name());
                r.put("reason", resolution.reason());
                r.put("eligibleCents", resolution.eligibleCents());
                r.put("cashbackCents", resolution.cashbackCents());
                r.put("atMillis", resolution.atMillis());
                m.put("resolution", r);
            } else {
                m.put("resolution", null);
            }
            m.put("networkReference", networkReference);
            m.put("orderKey", orderKey);
            m.put("version", version);
            return m;
        }

        @SuppressWarnings("unchecked")
        public static ClaimRecord fromJson(Map<String, Object> m) {
            Resolution res = null;
            if (m.get("resolution") instanceof Map<?, ?> r) {
                Map<String, Object> rm = (Map<String, Object>) r;
                res = new Resolution(Json.str(rm, "actor"), Outcome.valueOf(Json.str(rm, "verdict")),
                    Json.str(rm, "reason"), Json.lng(rm, "eligibleCents"), Json.lng(rm, "cashbackCents"),
                    Json.lng(rm, "atMillis"));
            }
            return new ClaimRecord(Ticket.fromJson(Json.obj(m, "ticket")), Decision.fromJson(Json.obj(m, "decision")),
                Status.valueOf(Json.str(m, "status")), res, (String) m.get("networkReference"),
                (String) m.get("orderKey"), Json.lng(m, "version"));
        }
    }

    /** Claims, compare-and-set on the version like the ledger. */
    public interface ClaimStore {
        Optional<ClaimRecord> get(String claimId);

        boolean put(ClaimRecord next);

        List<ClaimRecord> byOrder(String orderKey);

        List<ClaimRecord> all();
    }

    /** Sends a claim to the affiliate network. The claim id is the idempotency key, so a retry is not a second claim. */
    public interface NetworkFiler {
        String file(ClaimRecord claim, long eligibleCents);
    }

    private final ClaimStore store;
    private final ClaimDesk.Factory desks;
    private final NetworkFiler filer;
    private final Audit audit;
    private final LongSupplier clock;

    public ClaimService(ClaimStore store, ClaimDesk.Factory desks, NetworkFiler filer, Audit audit, LongSupplier clock) {
        this.store = store;
        this.desks = desks;
        this.filer = filer;
        this.audit = audit;
        this.clock = clock;
    }

    /** The claims of other accounts on the same order, from this store. */
    public static Claims.ClaimHistory historyOf(ClaimStore store) {
        return (merchantId, orderId, userId, claimId) -> store.byOrder(Records.RewardRecord.orderKey(merchantId, orderId))
            .stream().filter(c -> !c.ticket().userId().equals(userId) && !c.ticket().claimId().equals(claimId))
            .map(c -> c.ticket().userId()).distinct().toList();
    }

    public ClaimRecord submit(Ticket ticket, String actor) {
        Optional<ClaimRecord> existing = store.get(ticket.claimId());
        if (existing.isPresent()) {
            return existing.get();
        }
        Decision d = desks.desk(historyOf(store)).decide(ticket);
        String orderKey = d.orderId() == null ? null : Records.RewardRecord.orderKey(ticket.merchantId(), d.orderId());
        Status status = switch (d.outcome()) {
            case FILE -> Status.FILING;
            case ALREADY_TRACKED -> Status.ANSWERED;
            case WAIT -> Status.WAITING;
            case REJECT -> Status.REJECTED;
            case SUPPORT -> Status.NEEDS_SUPPORT;
        };
        ClaimRecord created = new ClaimRecord(ticket, d, status, null, null, orderKey, 1);
        if (!store.put(created)) {
            return store.get(ticket.claimId()).orElseThrow();
        }
        audit.record(clock.getAsLong(), actor, "claim.decided", ticket.claimId(),
            d.outcome().name() + " " + d.reason());
        if (d.outcome() == Outcome.FILE) {
            return fileOnce(created, d.eligibleCents(), "desk");
        }
        return created;
    }

    /** A support person's verdict on a claim the desk could not decide alone. */
    public ClaimRecord resolve(String claimId, String actor, Outcome verdict, String reason, Long eligibleCents) {
        if (verdict != Outcome.FILE && verdict != Outcome.REJECT && verdict != Outcome.ALREADY_TRACKED
            && verdict != Outcome.WAIT) {
            throw new IllegalArgumentException("a person files, rejects, answers or asks to wait");
        }
        for (int attempt = 0; attempt < 20; attempt++) {
            ClaimRecord c = store.get(claimId).orElseThrow(() -> new IllegalArgumentException("no claim " + claimId));
            if (c.status() != Status.NEEDS_SUPPORT || c.resolution() != null) {
                return c;
            }
            long eligible = eligibleCents != null ? eligibleCents : c.decision().eligibleCents();
            if (verdict == Outcome.FILE && eligible <= 0) {
                throw new IllegalArgumentException("filing needs an eligible amount");
            }
            long cashback = verdict == Outcome.FILE ? cashbackFor(c, eligible) : 0;
            Resolution r = new Resolution(actor, verdict, reason, verdict == Outcome.FILE ? eligible : 0, cashback,
                clock.getAsLong());
            Status s = switch (verdict) {
                case FILE -> Status.FILING;
                case ALREADY_TRACKED -> Status.ANSWERED;
                case WAIT -> Status.WAITING;
                default -> Status.REJECTED;
            };
            ClaimRecord resolved = c.next(s, r, null);
            if (store.put(resolved)) {
                audit.record(r.atMillis(), actor, "claim.resolved", claimId, verdict.name() + " " + reason);
                return verdict == Outcome.FILE ? fileOnce(resolved, eligible, actor) : resolved;
            }
        }
        throw new IllegalStateException("contention on claim " + claimId);
    }

    private long cashbackFor(ClaimRecord c, long eligible) {
        return desks.cashback(c.ticket().merchantId(), eligible);
    }

    /** Files a claim left in FILING (the network was unreachable); the claim id keeps the retry idempotent. */
    public ClaimRecord retryFiling(String claimId, String actor) {
        ClaimRecord c = store.get(claimId).orElseThrow(() -> new IllegalArgumentException("no claim " + claimId));
        if (c.status() != Status.FILING) {
            return c;
        }
        long eligible = c.resolution() != null ? c.resolution().eligibleCents() : c.decision().eligibleCents();
        return fileOnce(c, eligible, actor);
    }

    private ClaimRecord fileOnce(ClaimRecord c, long eligibleCents, String actor) {
        String reference = filer.file(c, eligibleCents);
        ClaimRecord filed = c.next(Status.FILED, c.resolution(), reference);
        if (!store.put(filed)) {
            return store.get(c.ticket().claimId()).orElseThrow();
        }
        audit.record(clock.getAsLong(), actor, "claim.filed", c.ticket().claimId(),
            "network reference " + reference + ", eligible " + eligibleCents + " cents");
        return filed;
    }

    public Optional<ClaimRecord> get(String claimId) {
        return store.get(claimId);
    }

    public List<ClaimRecord> list(Status status) {
        List<ClaimRecord> out = new ArrayList<>();
        for (ClaimRecord c : store.all()) {
            if (status == null || c.status() == status) {
                out.add(c);
            }
        }
        out.sort(Comparator.comparingLong((ClaimRecord c) -> c.ticket().filedAtMillis())
            .thenComparing(c -> c.ticket().claimId()));
        return out;
    }

    public static final class MemoryClaimStore implements ClaimStore {
        private final Map<String, ClaimRecord> claims = new ConcurrentHashMap<>();

        @Override
        public Optional<ClaimRecord> get(String claimId) {
            return Optional.ofNullable(claims.get(claimId));
        }

        @Override
        public boolean put(ClaimRecord next) {
            boolean[] stored = {false};
            claims.compute(next.ticket().claimId(), (k, current) -> {
                long have = current == null ? 0 : current.version();
                if (have != next.version() - 1) {
                    return current;
                }
                stored[0] = true;
                return next;
            });
            return stored[0];
        }

        @Override
        public List<ClaimRecord> byOrder(String orderKey) {
            return claims.values().stream().filter(c -> orderKey.equals(c.orderKey())).toList();
        }

        @Override
        public List<ClaimRecord> all() {
            return List.copyOf(claims.values());
        }
    }
}
