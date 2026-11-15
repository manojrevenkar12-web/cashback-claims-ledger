package cashback.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Keyed randomness: every draw is a hash of (seed, purpose, ids). Two designs that ask about the same claim get the
 * same answer, whatever order they ask in, so a difference in results is a difference in the designs.
 */
public final class Keyed {

    private final String seed;

    public Keyed(String seed) {
        this.seed = seed;
    }

    public String seed() {
        return seed;
    }

    /** A uniform draw in [0, 1). */
    public double u(String purpose, Object... ids) {
        StringBuilder key = new StringBuilder(seed).append('|').append(purpose);
        for (Object id : ids) {
            key.append('|').append(id);
        }
        byte[] h = sha256(key.toString());
        long bits = 0;
        for (int i = 0; i < 7; i++) {
            bits = (bits << 8) | (h[i] & 0xff);
        }
        return (bits >>> 3) / (double) (1L << 53);
    }

    public boolean chance(double p, String purpose, Object... ids) {
        return u(purpose, ids) < p;
    }

    /** A uniform integer in [0, n). */
    public int pick(int n, String purpose, Object... ids) {
        return (int) Math.min(n - 1, Math.floor(u(purpose, ids) * n));
    }

    /** A uniform integer in [lo, hi]. */
    public long between(long lo, long hi, String purpose, Object... ids) {
        return lo + (long) Math.floor(u(purpose, ids) * (hi - lo + 1));
    }

    /** An exponential draw with the given mean. */
    public double exponential(double mean, String purpose, Object... ids) {
        return -mean * StrictMath.log(1.0 - u(purpose, ids)); // StrictMath: the same bits on every JVM
    }

    public static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) {
            out.append(String.format("%02x", b));
        }
        return out.toString();
    }
}
