package cashback.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cashback.product.audit.Audit;
import cashback.product.ledger.Fact;
import cashback.product.ledger.LedgerStore;
import cashback.product.ledger.MemoryLedgerStore;
import cashback.product.ledger.Records.AccountRecord;
import cashback.product.ledger.Records.Payout;
import cashback.product.ledger.Records.RewardRecord;
import cashback.product.ledger.RewardStatus;
import cashback.service.store.DynamoLedgerStore;
import cashback.service.store.PostgresStores;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Every ledger store keeps the same contract; CI runs the Postgres and DynamoDB ones against real engines. */
class StoreContractTest {

    static void contract(LedgerStore store) {
        String user = "u-" + UUID.randomUUID();
        Fact f1 = new Fact.NetworkUpdate("e1", user, "r1", "m-nordlicht", "NO-1-2", 1000, 80, RewardStatus.PENDING, 1);
        Fact f2 = new Fact.NetworkUpdate("e2", user, "r1", "m-nordlicht", "NO-1-2", 1000, 80, RewardStatus.CONFIRMED,
            2);
        RewardRecord first = RewardRecord.empty(user, "r1").with(f1);
        assertTrue(store.putReward(first));
        assertFalse(store.putReward(first), "a second create loses");
        RewardRecord second = first.with(f2);
        assertTrue(store.putReward(second));
        assertFalse(store.putReward(first.with(f2)), "a write from a stale version loses");
        assertEquals(second, store.reward(user, "r1").orElseThrow());
        assertEquals(1, store.rewards(user).size());
        assertEquals("r1", store.rewardForOrder("m-nordlicht", "no-1 2").orElseThrow().rewardId());
        assertTrue(store.rewardForOrder("m-nordlicht", "NO-9-9").isEmpty());

        AccountRecord a = AccountRecord.empty(user).with(new Payout("p1", 500, 1));
        assertTrue(store.putAccount(a));
        assertFalse(store.putAccount(AccountRecord.empty(user).with(new Payout("p2", 500, 1))));
        assertEquals(500, store.account(user).paidOutCents());
        assertTrue(store.reward("nobody", "r1").isEmpty());
        assertEquals(0, store.account("nobody-" + user).version());
    }

    @Test
    void memory() {
        contract(new MemoryLedgerStore());
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "CASHBACK_TEST_DATABASE_URL", matches = ".+")
    void postgres() {
        PostgresStores pg = new PostgresStores(CoreConfig.dataSource(System.getenv("CASHBACK_TEST_DATABASE_URL")));
        contract(pg.ledger());
        Audit audit = new Audit(pg.audit());
        audit.record(1, "test", "a", "s", "d");
        audit.record(2, "test", "b", "s", "d");
        assertTrue(audit.verify().intact());
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "CASHBACK_TEST_DYNAMODB_ENDPOINT", matches = ".+")
    void dynamodb() {
        DynamoLedgerStore d = new DynamoLedgerStore(CoreConfig.dynamo(System.getenv(
            "CASHBACK_TEST_DYNAMODB_ENDPOINT"), "eu-central-1"), "cashback-contract");
        d.createTableIfMissing();
        contract(d);
    }
}
