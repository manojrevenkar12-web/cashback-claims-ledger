package cashback.eval;

import cashback.common.Json;
import cashback.product.audit.Audit;
import cashback.product.claims.ClaimDesk;
import cashback.product.claims.ClaimService;
import cashback.product.claims.ClaimService.ClaimRecord;
import cashback.product.claims.Claims;
import cashback.product.claims.Claims.Outcome;
import cashback.product.ledger.Ledger;
import cashback.product.ledger.MemoryLedgerStore;
import cashback.product.ledger.Records;
import cashback.product.ledger.RewardFold;
import cashback.product.terms.Cashback;
import cashback.product.terms.Terms;
import cashback.world.Actors;
import cashback.world.Model.Claim;
import cashback.world.Model.Event;
import cashback.world.Model.Truth;
import cashback.world.World;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Runs the four claim designs over the same claims, the same reader luck and the same support person. */
final class ClaimsEval {

    record Result(Truth truthKind, Outcome outcome, long eligibleCents, boolean bySupport, double minutes,
                  long arrivalMillis) {
    }

    private final World w;
    private final Terms terms;
    private final Map<String, Object> product;
    private final Map<String, Object> designs;
    private final Map<String, Claim> byId = new LinkedHashMap<>();

    ClaimsEval(World w, Terms terms, Map<String, Object> product, Map<String, Object> designs) {
        this.w = w;
        this.terms = terms;
        this.product = product;
        this.designs = designs;
        for (Claim c : w.claims) {
            byId.put(c.claimId(), c);
        }
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> run() {
        Map<String, List<Result>> results = new LinkedHashMap<>();
        results.put("support_all", supportAll());
        results.put("rules_alone", rulesAlone());
        results.put("llm_alone", llmAlone());
        results.put("desk", desk());
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> ds = new LinkedHashMap<>();
        for (Map.Entry<String, List<Result>> e : results.entrySet()) {
            ds.put(e.getKey(), score(e.getKey(), e.getValue()));
        }
        Map<String, Object> desk = (Map<String, Object>) ds.get("desk");
        desk.put("auditEntries", deskAudit.entries());
        desk.put("auditIntact", deskAudit.intact());
        out.put("designs", ds);
        Map<String, Object> cmp = new LinkedHashMap<>();
        for (String other : List.of("support_all", "rules_alone", "llm_alone")) {
            cmp.put("desk_vs_" + other, Stats.pairedBootstrap(w.k, "boot-" + other, right(results.get("desk")),
                right(results.get(other))));
        }
        out.put("rightShareDifference", cmp);
        return out;
    }

    // ---- designs ------------------------------------------------------------------------------------------------

    private List<Result> supportAll() {
        Actors.Person person = new Actors.Person(w);
        double minutes = Json.num(design("support_all"), "minutesPerClaim");
        List<Result> out = new ArrayList<>();
        for (Claim c : w.claims) {
            Actors.Verdict v = person.decide(c, 0, "support_all");
            out.add(new Result(c.truth().kind(), outcome(v.kind()), v.eligibleCents(), true, minutes,
                c.filedAtMillis()));
        }
        return out;
    }

    /** The shopper's form and the click log, without reading the confirmation. */
    private List<Result> rulesAlone() {
        Actors.Person person = new Actors.Person(w);
        double minutes = Json.num(design("rules_alone"), "minutesPerSupportCase");
        long supportAbove = Json.lng(Json.obj(product, "claims"), "supportAboveCashbackCents");
        Claims.ClickLog clickLog = Wiring.clicks(w);
        Tracked tracked = new Tracked();
        Map<String, List<String>> claimants = new HashMap<>();
        List<Result> out = new ArrayList<>();
        for (Claim c : w.claims) {
            tracked.advanceTo(c.filedAtMillis());
            Terms.Merchant m = terms.merchant(c.formShopId());
            String orderKey = Records.RewardRecord.orderKey(m.id(), c.formOrderId());
            List<String> others = claimants.computeIfAbsent(orderKey, x -> new ArrayList<>());
            boolean dup = others.stream().anyMatch(u -> !u.equals(c.userId()));
            others.add(c.userId());
            Outcome o;
            long eligible = 0;
            boolean support = false;
            long age = ChronoUnit.DAYS.between(c.formOrderDate(), Wiring.day(c.filedAtMillis()));
            if (dup) {
                support = true;
                o = null;
            } else if (tracked.statusOf(m.id(), c.formOrderId(), c.filedAtMillis()).isPresent()) {
                o = Outcome.ALREADY_TRACKED;
            } else if (age < m.claimAfterDays()) {
                o = Outcome.WAIT;
            } else if (age > m.claimWithinDays()) {
                o = Outcome.REJECT;
            } else if (clickLog.clicks(c.userId(), m.id()).stream().noneMatch(k -> {
                long before = ChronoUnit.DAYS.between(Wiring.day(k.atMillis()), c.formOrderDate());
                return before >= 0 && before <= m.cookieDays();
            })) {
                o = Outcome.REJECT;
            } else {
                eligible = c.formAmountCents();
                o = Outcome.FILE;
                if (Cashback.ofAmount(eligible, m.cashbackBp()) >= supportAbove) {
                    support = true;
                }
            }
            if (support) {
                Actors.Verdict v = person.decide(c, o == Outcome.FILE ? eligible : 0, "rules_alone");
                out.add(new Result(c.truth().kind(), outcome(v.kind()), v.eligibleCents(), true, minutes,
                    c.filedAtMillis()));
            } else {
                out.add(new Result(c.truth().kind(), o, eligible, false, 0, c.filedAtMillis()));
            }
        }
        return out;
    }

    private List<Result> llmAlone() {
        Actors.Judge judge = new Actors.Judge(w);
        List<Result> out = new ArrayList<>();
        for (Claim c : w.claims) {
            Actors.Verdict v = judge.decide(c);
            out.add(new Result(c.truth().kind(), outcome(v.kind()), v.eligibleCents(), false, 0, c.filedAtMillis()));
        }
        return out;
    }

    /** The product: the claim service with its desk, the ledger fed by delivered events, and the person for support. */
    private List<Result> desk() {
        Actors.Person person = new Actors.Person(w);
        double minutes = Json.num(design("desk"), "minutesPerSupportCase");
        Map<String, Object> pc = Json.obj(product, "claims");
        Tracked tracked = new Tracked();
        long[] now = {0};
        ClaimDesk.Factory desks = ClaimDesk.factory(terms, Wiring.clicks(w), tracked::statusOf,
            Wiring.reader(w, byId), Json.integer(pc, "maxReads"), Json.lng(pc, "supportAboveCashbackCents"));
        Audit audit = new Audit(new Audit.MemoryLog());
        ClaimService service = new ClaimService(new ClaimService.MemoryClaimStore(), desks,
            (claim, eligible) -> "net-" + claim.ticket().claimId(), audit, () -> now[0]);
        List<Result> out = new ArrayList<>();
        for (Claim c : w.claims) {
            now[0] = c.filedAtMillis();
            tracked.advanceTo(c.filedAtMillis());
            ClaimRecord r = service.submit(Wiring.ticket(c), "shopper:" + c.userId());
            boolean support = r.status() == ClaimService.Status.NEEDS_SUPPORT;
            if (support) {
                Actors.Verdict v = person.decide(c, r.decision().eligibleCents(), "desk");
                Long eligible = v.kind() == Truth.FILE && r.decision().eligibleCents() > 0
                    && v.eligibleCents() == r.decision().eligibleCents() ? null : v.eligibleCents();
                r = service.resolve(c.claimId(), "support:sim", outcome(v.kind()), "person", eligible);
            }
            long filed = r.status() == ClaimService.Status.FILED
                ? (r.resolution() != null ? r.resolution().eligibleCents() : r.decision().eligibleCents()) : 0;
            out.add(new Result(c.truth().kind(), r.finalOutcome(), filed, support, support ? minutes : 0,
                c.filedAtMillis()));
        }
        deskAudit = audit.verify();
        return out;
    }

    Audit.Verification deskAudit;

    /** The product's view of which orders networks have reported: the ledger, fed events as they are delivered. */
    private final class Tracked {
        private final Ledger ledger = new Ledger(new MemoryLedgerStore(), new RewardFold(terms), 1000, 50);
        private int next = 0;

        void advanceTo(long millis) {
            while (next < w.events.size() && w.events.get(next).deliveredAtMillis() <= millis) {
                Event e = w.events.get(next++);
                ledger.apply(Wiring.fact(e.fact()));
            }
        }

        java.util.Optional<String> statusOf(String merchantId, String orderId, long asOf) {
            return ledger.rewardForOrder(merchantId, orderId, asOf).map(r -> r.status().name().toLowerCase());
        }
    }

    // ---- scoring ------------------------------------------------------------------------------------------------

    static boolean right(Result r, Claim c) {
        return switch (c.truth().kind()) {
            case FILE -> r.outcome() == Outcome.FILE && r.eligibleCents() == c.truth().eligibleCents();
            case FRAUD -> r.outcome() != Outcome.FILE;
            case REJECT -> r.outcome() == Outcome.REJECT;
            case WAIT -> r.outcome() == Outcome.WAIT;
            case ALREADY_TRACKED -> r.outcome() == Outcome.ALREADY_TRACKED;
        };
    }

    private boolean[] right(List<Result> results) {
        boolean[] out = new boolean[results.size()];
        for (int i = 0; i < results.size(); i++) {
            out[i] = right(results.get(i), w.claims.get(i));
        }
        return out;
    }

    private Map<String, Object> score(String name, List<Result> results) {
        long right = 0;
        long filed = 0;
        long invalidFiled = 0;
        long wrongAmounts = 0;
        long validDenied = 0;
        long recovered = 0;
        long recoverable = 0;
        long supportCases = 0;
        double minutes = 0;
        List<long[]> queue = new ArrayList<>();
        Map<String, Long> wrongBy = new java.util.TreeMap<>();
        for (int i = 0; i < results.size(); i++) {
            Result r = results.get(i);
            Claim c = w.claims.get(i);
            if (right(r, c)) {
                right++;
            } else {
                String what = c.truth().reason() + " -> " + (r.outcome() == Outcome.FILE
                    && c.truth().kind() == Truth.FILE ? "FILE wrong amount" : r.outcome().name());
                wrongBy.merge(what, 1L, Long::sum);
            }
            boolean valid = c.truth().kind() == Truth.FILE;
            if (valid) {
                recoverable += c.truth().cashbackCents();
            }
            if (r.outcome() == Outcome.FILE) {
                filed++;
                if (!valid) {
                    invalidFiled++;
                } else if (r.eligibleCents() != c.truth().eligibleCents()) {
                    wrongAmounts++;
                }
                if (Actors.networkApproves(w, c, r.eligibleCents())) {
                    recovered += c.truth().cashbackCents();
                }
            } else if (valid) {
                validDenied += c.truth().cashbackCents();
            }
            if (r.bySupport()) {
                supportCases++;
                minutes += r.minutes();
                queue.add(new long[]{r.arrivalMillis(), Math.round(r.minutes())});
            }
        }
        Stats.Queue q = Stats.queue(queue, Json.num(designs, "supportMinutesPerDay"), Json.integer(designs, "slaDays"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", Json.str(design(name), "label"));
        m.put("claims", (long) results.size());
        m.put("right", right);
        m.put("rightShare", ratio(right, results.size()));
        m.put("filed", filed);
        m.put("invalidFiled", invalidFiled);
        m.put("invalidFiledShare", ratio(invalidFiled, filed));
        m.put("wrongAmounts", wrongAmounts);
        m.put("wrongAmountShareOfFiled", ratio(wrongAmounts, filed));
        m.put("validDeniedCents", validDenied);
        m.put("recoveredCents", recovered);
        m.put("recoverableCents", recoverable);
        m.put("recoveredShare", ratio(recovered, recoverable));
        m.put("supportCases", supportCases);
        m.put("supportHours", Math.round(minutes / 60.0 * 10) / 10.0);
        m.put("late", q.late());
        m.put("p90AnswerDays", q.p90Days());
        m.put("maxAnswerDays", q.maxDays());
        Map<String, Object> wrong = new LinkedHashMap<>();
        wrongBy.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
            .forEach(e -> wrong.put(e.getKey(), e.getValue()));
        m.put("wrongBy", wrong);
        return m;
    }

    static double ratio(long a, long b) {
        return b == 0 ? 0.0 : Math.round(a * 10000.0 / b) / 10000.0;
    }

    private Map<String, Object> design(String name) {
        return Json.obj(Json.obj(designs, "designs"), name);
    }

    private static Outcome outcome(Truth t) {
        return switch (t) {
            case FILE -> Outcome.FILE;
            case ALREADY_TRACKED -> Outcome.ALREADY_TRACKED;
            case WAIT -> Outcome.WAIT;
            case REJECT, FRAUD -> Outcome.REJECT;
        };
    }

    static Map<String, Object> truthMix(World w) {
        Map<String, Object> mix = new LinkedHashMap<>();
        for (Claim c : w.claims) {
            mix.merge(c.truth().reason(), 1L, (a, b) -> (Long) a + (Long) b);
        }
        return mix;
    }
}
