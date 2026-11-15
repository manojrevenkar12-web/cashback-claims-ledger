package cashback.product.ledger;

import cashback.product.ledger.Records.AccountRecord;
import cashback.product.ledger.Records.Payout;
import cashback.product.ledger.Records.RewardRecord;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Applies facts exactly once and pays out what is available. Every write is compare-and-set, so any number of
 * consumers can apply facts for the same reward at the same time and the result is the same as one at a time.
 */
public final class Ledger {

    private final LedgerStore store;
    private final RewardFold fold;
    private final long payoutThresholdCents;
    private final int maxRetries;

    public Ledger(LedgerStore store, RewardFold fold, long payoutThresholdCents, int maxRetries) {
        this.store = store;
        this.fold = fold;
        this.payoutThresholdCents = payoutThresholdCents;
        this.maxRetries = maxRetries;
    }

    public enum Applied { APPLIED, DUPLICATE }

    public record Balance(String userId, long pendingCents, long confirmedCents, long paidOutCents,
                          long availableCents, List<RewardFold.Reward> rewards) {

        /** Cashback paid out that a later decline took back; the user owes it against future cashback. */
        public long owedCents() {
            return Math.max(0, -availableCents);
        }
    }

    public sealed interface PayoutResult permits Paid, Refused {
    }

    public record Paid(Payout payout, boolean repeated) implements PayoutResult {
    }

    public record Refused(String reason, long availableCents) implements PayoutResult {
    }

    public Applied apply(Fact fact) {
        for (int attempt = 0; attempt < maxRetries; attempt++) {
            RewardRecord current = store.reward(fact.userId(), fact.rewardId())
                .orElse(RewardRecord.empty(fact.userId(), fact.rewardId()));
            if (current.has(fact.eventId())) {
                return Applied.DUPLICATE;
            }
            if (store.putReward(current.with(fact))) {
                return Applied.APPLIED;
            }
        }
        throw new IllegalStateException("too much contention on reward " + fact.rewardId());
    }

    public Balance balance(String userId, long asOfMillis) {
        long pending = 0;
        long confirmed = 0;
        List<RewardFold.Reward> rewards = new ArrayList<>();
        for (RewardRecord r : store.rewards(userId)) {
            RewardFold.Reward reward = fold.fold(r, asOfMillis);
            rewards.add(reward);
            switch (reward.status()) {
                case PENDING -> pending += reward.cashbackCents();
                case CONFIRMED -> confirmed += reward.cashbackCents();
                case DECLINED -> {
                }
            }
        }
        long paid = store.account(userId).paidOutCents();
        return new Balance(userId, pending, confirmed, paid, confirmed - paid, rewards);
    }

    /**
     * Pays out everything available, once per payout id. Two requests racing for the same balance cannot both be
     * paid: the account write is compare-and-set, and the loser recomputes and finds nothing left.
     */
    public PayoutResult requestPayout(String userId, String payoutId, long asOfMillis) {
        for (int attempt = 0; attempt < maxRetries; attempt++) {
            AccountRecord account = store.account(userId);
            Optional<Payout> earlier = account.payout(payoutId);
            if (earlier.isPresent()) {
                return new Paid(earlier.get(), true);
            }
            Balance b = balance(userId, asOfMillis);
            long available = b.confirmedCents() - account.paidOutCents();
            if (available < payoutThresholdCents) {
                return new Refused(available < 0 ? "a declined reward is still owed" : "below the payout threshold",
                    available);
            }
            Payout payout = new Payout(payoutId, available, asOfMillis);
            if (store.putAccount(account.with(payout))) {
                return new Paid(payout, false);
            }
        }
        throw new IllegalStateException("too much contention on account " + userId);
    }

    public Optional<RewardFold.Reward> rewardForOrder(String merchantId, String orderId, long asOfMillis) {
        return store.rewardForOrder(merchantId, orderId).map(r -> fold.fold(r, asOfMillis));
    }

    public LedgerStore store() {
        return store;
    }
}
