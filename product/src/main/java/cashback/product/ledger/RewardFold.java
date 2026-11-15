package cashback.product.ledger;

import cashback.product.terms.Cashback;
import cashback.product.terms.Terms;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Computes a reward from its facts. The result depends on the set of facts and the time asked, never on the order the
 * facts arrived in; that is what makes duplicates and late, reordered deliveries harmless.
 */
public final class RewardFold {

    private static final long DAY = 86_400_000L;

    private final Terms terms;

    public RewardFold(Terms terms) {
        this.terms = terms;
    }

    public enum Kind { ONLINE, IN_STORE }

    public record Reward(String userId, String rewardId, Kind kind, String merchantId, RewardStatus status,
                         long cashbackCents, long baseCents, String reason) {
    }

    public Reward fold(Records.RewardRecord record, long asOfMillis) {
        List<Fact> facts = record.facts();
        if (facts.isEmpty()) {
            throw new IllegalArgumentException("a reward needs at least one fact");
        }
        boolean online = facts.stream().anyMatch(f -> f instanceof Fact.NetworkUpdate);
        return online ? online(record, facts) : inStore(record, facts, asOfMillis);
    }

    private Reward online(Records.RewardRecord record, List<Fact> facts) {
        // The network's latest update wins; equal versions are broken by event id so every replica agrees.
        Fact.NetworkUpdate latest = facts.stream().filter(f -> f instanceof Fact.NetworkUpdate)
            .map(f -> (Fact.NetworkUpdate) f)
            .max(Comparator.comparingLong(Fact.NetworkUpdate::sourceVersion)
                .thenComparing(Fact.NetworkUpdate::eventId))
            .orElseThrow();
        long cashback = latest.status() == RewardStatus.DECLINED ? 0 : latest.cashbackCents();
        return new Reward(record.userId(), record.rewardId(), Kind.ONLINE, latest.merchantId(), latest.status(),
            cashback, latest.saleCents(), "network " + latest.status().name().toLowerCase());
    }

    private Reward inStore(Records.RewardRecord record, List<Fact> facts, long asOf) {
        String offerId = facts.stream().map(RewardFold::offerOf).filter(o -> o != null).findFirst().orElseThrow();
        Terms.Offer offer = terms.offer(offerId);
        Optional<Fact.CardAuth> auth = facts.stream().filter(f -> f instanceof Fact.CardAuth)
            .map(f -> (Fact.CardAuth) f).min(Comparator.comparingLong(Fact.CardAuth::atMillis));
        Optional<Fact.CardClearing> clearing = facts.stream().filter(f -> f instanceof Fact.CardClearing)
            .map(f -> (Fact.CardClearing) f)
            .max(Comparator.comparingLong(Fact.CardClearing::atMillis).thenComparing(Fact.CardClearing::eventId));
        // A refund is counted once however often it is sent: refunds are keyed by the scheme's refund id.
        Map<String, Long> refunds = new LinkedHashMap<>();
        facts.stream().filter(f -> f instanceof Fact.CardRefund).map(f -> (Fact.CardRefund) f)
            .sorted(Comparator.comparing(Fact.CardRefund::eventId))
            .forEach(r -> refunds.putIfAbsent(r.refundId(), r.amountCents()));
        long refunded = refunds.values().stream().mapToLong(Long::longValue).sum();

        if (clearing.isEmpty()) {
            if (auth.isEmpty()) {
                return new Reward(record.userId(), record.rewardId(), Kind.IN_STORE, offer.merchantId(),
                    RewardStatus.PENDING, 0, 0, "refund before purchase");
            }
            if (asOf >= auth.get().atMillis() + offer.authExpiryDays() * DAY) {
                return new Reward(record.userId(), record.rewardId(), Kind.IN_STORE, offer.merchantId(),
                    RewardStatus.DECLINED, 0, 0, "authorisation expired");
            }
            long estimate = capped(offer, Math.max(0, auth.get().amountCents() - refunded));
            return new Reward(record.userId(), record.rewardId(), Kind.IN_STORE, offer.merchantId(),
                RewardStatus.PENDING, estimate, auth.get().amountCents(), "awaiting clearing");
        }
        long net = Math.max(0, clearing.get().amountCents() - refunded);
        if (net < offer.minSpendCents()) {
            return new Reward(record.userId(), record.rewardId(), Kind.IN_STORE, offer.merchantId(),
                RewardStatus.DECLINED, 0, net, "below minimum spend");
        }
        long cashback = capped(offer, net);
        boolean held = asOf < clearing.get().atMillis() + offer.holdDays() * DAY;
        return new Reward(record.userId(), record.rewardId(), Kind.IN_STORE, offer.merchantId(),
            held ? RewardStatus.PENDING : RewardStatus.CONFIRMED, cashback, net,
            held ? "in return window" : "hold passed");
    }

    private static long capped(Terms.Offer offer, long net) {
        if (net < offer.minSpendCents()) {
            return 0;
        }
        return Math.min(offer.maxCashbackCents(), Cashback.ofAmount(net, offer.cashbackBp()));
    }

    private static String offerOf(Fact f) {
        return switch (f) {
            case Fact.CardAuth a -> a.offerId();
            case Fact.CardClearing c -> c.offerId();
            case Fact.CardRefund r -> r.offerId();
            case Fact.NetworkUpdate n -> null;
        };
    }
}
