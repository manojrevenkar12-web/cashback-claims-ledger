package cashback.eval;

import cashback.common.Keyed;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

final class Stats {

    private static final long DAY = 86_400_000L;
    static final int RESAMPLES = 2000;

    private Stats() {
    }

    /** Difference in right share (a - b) on the same claims, with a 90% interval from a paired bootstrap. */
    static Map<String, Object> pairedBootstrap(Keyed k, String purpose, boolean[] a, boolean[] b) {
        int n = a.length;
        double[] diffs = new double[RESAMPLES];
        // One keyed seed per comparison; the resampling stream follows from it, so every run gives the same interval.
        SplittableRandom rng = new SplittableRandom((long) (k.u(purpose) * Long.MAX_VALUE));
        for (int s = 0; s < RESAMPLES; s++) {
            long d = 0;
            for (int i = 0; i < n; i++) {
                int j = rng.nextInt(n);
                d += (a[j] ? 1 : 0) - (b[j] ? 1 : 0);
            }
            diffs[s] = d / (double) n;
        }
        Arrays.sort(diffs);
        long d = 0;
        for (int i = 0; i < n; i++) {
            d += (a[i] ? 1 : 0) - (b[i] ? 1 : 0);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("difference", round4(d / (double) n));
        m.put("lo90", round4(diffs[(int) (RESAMPLES * 0.05)]));
        m.put("hi90", round4(diffs[(int) (RESAMPLES * 0.95) - 1]));
        return m;
    }

    record Queue(long late, long p90Days, long maxDays) {
    }

    /**
     * First-come, first-served support queue with a fixed number of minutes a day. A case is answered on the day the
     * minutes for it run out; it is late when that is more than {@code slaDays} after it arrived.
     */
    static Queue queue(List<long[]> cases, double minutesPerDay, int slaDays) {
        List<long[]> sorted = new ArrayList<>(cases);
        sorted.sort(Comparator.comparingLong(c -> c[0]));
        long day = Long.MIN_VALUE;
        double left = 0;
        long late = 0;
        List<Long> waits = new ArrayList<>();
        for (long[] c : sorted) {
            long arrival = Math.floorDiv(c[0], DAY);
            if (day < arrival) {
                day = arrival;
                left = minutesPerDay;
            }
            double need = c[1];
            while (left < need) {
                need -= left;
                day++;
                left = minutesPerDay;
            }
            left -= need;
            long wait = day - arrival;
            waits.add(wait);
            if (wait > slaDays) {
                late++;
            }
        }
        waits.sort(Long::compare);
        long p90 = waits.isEmpty() ? 0 : waits.get((int) Math.min(waits.size() - 1, Math.floor(waits.size() * 0.9)));
        long max = waits.isEmpty() ? 0 : waits.get(waits.size() - 1);
        return new Queue(late, p90, max);
    }

    static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
