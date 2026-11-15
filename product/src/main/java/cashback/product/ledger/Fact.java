package cashback.product.ledger;

import cashback.common.Json;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Something a network or a card scheme told us about one reward. Facts are kept, never overwritten: the reward is
 * computed from the set of facts, so the order they arrive in and how often they arrive do not matter.
 */
public sealed interface Fact permits Fact.NetworkUpdate, Fact.CardAuth, Fact.CardClearing, Fact.CardRefund {

    String eventId();

    String userId();

    String rewardId();

    /**
     * An affiliate network's view of an online order. {@code sourceVersion} is the network's own update time for
     * this transaction; a higher one supersedes a lower one.
     */
    record NetworkUpdate(String eventId, String userId, String rewardId, String merchantId, String orderId,
                         long saleCents, long cashbackCents, RewardStatus status, long sourceVersion) implements Fact {
    }

    record CardAuth(String eventId, String userId, String rewardId, String offerId, long amountCents, long atMillis)
        implements Fact {
    }

    record CardClearing(String eventId, String userId, String rewardId, String offerId, long amountCents,
                        long atMillis) implements Fact {
    }

    record CardRefund(String eventId, String userId, String rewardId, String offerId, String refundId,
                      long amountCents, long atMillis) implements Fact {
    }

    static Map<String, Object> encode(Fact f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("eventId", f.eventId());
        m.put("userId", f.userId());
        m.put("rewardId", f.rewardId());
        switch (f) {
            case NetworkUpdate n -> {
                m.put("type", "network_update");
                m.put("merchantId", n.merchantId());
                m.put("orderId", n.orderId());
                m.put("saleCents", n.saleCents());
                m.put("cashbackCents", n.cashbackCents());
                m.put("status", n.status().name());
                m.put("sourceVersion", n.sourceVersion());
            }
            case CardAuth a -> {
                m.put("type", "card_auth");
                m.put("offerId", a.offerId());
                m.put("amountCents", a.amountCents());
                m.put("atMillis", a.atMillis());
            }
            case CardClearing c -> {
                m.put("type", "card_clearing");
                m.put("offerId", c.offerId());
                m.put("amountCents", c.amountCents());
                m.put("atMillis", c.atMillis());
            }
            case CardRefund r -> {
                m.put("type", "card_refund");
                m.put("offerId", r.offerId());
                m.put("refundId", r.refundId());
                m.put("amountCents", r.amountCents());
                m.put("atMillis", r.atMillis());
            }
        }
        return m;
    }

    /** Reads a fact from its JSON form; anything malformed is refused rather than half-read. */
    static Fact decode(Map<String, Object> m) {
        String type = Json.str(m, "type");
        String eventId = nonBlank(m, "eventId");
        String userId = nonBlank(m, "userId");
        String rewardId = nonBlank(m, "rewardId");
        return switch (type) {
            case "network_update" -> new NetworkUpdate(eventId, userId, rewardId, nonBlank(m, "merchantId"),
                nonBlank(m, "orderId"), nonNegative(m, "saleCents"), nonNegative(m, "cashbackCents"),
                RewardStatus.valueOf(Json.str(m, "status")), nonNegative(m, "sourceVersion"));
            case "card_auth" -> new CardAuth(eventId, userId, rewardId, nonBlank(m, "offerId"),
                nonNegative(m, "amountCents"), nonNegative(m, "atMillis"));
            case "card_clearing" -> new CardClearing(eventId, userId, rewardId, nonBlank(m, "offerId"),
                nonNegative(m, "amountCents"), nonNegative(m, "atMillis"));
            case "card_refund" -> new CardRefund(eventId, userId, rewardId, nonBlank(m, "offerId"),
                nonBlank(m, "refundId"), nonNegative(m, "amountCents"), nonNegative(m, "atMillis"));
            default -> throw new IllegalArgumentException("unknown fact type " + type);
        };
    }

    private static String nonBlank(Map<String, Object> m, String key) {
        String s = Json.str(m, key);
        if (s == null || s.isBlank()) {
            throw new IllegalArgumentException(key + " is blank");
        }
        return s;
    }

    private static long nonNegative(Map<String, Object> m, String key) {
        long v = Json.lng(m, key);
        if (v < 0) {
            throw new IllegalArgumentException(key + " is negative");
        }
        return v;
    }
}
