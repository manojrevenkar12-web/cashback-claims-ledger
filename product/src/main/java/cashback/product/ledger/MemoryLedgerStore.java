package cashback.product.ledger;

import cashback.product.ledger.Records.AccountRecord;
import cashback.product.ledger.Records.RewardRecord;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** The in-memory store. Compare-and-set is done per key with {@link ConcurrentHashMap#compute}. */
public final class MemoryLedgerStore implements LedgerStore {

    private final Map<String, RewardRecord> rewards = new ConcurrentHashMap<>();
    private final Map<String, String> orders = new ConcurrentHashMap<>();
    private final Map<String, AccountRecord> accounts = new ConcurrentHashMap<>();

    private static String key(String userId, String rewardId) {
        return userId + "|" + rewardId;
    }

    @Override
    public Optional<RewardRecord> reward(String userId, String rewardId) {
        return Optional.ofNullable(rewards.get(key(userId, rewardId)));
    }

    @Override
    public boolean putReward(RewardRecord next) {
        boolean[] stored = {false};
        rewards.compute(key(next.userId(), next.rewardId()), (k, current) -> {
            long have = current == null ? 0 : current.version();
            if (have != next.version() - 1) {
                return current;
            }
            stored[0] = true;
            return next;
        });
        if (stored[0]) {
            next.orderKey().ifPresent(o -> orders.putIfAbsent(o, key(next.userId(), next.rewardId())));
        }
        return stored[0];
    }

    @Override
    public List<RewardRecord> rewards(String userId) {
        List<RewardRecord> out = new ArrayList<>();
        for (RewardRecord r : rewards.values()) {
            if (r.userId().equals(userId)) {
                out.add(r);
            }
        }
        out.sort(Comparator.comparing(RewardRecord::rewardId));
        return out;
    }

    @Override
    public Optional<RewardRecord> rewardForOrder(String merchantId, String orderId) {
        String k = orders.get(RewardRecord.orderKey(merchantId, orderId));
        return k == null ? Optional.empty() : Optional.ofNullable(rewards.get(k));
    }

    @Override
    public AccountRecord account(String userId) {
        return accounts.getOrDefault(userId, AccountRecord.empty(userId));
    }

    @Override
    public boolean putAccount(AccountRecord next) {
        boolean[] stored = {false};
        accounts.compute(next.userId(), (k, current) -> {
            long have = current == null ? 0 : current.version();
            if (have != next.version() - 1) {
                return current;
            }
            stored[0] = true;
            return next;
        });
        return stored[0];
    }

    public int size() {
        return rewards.size();
    }
}
