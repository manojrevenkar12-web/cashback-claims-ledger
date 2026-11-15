package cashback.product.ledger;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** What the stores keep. Every record carries a version; a write names the version it read. */
public final class Records {

    private Records() {
    }

    public record RewardRecord(String userId, String rewardId, List<Fact> facts, long version) {

        public RewardRecord {
            facts = List.copyOf(facts);
        }

        public static RewardRecord empty(String userId, String rewardId) {
            return new RewardRecord(userId, rewardId, List.of(), 0);
        }

        public boolean has(String eventId) {
            return facts.stream().anyMatch(f -> f.eventId().equals(eventId));
        }

        public RewardRecord with(Fact fact) {
            List<Fact> next = new ArrayList<>(facts);
            next.add(fact);
            return new RewardRecord(userId, rewardId, next, version + 1);
        }

        /** The order this reward is for, if a network has named one. */
        public Optional<String> orderKey() {
            return facts.stream().filter(f -> f instanceof Fact.NetworkUpdate).map(f -> (Fact.NetworkUpdate) f)
                .findFirst().map(n -> orderKey(n.merchantId(), n.orderId()));
        }

        public static String orderKey(String merchantId, String orderId) {
            return merchantId + "#" + normaliseOrderId(orderId);
        }
    }

    public record Payout(String payoutId, long amountCents, long atMillis) {
    }

    public record AccountRecord(String userId, List<Payout> payouts, long version) {

        public AccountRecord {
            payouts = List.copyOf(payouts);
        }

        public static AccountRecord empty(String userId) {
            return new AccountRecord(userId, List.of(), 0);
        }

        public Optional<Payout> payout(String payoutId) {
            return payouts.stream().filter(p -> p.payoutId().equals(payoutId)).findFirst();
        }

        public long paidOutCents() {
            return payouts.stream().mapToLong(Payout::amountCents).sum();
        }

        public AccountRecord with(Payout payout) {
            List<Payout> next = new ArrayList<>(payouts);
            next.add(payout);
            return new AccountRecord(userId, next, version + 1);
        }
    }

    /** Order numbers are compared without case, spaces or dashes: "nm-4821 377" is "NM4821377". */
    public static String normaliseOrderId(String orderId) {
        return orderId == null ? "" : orderId.toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }
}
