package cashback.product;

import static cashback.product.Fixtures.DAY;
import static cashback.product.Fixtures.TERMS;
import static cashback.product.Fixtures.at;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cashback.common.Json;
import cashback.product.ledger.Fact;
import cashback.product.ledger.Ledger;
import cashback.product.ledger.MemoryLedgerStore;
import cashback.product.ledger.RewardFold;
import cashback.product.ledger.RewardStatus;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class LedgerTest {

    private static Ledger ledger() {
        return new Ledger(new MemoryLedgerStore(), new RewardFold(TERMS), 1000, 50);
    }

    private static Fact.NetworkUpdate update(String event, String reward, long cashback, RewardStatus s, long version) {
        return new Fact.NetworkUpdate(event, "u1", reward, "m-nordlicht", "NO-1-" + reward, cashback * 12, cashback, s,
            version);
    }

    @Test
    void aDuplicateIsAppliedOnce() {
        Ledger l = ledger();
        Fact f = update("e1", "r1", 500, RewardStatus.CONFIRMED, 1);
        assertEquals(Ledger.Applied.APPLIED, l.apply(f));
        assertEquals(Ledger.Applied.DUPLICATE, l.apply(f));
        assertEquals(500, l.balance("u1", 0).confirmedCents());
    }

    @Test
    void theNetworksLatestUpdateWinsWhateverOrderItArrivesIn() {
        List<Fact> facts = List.of(update("e1", "r1", 500, RewardStatus.PENDING, 1),
            update("e2", "r1", 300, RewardStatus.PENDING, 2), update("e3", "r1", 300, RewardStatus.CONFIRMED, 3),
            update("e4", "r1", 300, RewardStatus.DECLINED, 4), update("e5", "r2", 900, RewardStatus.PENDING, 1),
            update("e6", "r2", 900, RewardStatus.CONFIRMED, 2));
        Random rnd = new Random(7);
        for (int round = 0; round < 50; round++) {
            List<Fact> shuffled = new ArrayList<>(facts);
            shuffled.addAll(facts.subList(0, rnd.nextInt(facts.size())));
            Collections.shuffle(shuffled, rnd);
            Ledger l = ledger();
            shuffled.forEach(l::apply);
            Ledger.Balance b = l.balance("u1", 0);
            assertEquals(0, b.pendingCents());
            assertEquals(900, b.confirmedCents(), "round " + round);
        }
    }

    @Test
    void cardRewardsFollowClearingRefundsHoldAndExpiry() {
        // Sportwelt store offer: 5%, minimum 30 EUR, cap 25 EUR, 30-day hold, auth expires after 7 days.
        long t = at("2026-08-01", 12);
        Ledger l = ledger();
        l.apply(new Fact.CardAuth("a1", "u1", "t1", "o-sportwelt-store", 10_000, t));
        assertEquals(500, l.balance("u1", t + DAY).pendingCents(), "estimated on the authorisation");
        l.apply(new Fact.CardClearing("c1", "u1", "t1", "o-sportwelt-store", 12_000, t + DAY));
        assertEquals(600, l.balance("u1", t + 2 * DAY).pendingCents(), "the cleared amount counts");
        assertEquals(600, l.balance("u1", t + 32 * DAY).confirmedCents(), "confirmed once the hold passed");
        l.apply(new Fact.CardRefund("f1", "u1", "t1", "o-sportwelt-store", "rf1", 4_000, t + 5 * DAY));
        l.apply(new Fact.CardRefund("f1b", "u1", "t1", "o-sportwelt-store", "rf1", 4_000, t + 5 * DAY));
        assertEquals(400, l.balance("u1", t + 32 * DAY).confirmedCents(), "one refund id counts once");
        l.apply(new Fact.CardRefund("f2", "u1", "t1", "o-sportwelt-store", "rf2", 6_000, t + 6 * DAY));
        RewardFold.Reward r = l.balance("u1", t + 32 * DAY).rewards().get(0);
        assertEquals(RewardStatus.DECLINED, r.status(), "20 EUR left is below the 30 EUR minimum");

        Ledger e = ledger();
        e.apply(new Fact.CardAuth("a2", "u2", "t2", "o-sportwelt-store", 10_000, t));
        assertEquals(RewardStatus.PENDING, e.balance("u2", t + 6 * DAY).rewards().get(0).status());
        assertEquals(RewardStatus.DECLINED, e.balance("u2", t + 7 * DAY).rewards().get(0).status(), "never cleared");
    }

    @Test
    void cappedAndRoundedDown() {
        long t = at("2026-08-01", 12);
        Ledger l = ledger();
        l.apply(new Fact.CardClearing("c1", "u1", "t1", "o-kaffeewerk-store", 999, t));
        assertEquals(99, l.balance("u1", t).pendingCents(), "10% of 9.99 is 0.99, rounded down");
        l.apply(new Fact.CardClearing("c2", "u1", "t2", "o-kaffeewerk-store", 90_000, t));
        assertEquals(99 + 500, l.balance("u1", t).pendingCents(), "capped at 5 EUR");
    }

    @Test
    void payoutsAreIdempotentAndAboveTheThreshold() {
        Ledger l = ledger();
        l.apply(update("e1", "r1", 800, RewardStatus.CONFIRMED, 1));
        assertInstanceOf(Ledger.Refused.class, l.requestPayout("u1", "p1", 0));
        l.apply(update("e2", "r2", 700, RewardStatus.CONFIRMED, 1));
        Ledger.Paid paid = assertInstanceOf(Ledger.Paid.class, l.requestPayout("u1", "p1", 0));
        assertEquals(1500, paid.payout().amountCents());
        Ledger.Paid again = assertInstanceOf(Ledger.Paid.class, l.requestPayout("u1", "p1", 0));
        assertTrue(again.repeated());
        assertEquals(0, l.balance("u1", 0).availableCents());
    }

    @Test
    void aDeclineAfterPayoutIsOwed() {
        Ledger l = ledger();
        l.apply(update("e1", "r1", 1500, RewardStatus.CONFIRMED, 1));
        l.requestPayout("u1", "p1", 0);
        l.apply(update("e2", "r1", 1500, RewardStatus.DECLINED, 2));
        Ledger.Balance b = l.balance("u1", 0);
        assertEquals(-1500, b.availableCents());
        assertEquals(1500, b.owedCents());
        Ledger.Refused refused = assertInstanceOf(Ledger.Refused.class, l.requestPayout("u1", "p2", 0));
        assertEquals("a declined reward is still owed", refused.reason());
    }

    @Test
    void parallelConsumersGiveTheSameResultAsOne() throws Exception {
        List<Fact> facts = new ArrayList<>();
        for (int r = 0; r < 200; r++) {
            for (int v = 1; v <= 4; v++) {
                RewardStatus s = v < 3 ? RewardStatus.PENDING : (r % 5 == 0 && v == 4 ? RewardStatus.DECLINED
                    : RewardStatus.CONFIRMED);
                if (v == 4 && r % 5 != 0) {
                    continue;
                }
                facts.add(update("e" + r + "-" + v, "r" + r, 100 + r, s, v));
            }
        }
        Ledger one = ledger();
        facts.forEach(one::apply);
        List<Fact> doubled = new ArrayList<>(facts);
        doubled.addAll(facts);
        Collections.shuffle(doubled, new Random(3));
        Ledger many = ledger();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> jobs = new ArrayList<>();
        for (int t = 0; t < 8; t++) {
            List<Fact> part = doubled.subList(t * doubled.size() / 8, (t + 1) * doubled.size() / 8);
            jobs.add(pool.submit(() -> {
                go.await();
                part.forEach(many::apply);
                return null;
            }));
        }
        go.countDown();
        for (Future<?> j : jobs) {
            j.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertEquals(one.balance("u1", 0).confirmedCents(), many.balance("u1", 0).confirmedCents());
        assertEquals(one.balance("u1", 0).rewards(), many.balance("u1", 0).rewards());
    }

    @Test
    void racingPayoutsPayOnce() throws Exception {
        for (int round = 0; round < 20; round++) {
            Ledger l = ledger();
            l.apply(update("e1", "r1", 5000, RewardStatus.CONFIRMED, 1));
            ExecutorService pool = Executors.newFixedThreadPool(6);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Ledger.PayoutResult>> results = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                String id = "p" + i;
                results.add(pool.submit(() -> {
                    go.await();
                    return l.requestPayout("u1", id, 0);
                }));
            }
            go.countDown();
            long paid = 0;
            for (Future<Ledger.PayoutResult> f : results) {
                if (f.get() instanceof Ledger.Paid p) {
                    paid += p.payout().amountCents();
                }
            }
            pool.shutdown();
            assertEquals(5000, paid, "round " + round);
            assertEquals(0, l.balance("u1", 0).availableCents());
        }
    }

    @Test
    void factsSurviveTheirJsonFormAndBadOnesAreRefused() {
        List<Fact> facts = List.of(update("e1", "r1", 5, RewardStatus.PENDING, 9),
            new Fact.CardAuth("a", "u", "t", "o-kaffeewerk-store", 10, 1),
            new Fact.CardClearing("c", "u", "t", "o-kaffeewerk-store", 10, 2),
            new Fact.CardRefund("f", "u", "t", "o-kaffeewerk-store", "rf", 10, 3));
        for (Fact f : facts) {
            assertEquals(f, Fact.decode(Json.object(Json.compact(Fact.encode(f)))));
        }
        Map<String, Object> bad = Fact.encode(facts.get(1));
        bad.put("amountCents", -5L);
        assertThrows(IllegalArgumentException.class, () -> Fact.decode(bad));
        Map<String, Object> blank = Fact.encode(facts.get(1));
        blank.put("userId", " ");
        assertThrows(IllegalArgumentException.class, () -> Fact.decode(blank));
        Map<String, Object> unknown = Fact.encode(facts.get(1));
        unknown.put("type", "card_magic");
        assertThrows(IllegalArgumentException.class, () -> Fact.decode(unknown));
    }
}
