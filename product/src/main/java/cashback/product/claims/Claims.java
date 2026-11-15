package cashback.product.claims;

import cashback.common.Json;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The shapes a missing-cashback claim passes through. */
public final class Claims {

    private Claims() {
    }

    /**
     * What a shopper sends: the form fields they typed and the order confirmation they pasted. The pasted text is
     * data; nothing in it is ever treated as an instruction.
     */
    public record Ticket(String claimId, String userId, String merchantId, long filedAtMillis, String formOrderId,
                         LocalDate formOrderDate, long formAmountCents, String confirmationText) {

        public Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("claimId", claimId);
            m.put("userId", userId);
            m.put("merchantId", merchantId);
            m.put("filedAtMillis", filedAtMillis);
            m.put("formOrderId", formOrderId);
            m.put("formOrderDate", formOrderDate.toString());
            m.put("formAmountCents", formAmountCents);
            m.put("confirmationText", confirmationText);
            return m;
        }

        public static Ticket fromJson(Map<String, Object> m) {
            return new Ticket(Json.str(m, "claimId"), Json.str(m, "userId"), Json.str(m, "merchantId"),
                Json.lng(m, "filedAtMillis"), Json.str(m, "formOrderId"),
                LocalDate.parse(Json.str(m, "formOrderDate")), Json.lng(m, "formAmountCents"),
                Json.str(m, "confirmationText"));
        }
    }

    public record ReadLine(String description, long amountCents) {
    }

    /**
     * What a reader found on the confirmation. The date stays as printed: the code, which knows the merchant's date
     * order, turns it into a day.
     */
    public record Reading(String merchantName, String orderId, String orderDateText, List<ReadLine> lines,
                          String voucherCode, long discountCents, long shippingCents, long totalCents) {

        public Reading {
            lines = List.copyOf(lines);
        }

        /** The printed figures add up: lines, less the discount, plus shipping, make the total. */
        public boolean addsUp() {
            if (lines.isEmpty() || discountCents < 0 || shippingCents < 0 || totalCents <= 0) {
                return false;
            }
            long sum = 0;
            for (ReadLine l : lines) {
                if (l.amountCents() < 0) {
                    return false;
                }
                sum += l.amountCents();
            }
            return sum - discountCents + shippingCents == totalCents;
        }

        public Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("merchantName", merchantName);
            m.put("orderId", orderId);
            m.put("orderDateText", orderDateText);
            List<Object> ls = new ArrayList<>();
            for (ReadLine l : lines) {
                Map<String, Object> lm = new LinkedHashMap<>();
                lm.put("description", l.description());
                lm.put("amountCents", l.amountCents());
                ls.add(lm);
            }
            m.put("lines", ls);
            m.put("voucherCode", voucherCode);
            m.put("discountCents", discountCents);
            m.put("shippingCents", shippingCents);
            m.put("totalCents", totalCents);
            return m;
        }

        public static Reading fromJson(Map<String, Object> m) {
            List<ReadLine> lines = new ArrayList<>();
            for (Map<String, Object> l : Json.objects(m, "lines")) {
                lines.add(new ReadLine(Json.str(l, "description"), Json.lng(l, "amountCents")));
            }
            Object voucher = m.get("voucherCode");
            return new Reading(Json.strOr(m, "merchantName", ""), Json.strOr(m, "orderId", ""),
                Json.strOr(m, "orderDateText", ""), lines, voucher == null ? null : String.valueOf(voucher),
                Json.lngOr(m, "discountCents", 0), Json.lngOr(m, "shippingCents", 0), Json.lng(m, "totalCents"));
        }
    }

    public enum Outcome { FILE, ALREADY_TRACKED, WAIT, REJECT, SUPPORT }

    public record Check(String name, boolean passed, String detail) {
    }

    /**
     * What the desk decided. {@code eligibleCents} and {@code cashbackCents} are set when the claim is filed (or
     * would be, for a person to approve).
     */
    public record Decision(Outcome outcome, String reason, String orderId, Reading reading, long eligibleCents,
                           long cashbackCents, List<Check> checks, int reads) {

        public Decision {
            checks = List.copyOf(checks);
        }

        public Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("outcome", outcome.name());
            m.put("reason", reason);
            m.put("orderId", orderId);
            m.put("reading", reading == null ? null : reading.toJson());
            m.put("eligibleCents", eligibleCents);
            m.put("cashbackCents", cashbackCents);
            List<Object> cs = new ArrayList<>();
            for (Check c : checks) {
                Map<String, Object> cm = new LinkedHashMap<>();
                cm.put("name", c.name());
                cm.put("passed", c.passed());
                cm.put("detail", c.detail());
                cs.add(cm);
            }
            m.put("checks", cs);
            m.put("reads", (long) reads);
            return m;
        }

        @SuppressWarnings("unchecked")
        public static Decision fromJson(Map<String, Object> m) {
            List<Check> checks = new ArrayList<>();
            for (Map<String, Object> c : Json.objects(m, "checks")) {
                checks.add(new Check(Json.str(c, "name"), Json.bool(c, "passed"), Json.str(c, "detail")));
            }
            Reading reading = m.get("reading") instanceof Map<?, ?> r ? Reading.fromJson((Map<String, Object>) r) : null;
            return new Decision(Outcome.valueOf(Json.str(m, "outcome")), Json.str(m, "reason"),
                (String) m.get("orderId"), reading, Json.lng(m, "eligibleCents"), Json.lng(m, "cashbackCents"), checks, Json.integer(m, "reads"));
        }
    }

    public record Click(String clickId, String userId, String merchantId, long atMillis) {
    }

    /** The platform's own record of shoppers clicking out to merchants. */
    public interface ClickLog {
        List<Click> clicks(String userId, String merchantId);
    }

    /** Other accounts that have claimed the same order. */
    public interface ClaimHistory {
        List<String> otherClaimants(String merchantId, String orderId, String userId, String claimId);
    }
}
