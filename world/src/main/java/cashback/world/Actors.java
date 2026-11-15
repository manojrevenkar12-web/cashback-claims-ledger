package cashback.world;

import cashback.common.Json;
import cashback.common.Keyed;
import cashback.world.Model.Claim;
import cashback.world.Model.Line;
import cashback.world.Model.Order;
import cashback.world.Model.Truth;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The people and models in the world. Each reads its error rates from {@code world.json} and draws its luck per claim
 * (and per attempt), so every design meets the same reader on the same claim.
 */
public final class Actors {

    private Actors() {
    }

    public record ReadLine(String description, long cents) {
    }

    public record Read(String merchantName, String orderId, String dateText, List<ReadLine> lines, String voucherCode,
                       long discountCents, long shippingCents, long totalCents) {
    }

    /** A model reading a confirmation: right most of the time, with the slips real models make on real pastes. */
    public static final class Reader {
        private final Map<String, Object> r;
        private final Keyed k;

        public Reader(World w) {
            this.r = w.section("reader");
            this.k = w.k;
        }

        /** Returns null when the model gives no usable answer. */
        public Read read(Claim c, int attempt) {
            Order o = c.order();
            String key = c.claimId();
            if (k.chance(Json.num(r, "failShare"), "readFail", key, attempt)) {
                return null;
            }
            double boost = o.hard() ? Json.num(r, "hardErrorShare") : 0;
            List<ReadLine> lines = new ArrayList<>();
            for (int i = 0; i < o.lines().size(); i++) {
                Line l = o.lines().get(i);
                long cents = l.cents();
                String desc = l.description();
                if (slip("lineAmount", boost, key, attempt, i)) {
                    cents = misdigit(cents, key, attempt, i);
                }
                if (!l.category().equals("standard") && slip("dropCategoryWord", 0, key, attempt, i)) {
                    desc = desc.length() > 3 ? desc.charAt(0) + "x" + desc.substring(2) : desc;
                }
                lines.add(new ReadLine(desc, cents));
            }
            String voucher = o.voucherCode();
            long discount = o.discountCents();
            if (voucher != null && slip("dropVoucherLine", boost, key, attempt, -1)) {
                voucher = null;
                discount = 0;
            }
            long total = o.totalCents();
            if (slip("total", boost, key, attempt, -2)) {
                total = misdigit(total, key, attempt, -2);
            }
            String orderId = o.orderId();
            if (slip("orderIdDigit", boost, key, attempt, -3)) {
                char[] ch = orderId.toCharArray();
                int pos = 3 + k.pick(5, "oidPos", key, attempt);
                ch[pos] = ch[pos] == '1' ? '7' : ch[pos] == '0' ? '8' : '1';
                orderId = new String(ch);
            }
            String date = Render.date(o.day(), o.shop().locale(), o.shop().dateOrder());
            if (o.day().getDayOfMonth() <= 12 && slip("dateSwap", boost, key, attempt, -4)) {
                LocalDate swapped = LocalDate.of(o.day().getYear(), o.day().getDayOfMonth(), o.day().getMonthValue());
                date = Render.date(swapped, o.shop().locale(), o.shop().dateOrder());
            }
            return new Read(o.shop().name(), orderId, date, lines, voucher, discount, o.shippingCents(), total);
        }

        private boolean slip(String kind, double boost, String key, int attempt, int at) {
            double p = Math.max(Json.num(r, kind), boost * Json.num(r, kind) * 10);
            return k.chance(Math.min(0.95, p), "slip-" + kind, key, attempt, at);
        }

        private long misdigit(long cents, String key, int attempt, int at) {
            long delta = new long[]{9, 90, 900, -9, -90}[k.pick(5, "digit", key, attempt, at)];
            return Math.max(1, cents + delta);
        }
    }

    public record Verdict(Truth kind, long eligibleCents) {
    }

    /** A model asked to read and decide alone: lenient where the rules are subtle, and sometimes off on the amount. */
    public static final class Judge {
        private final Map<String, Object> j;
        private final Keyed k;

        public Judge(World w) {
            this.j = w.section("judge");
            this.k = w.k;
        }

        public Verdict decide(Claim c) {
            String key = c.claimId();
            Order o = c.order();
            long wholeOrder = o.totalCents() - o.shippingCents();
            String rateKey = switch (c.truth().reason()) {
                case "eligible" -> "validWronglyRejected";
                case "no_qualifying_click" -> "noClickFiled";
                case "foreign_voucher" -> "foreignVoucherFiled";
                case "excluded_items" -> "excludedFiledWhole";
                case "too_late" -> "tooLateFiled";
                case "too_early" -> "tooEarlyFiled";
                case "already_tracked" -> "alreadyTrackedFiled";
                case "fraud" -> "fraudFiled";
                case "other_merchant" -> "wrongMerchantFiled";
                default -> throw new IllegalStateException(c.truth().reason());
            };
            boolean slips = k.chance(Json.num(j, rateKey), "judge", key);
            if (c.truth().kind() == Truth.FILE) {
                if (slips) {
                    return new Verdict(Truth.REJECT, 0);
                }
                boolean hasExcluded = o.lines().stream().anyMatch(l -> o.shop().excluded().contains(l.category()));
                if (hasExcluded && k.chance(Json.num(j, "excludedFiledWhole"), "judgeWhole", key)) {
                    return new Verdict(Truth.FILE, wholeOrder);
                }
                if (k.chance(Json.num(j, "amountWrong"), "judgeAmount", key)) {
                    return new Verdict(Truth.FILE, o.totalCents());
                }
                return new Verdict(Truth.FILE, c.truth().eligibleCents());
            }
            if (slips) {
                return new Verdict(Truth.FILE, wholeOrder);
            }
            return new Verdict(c.truth().kind() == Truth.FRAUD ? Truth.REJECT : c.truth().kind(), 0);
        }
    }

    /** A support person with the evidence in front of them. */
    public static final class Person {
        private final Map<String, Object> p;
        private final Keyed k;

        public Person(World w) {
            this.p = w.section("person");
            this.k = w.k;
        }

        /**
         * Decides a claim. {@code suggestedEligible} is an amount the desk already computed (0 if none); a person
         * approving a computed amount does not recompute it.
         */
        public Verdict decide(Claim c, long suggestedEligible, String design) {
            String key = c.claimId();
            Order o = c.order();
            boolean right = k.chance(Json.num(p, "accuracy"), "person", key, design);
            Truth truth = c.truth().kind();
            if (!right) {
                return truth == Truth.FILE ? new Verdict(Truth.REJECT, 0)
                    : new Verdict(Truth.FILE, o.totalCents() - o.shippingCents());
            }
            if (truth == Truth.FRAUD) {
                return new Verdict(Truth.REJECT, 0);
            }
            if (truth != Truth.FILE) {
                return new Verdict(truth, 0);
            }
            if (suggestedEligible > 0) {
                return new Verdict(Truth.FILE, suggestedEligible);
            }
            boolean amountRight = k.chance(Json.num(p, "amountAccuracy"), "personAmount", key, design);
            return new Verdict(Truth.FILE, amountRight ? c.truth().eligibleCents()
                : o.totalCents() - o.shippingCents());
        }
    }

    /** Whether the network pays a filed claim: valid claims at the right amount, most of the time. */
    public static boolean networkApproves(World w, Claim c, long filedEligible) {
        if (c.truth().kind() != Truth.FILE || filedEligible != c.truth().eligibleCents()) {
            return false;
        }
        return w.k.chance(Json.num(w.section("networks"), "approveValidClaim"), "networkApproves", c.claimId());
    }
}
