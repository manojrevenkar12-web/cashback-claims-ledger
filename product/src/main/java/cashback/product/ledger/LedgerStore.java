package cashback.product.ledger;

import cashback.product.ledger.Records.AccountRecord;
import cashback.product.ledger.Records.RewardRecord;
import java.util.List;
import java.util.Optional;

/**
 * Where rewards and accounts live. Writes are compare-and-set on the version: {@code put} returns false, and changes
 * nothing, when someone else wrote first. In-memory, Postgres and DynamoDB implementations keep the same contract.
 */
public interface LedgerStore {

    Optional<RewardRecord> reward(String userId, String rewardId);

    /** Stores {@code next} if the stored version is still {@code next.version() - 1} (0 means absent). */
    boolean putReward(RewardRecord next);

    List<RewardRecord> rewards(String userId);

    /** The reward a network has reported for this order, if any. */
    Optional<RewardRecord> rewardForOrder(String merchantId, String orderId);

    AccountRecord account(String userId);

    /** Stores {@code next} if the stored version is still {@code next.version() - 1}. */
    boolean putAccount(AccountRecord next);
}
