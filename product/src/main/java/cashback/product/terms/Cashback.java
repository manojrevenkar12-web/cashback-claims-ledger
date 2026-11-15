package cashback.product.terms;

import java.util.List;

/**
 * The cashback arithmetic from the terms, in integer cents. Shipping never earns cashback; a discount is spread over
 * the lines in proportion to their value and the eligible share is rounded down; cashback is rounded down.
 */
public final class Cashback {

    private Cashback() {
    }

    public record Line(String description, long amountCents, boolean eligible) {
    }

    public static long ofAmount(long cents, int basisPoints) {
        if (cents <= 0) {
            return 0;
        }
        return Math.floorDiv(Math.multiplyExact(cents, (long) basisPoints), 10_000L);
    }

    /** The eligible order value: eligible lines, less their proportional part of the discount, rounded down. */
    public static long eligibleValue(List<Line> lines, long discountCents) {
        long all = 0;
        long eligible = 0;
        for (Line l : lines) {
            all += l.amountCents();
            if (l.eligible()) {
                eligible += l.amountCents();
            }
        }
        if (eligible <= 0 || all <= 0) {
            return 0;
        }
        long discountShare = discountCents <= 0 ? 0 : ceilDiv(Math.multiplyExact(discountCents, eligible), all);
        return Math.max(0, eligible - discountShare);
    }

    private static long ceilDiv(long a, long b) {
        return -Math.floorDiv(-a, b);
    }
}
