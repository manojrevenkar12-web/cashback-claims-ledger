package cashback.world;

import java.time.LocalDate;
import java.util.List;

/** The world's own records. Nothing outside the world module and the evaluation sees these. */
public final class Model {

    private Model() {
    }

    /** A published merchant, as the world reads the terms file (its own reading, not the product's classes). */
    public record Shop(String id, String name, String locale, String dateOrder, String network, int cashbackBp,
                       int cookieDays, int claimAfterDays, int claimWithinDays, List<String> excluded,
                       List<String> platformVouchers) {
    }

    public record CardOffer(String id, String shopId, int cashbackBp, long minSpendCents, long maxCashbackCents,
                            int holdDays, int authExpiryDays) {
    }

    public record Line(String description, long cents, String category) {
    }

    public record Order(String orderId, String userId, Shop shop, LocalDate day, long atMillis, List<Line> lines,
                        String voucherCode, boolean platformVoucher, long discountCents, long shippingCents,
                        long totalCents, Long clickAtMillis, boolean validClick, boolean tracked, Long reportAtMillis,
                        String confirmation, boolean hard) {
    }

    public enum Truth { FILE, ALREADY_TRACKED, WAIT, REJECT, FRAUD }

    public record ClaimTruth(Truth kind, String reason, long eligibleCents, long cashbackCents) {
    }

    /** A claim as filed, with what the world knows is right about it. */
    public record Claim(String claimId, String userId, String formShopId, Order order, long filedAtMillis,
                        String formOrderId, LocalDate formOrderDate, long formAmountCents, ClaimTruth truth,
                        boolean fraud) {
    }

    public record Click(String clickId, String userId, String shopId, long atMillis) {
    }

    /** One update a network or card scheme makes, and when it reaches the platform. */
    public record Event(String eventId, long deliveredAtMillis, Object fact) {
    }

    /** What an affiliate network says about an online order at one moment. */
    public record NetworkState(String eventId, String userId, String rewardId, String shopId, String orderId,
                               long saleCents, long cashbackCents, String status, long updatedAtMillis) {
    }

    public enum CardKind { AUTH, CLEARING, REFUND }

    public record CardMessage(String eventId, String userId, String txId, String offerId, CardKind kind,
                              String refundId, long amountCents, long atMillis) {
    }

    /** The truth about one reward at the end of the world: its status and cashback. */
    public record RewardTruth(String userId, String rewardId, String status, long cashbackCents) {
    }
}
