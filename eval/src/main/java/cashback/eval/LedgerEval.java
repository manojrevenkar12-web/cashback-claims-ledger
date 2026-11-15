package cashback.eval;

import cashback.product.ledger.Fact;
import cashback.product.ledger.Ledger;
import cashback.product.ledger.MemoryLedgerStore;
import cashback.product.ledger.RewardFold;
import cashback.product.ledger.RewardStatus;
import cashback.product.terms.Cashback;
import cashback.product.terms.Terms;
import cashback.world.Model.Event;
import cashback.world.Model.RewardTruth;
import cashback.world.World;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Replays every delivered event (late, duplicated, replayed) through three consumers and compares each user's pending
 * and confirmed cashback with what the networks and card schemes actually said.
 */
final class LedgerEval {

    private static final long DAY = 86_400_000L;

    private final World w;
    private final Terms terms;

    LedgerEval(World w, Terms terms) {
        this.w = w;
        this.terms = terms;
    }

    record View(RewardStatus status, long cashbackCents) {
    }

    Map<String, Object> run() {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> designs = new LinkedHashMap<>();
        designs.put("arrival_order", score(naive(false)));
        designs.put("dedupe_only", score(naive(true)));
        Map<String, View> sequential = ledger(1);
        designs.put("ledger", score(sequential));
        out.put("designs", designs);
        Map<String, View> parallel = ledger(8);
        out.put("parallelConsumers", 8L);
        out.put("parallelIdentical", parallel.equals(sequential));
        Set<String> ids = new HashSet<>();
        long dups = 0;
        for (Event e : w.events) {
            if (!ids.add(e.eventId())) {
                dups++;
            }
        }
        out.put("eventsDelivered", (long) w.events.size());
        out.put("repeatedDeliveries", dups);
        out.put("outOfOrderDeliveries", outOfOrder());
        return out;
    }

    /** Deliveries that reached us before an earlier update about the same reward. */
    private long outOfOrder() {
        Map<String, Long> latest = new HashMap<>();
        long n = 0;
        for (Event e : w.events) {
            Fact f = Wiring.fact(e.fact());
            long t = switch (f) {
                case Fact.NetworkUpdate u -> u.sourceVersion();
                case Fact.CardAuth a -> a.atMillis();
                case Fact.CardClearing c -> c.atMillis();
                case Fact.CardRefund r -> r.atMillis();
            };
            String key = f.userId() + "|" + f.rewardId();
            Long seen = latest.get(key);
            if (seen != null && t < seen) {
                n++;
            }
            latest.merge(key, t, Math::max);
        }
        return n;
    }

    private Map<String, View> ledger(int threads) {
        MemoryLedgerStore store = new MemoryLedgerStore();
        RewardFold fold = new RewardFold(terms);
        Ledger ledger = new Ledger(store, fold, 1000, 200);
        if (threads == 1) {
            for (Event e : w.events) {
                ledger.apply(Wiring.fact(e.fact()));
            }
        } else {
            List<Event> shuffled = new ArrayList<>(w.events);
            Collections.shuffle(shuffled, new java.util.Random(42));
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            try {
                List<Future<?>> jobs = new ArrayList<>();
                int chunk = (shuffled.size() + threads - 1) / threads;
                for (int t = 0; t < threads; t++) {
                    List<Event> part = shuffled.subList(Math.min(shuffled.size(), t * chunk),
                        Math.min(shuffled.size(), (t + 1) * chunk));
                    jobs.add(pool.submit(() -> part.forEach(e -> ledger.apply(Wiring.fact(e.fact())))));
                }
                for (Future<?> f : jobs) {
                    f.get();
                }
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            } finally {
                pool.shutdown();
            }
        }
        Map<String, View> out = new LinkedHashMap<>();
        for (String user : users()) {
            for (RewardFold.Reward r : ledger.balance(user, w.endMillis).rewards()) {
                out.put(user + "|" + r.rewardId(), new View(r.status(), r.cashbackCents()));
            }
        }
        return out;
    }

    private Set<String> users() {
        Set<String> users = new TreeSet<>();
        for (RewardTruth t : w.rewardTruths.values()) {
            users.add(t.userId());
        }
        return users;
    }

    /** A consumer that updates one row per reward with whatever event came last, optionally skipping repeats. */
    private Map<String, View> naive(boolean dedupe) {
        record Row(RewardStatus status, long cashback, String offerId, long base, Long authAt, Long clearedAt) {
        }
        Map<String, Row> rows = new HashMap<>();
        Set<String> seen = new HashSet<>();
        for (Event e : w.events) {
            if (dedupe && !seen.add(e.eventId())) {
                continue;
            }
            Fact f = Wiring.fact(e.fact());
            String key = f.userId() + "|" + f.rewardId();
            Row row = rows.get(key);
            switch (f) {
                case Fact.NetworkUpdate u -> rows.put(key, new Row(u.status(), u.cashbackCents(), null, 0, null, null));
                case Fact.CardAuth a -> rows.put(key, new Row(RewardStatus.PENDING, 0, a.offerId(), a.amountCents(),
                    a.atMillis(), null));
                case Fact.CardClearing c -> rows.put(key, new Row(RewardStatus.PENDING, 0, c.offerId(), c.amountCents(),
                    row == null ? null : row.authAt(), c.atMillis()));
                case Fact.CardRefund r -> rows.put(key, new Row(RewardStatus.PENDING, 0, r.offerId(),
                    (row == null ? 0 : row.base()) - r.amountCents(), row == null ? null : row.authAt(),
                    row == null ? null : row.clearedAt()));
            }
        }
        Map<String, View> out = new LinkedHashMap<>();
        for (Map.Entry<String, Row> e : rows.entrySet()) {
            Row r = e.getValue();
            if (r.offerId() == null) {
                out.put(e.getKey(), new View(r.status(), r.status() == RewardStatus.DECLINED ? 0 : r.cashback()));
                continue;
            }
            Terms.Offer offer = terms.offer(r.offerId());
            long base = Math.max(0, r.base());
            long cashback = base < offer.minSpendCents() ? 0
                : Math.min(offer.maxCashbackCents(), Cashback.ofAmount(base, offer.cashbackBp()));
            RewardStatus status;
            if (r.clearedAt() == null) {
                boolean expired = r.authAt() != null && w.endMillis >= r.authAt() + offer.authExpiryDays() * DAY;
                status = expired ? RewardStatus.DECLINED : RewardStatus.PENDING;
            } else if (base < offer.minSpendCents()) {
                status = RewardStatus.DECLINED;
            } else {
                status = w.endMillis < r.clearedAt() + offer.holdDays() * DAY ? RewardStatus.PENDING
                    : RewardStatus.CONFIRMED;
            }
            out.put(e.getKey(), new View(status, status == RewardStatus.DECLINED ? 0 : cashback));
        }
        return out;
    }

    private Map<String, Object> score(Map<String, View> views) {
        Map<String, long[]> perUser = new HashMap<>();
        Map<String, long[]> truthUser = new HashMap<>();
        long wrongStatus = 0;
        long overstated = 0;
        for (RewardTruth t : w.rewardTruths.values()) {
            String key = t.userId() + "|" + t.rewardId();
            View v = views.get(key);
            RewardStatus truth = RewardStatus.valueOf(t.status());
            if (v == null || v.status() != truth) {
                wrongStatus++;
            }
            long[] tu = truthUser.computeIfAbsent(t.userId(), x -> new long[2]);
            add(tu, truth, t.cashbackCents());
            long[] pu = perUser.computeIfAbsent(t.userId(), x -> new long[2]);
            if (v != null) {
                add(pu, v.status(), v.cashbackCents());
            }
        }
        long exact = 0;
        long centsOff = 0;
        for (Map.Entry<String, long[]> e : truthUser.entrySet()) {
            long[] t = e.getValue();
            long[] p = perUser.get(e.getKey());
            long off = Math.abs(t[0] - p[0]) + Math.abs(t[1] - p[1]);
            centsOff += off;
            overstated += Math.max(0, p[1] - t[1]);
            if (off == 0) {
                exact++;
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("users", (long) truthUser.size());
        m.put("usersExact", exact);
        m.put("usersExactShare", ClaimsEval.ratio(exact, truthUser.size()));
        m.put("rewards", (long) w.rewardTruths.size());
        m.put("wrongStatus", wrongStatus);
        m.put("centsOff", centsOff);
        m.put("confirmedOverstatedCents", overstated);
        return m;
    }

    private static void add(long[] a, RewardStatus s, long cents) {
        if (s == RewardStatus.PENDING) {
            a[0] += cents;
        } else if (s == RewardStatus.CONFIRMED) {
            a[1] += cents;
        }
    }
}
