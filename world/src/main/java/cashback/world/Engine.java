package cashback.world;

import cashback.world.Model.CardOffer;
import cashback.world.Model.Line;
import cashback.world.Model.Shop;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * The world's own cashback arithmetic, written apart from the product's so that a mistake in one shows up as a
 * disagreement. It uses BigDecimal where the product uses integer division; a test checks they agree.
 */
public final class Engine {

    private Engine() {
    }

    public static long cashback(long cents, int basisPoints) {
        if (cents <= 0) {
            return 0;
        }
        return BigDecimal.valueOf(cents).multiply(BigDecimal.valueOf(basisPoints))
            .divide(BigDecimal.valueOf(10_000), 0, RoundingMode.FLOOR).longValueExact();
    }

    /** Eligible lines less their share of the discount; the eligible value is rounded down to the cent. */
    public static long eligible(List<Line> lines, long discountCents, Shop shop) {
        BigDecimal all = BigDecimal.ZERO;
        BigDecimal ok = BigDecimal.ZERO;
        for (Line l : lines) {
            all = all.add(BigDecimal.valueOf(l.cents()));
            if (!shop.excluded().contains(l.category())) {
                ok = ok.add(BigDecimal.valueOf(l.cents()));
            }
        }
        if (ok.signum() == 0) {
            return 0;
        }
        // ok - discount * ok / all, rounded down, computed exactly as one fraction.
        BigDecimal numerator = ok.multiply(all).subtract(BigDecimal.valueOf(discountCents).multiply(ok));
        BigDecimal value = numerator.divide(all, 0, RoundingMode.FLOOR);
        return Math.max(0, value.longValueExact());
    }

    public static long cardCashback(CardOffer offer, long netCents) {
        if (netCents < offer.minSpendCents()) {
            return 0;
        }
        return Math.min(offer.maxCashbackCents(), cashback(netCents, offer.cashbackBp()));
    }
}
