package cashback.product.claims;

import cashback.product.claims.Claims.Check;
import cashback.product.claims.Claims.Click;
import cashback.product.claims.Claims.ClaimHistory;
import cashback.product.claims.Claims.ClickLog;
import cashback.product.claims.Claims.Decision;
import cashback.product.claims.Claims.Outcome;
import cashback.product.claims.Claims.ReadLine;
import cashback.product.claims.Claims.Reading;
import cashback.product.claims.Claims.Ticket;
import cashback.product.ledger.Records;
import cashback.product.terms.Cashback;
import cashback.product.terms.Terms;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Decides a missing-cashback claim. A reader turns the pasted confirmation into fields; everything after that is
 * code: the arithmetic must add up, two readings (or one reading and the shopper's own form) must agree, and the
 * terms, the click log and the ledger decide. The checks run in the order the terms publish them.
 */
public final class ClaimDesk {

    /** Whether a network already reported this order, and in what state. */
    public interface TrackedOrders {
        Optional<String> statusOf(String merchantId, String orderId, long asOfMillis);
    }

    private final Terms terms;
    private final ClickLog clicks;
    private final TrackedOrders tracked;
    private final ClaimHistory history;
    private final ConfirmationReader reader;
    private final int maxReads;
    private final long supportAboveCashbackCents;

    public ClaimDesk(Terms terms, ClickLog clicks, TrackedOrders tracked, ClaimHistory history,
                     ConfirmationReader reader, int maxReads, long supportAboveCashbackCents) {
        this.terms = terms;
        this.clicks = clicks;
        this.tracked = tracked;
        this.history = history;
        this.reader = reader;
        this.maxReads = maxReads;
        this.supportAboveCashbackCents = supportAboveCashbackCents;
    }

    /** Builds desks that share settings; the claim history comes from whichever store the service uses. */
    public interface Factory {
        ClaimDesk desk(ClaimHistory history);

        long cashback(String merchantId, long eligibleCents);
    }

    public static Factory factory(Terms terms, ClickLog clicks, TrackedOrders tracked, ConfirmationReader reader,
                                  int maxReads, long supportAboveCashbackCents) {
        return new Factory() {
            @Override
            public ClaimDesk desk(ClaimHistory history) {
                return new ClaimDesk(terms, clicks, tracked, history, reader, maxReads, supportAboveCashbackCents);
            }

            @Override
            public long cashback(String merchantId, long eligibleCents) {
                return Cashback.ofAmount(eligibleCents, terms.merchant(merchantId).cashbackBp());
            }
        };
    }

    public record Accepted(Reading reading, LocalDate orderDate, int reads, String basis) {
    }

    public Decision decide(Ticket t) {
        List<Check> checks = new ArrayList<>();
        Terms.Merchant merchant = terms.merchant(t.merchantId());
        ReadResult read = readCarefully(t, merchant);
        if (read.accepted().isEmpty()) {
            checks.add(new Check("reading", false, "no reading added up and agreed after " + read.reads() + " reads"));
            return new Decision(Outcome.SUPPORT, "unreadable", null, null, 0, 0, checks, read.reads());
        }
        Accepted a = read.accepted().get();
        Reading r = a.reading();
        int reads = a.reads();
        checks.add(new Check("reading", true, "figures add up; " + a.basis()));

        if (!merchant.knowsName(r.merchantName())) {
            checks.add(new Check("merchant", false, "confirmation is from \"" + r.merchantName() + "\""));
            return new Decision(Outcome.SUPPORT, "other_merchant", r.orderId(), r, 0, 0, checks, reads);
        }
        checks.add(new Check("merchant", true, merchant.name()));

        List<String> others = history.otherClaimants(merchant.id(), r.orderId(), t.userId(), t.claimId());
        if (!others.isEmpty()) {
            checks.add(new Check("one claimant", false, "order also claimed by " + others.size() + " other account(s)"));
            return new Decision(Outcome.SUPPORT, "order_claimed_by_other_account", r.orderId(), r, 0, 0, checks, reads);
        }
        checks.add(new Check("one claimant", true, "no other account claimed order " + r.orderId()));

        Optional<String> status = tracked.statusOf(merchant.id(), r.orderId(), t.filedAtMillis());
        if (status.isPresent()) {
            checks.add(new Check("not tracked yet", false, "network already reported it: " + status.get()));
            return new Decision(Outcome.ALREADY_TRACKED, "already_tracked", r.orderId(), r, 0, 0, checks, reads);
        }
        checks.add(new Check("not tracked yet", true, "no network report for this order"));

        LocalDate filed = Instant.ofEpochMilli(t.filedAtMillis()).atZone(ZoneOffset.UTC).toLocalDate();
        long age = ChronoUnit.DAYS.between(a.orderDate(), filed);
        if (age < 0) {
            checks.add(new Check("claim window", false, "order date " + a.orderDate() + " is after the claim"));
            return new Decision(Outcome.SUPPORT, "order_after_claim", r.orderId(), r, 0, 0, checks, reads);
        }
        if (age < merchant.claimAfterDays()) {
            checks.add(new Check("claim window", false, "networks report within " + merchant.claimAfterDays()
                + " days; order is " + age + " days old"));
            return new Decision(Outcome.WAIT, "too_early", r.orderId(), r, 0, 0, checks, reads);
        }
        if (age > merchant.claimWithinDays()) {
            checks.add(new Check("claim window", false, "claims close after " + merchant.claimWithinDays()
                + " days; order is " + age + " days old"));
            return new Decision(Outcome.REJECT, "too_late", r.orderId(), r, 0, 0, checks, reads);
        }
        checks.add(new Check("claim window", true, "order is " + age + " days old"));

        Optional<Click> click = qualifyingClick(t.userId(), merchant, a.orderDate());
        if (click.isEmpty()) {
            checks.add(new Check("tracked click", false, "no click to " + merchant.name() + " in the "
                + merchant.cookieDays() + " days up to " + a.orderDate()));
            return new Decision(Outcome.REJECT, "no_qualifying_click", r.orderId(), r, 0, 0, checks, reads);
        }
        checks.add(new Check("tracked click", true, "click " + click.get().clickId() + " on "
            + day(click.get().atMillis())));

        if (r.voucherCode() != null && !r.voucherCode().isBlank() && !merchant.isPlatformVoucher(r.voucherCode())) {
            checks.add(new Check("voucher", false, "code " + r.voucherCode() + " is not one we published"));
            return new Decision(Outcome.REJECT, "foreign_voucher", r.orderId(), r, 0, 0, checks, reads);
        }
        checks.add(new Check("voucher", true, r.voucherCode() == null || r.voucherCode().isBlank()
            ? "no code used" : "our code " + r.voucherCode()));

        List<Cashback.Line> lines = new ArrayList<>();
        List<String> excluded = new ArrayList<>();
        for (ReadLine l : r.lines()) {
            String category = terms.categoryOf(l.description());
            boolean eligible = !merchant.excluded().contains(category);
            if (!eligible) {
                excluded.add(l.description() + " (" + category + ")");
            }
            lines.add(new Cashback.Line(l.description(), l.amountCents(), eligible));
        }
        long eligibleCents = Cashback.eligibleValue(lines, r.discountCents());
        if (eligibleCents == 0) {
            checks.add(new Check("eligible items", false, "nothing eligible: " + String.join(", ", excluded)));
            return new Decision(Outcome.REJECT, "excluded_items", r.orderId(), r, 0, 0, checks, reads);
        }
        long cashback = Cashback.ofAmount(eligibleCents, merchant.cashbackBp());
        checks.add(new Check("eligible items", true, excluded.isEmpty() ? "all items eligible"
            : "left out: " + String.join(", ", excluded)));

        if (cashback >= supportAboveCashbackCents) {
            checks.add(new Check("amount", false, "cashback of " + cashback + " cents needs a person's approval"));
            return new Decision(Outcome.SUPPORT, "high_value", r.orderId(), r, eligibleCents, cashback, checks, reads);
        }
        checks.add(new Check("amount", true, cashback + " cents cashback on " + eligibleCents + " cents"));
        return new Decision(Outcome.FILE, "eligible", r.orderId(), r, eligibleCents, cashback, checks, reads);
    }

    private Optional<Click> qualifyingClick(String userId, Terms.Merchant merchant, LocalDate orderDate) {
        return clicks.clicks(userId, merchant.id()).stream().filter(c -> {
            long before = ChronoUnit.DAYS.between(day(c.atMillis()), orderDate);
            return before >= 0 && before <= merchant.cookieDays();
        }).max((x, y) -> Long.compare(x.atMillis(), y.atMillis()));
    }

    private static LocalDate day(long millis) {
        return Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate();
    }

    private record ReadResult(Optional<Accepted> accepted, int reads) {
    }

    /**
     * A reading is accepted when its figures add up and either it agrees with what the shopper typed (order id,
     * date and total) or a second reading that adds up agrees with it on everything the decision uses.
     */
    private ReadResult readCarefully(Ticket t, Terms.Merchant merchant) {
        List<Reading> seen = new ArrayList<>();
        int reads = 0;
        for (int attempt = 1; attempt <= maxReads; attempt++) {
            Reading r;
            reads = attempt;
            try {
                r = reader.read(t, attempt);
            } catch (ConfirmationReader.ReadFailed e) {
                continue;
            }
            Optional<LocalDate> date = OrderDates.parse(r.orderDateText(), merchant.dateOrder());
            if (!r.addsUp() || date.isEmpty() || Records.normaliseOrderId(r.orderId()).isEmpty()) {
                continue;
            }
            if (Records.normaliseOrderId(r.orderId()).equals(Records.normaliseOrderId(t.formOrderId()))
                && date.get().equals(t.formOrderDate()) && r.totalCents() == t.formAmountCents()) {
                return new ReadResult(Optional.of(new Accepted(r, date.get(), attempt,
                    "matches the order number, date and total the shopper typed")), reads);
            }
            for (Reading earlier : seen) {
                if (same(earlier, r)) {
                    return new ReadResult(Optional.of(new Accepted(r, date.get(), attempt,
                        "two independent readings agree")), reads);
                }
            }
            seen.add(r);
        }
        return new ReadResult(Optional.empty(), reads);
    }

    static boolean same(Reading a, Reading b) {
        if (!Records.normaliseOrderId(a.orderId()).equals(Records.normaliseOrderId(b.orderId()))
            || !Objects.equals(a.orderDateText().trim(), b.orderDateText().trim())
            || a.totalCents() != b.totalCents() || a.discountCents() != b.discountCents()
            || a.shippingCents() != b.shippingCents()
            || !Objects.equals(code(a.voucherCode()), code(b.voucherCode()))
            || !Terms.fold(a.merchantName()).equals(Terms.fold(b.merchantName()))
            || a.lines().size() != b.lines().size()) {
            return false;
        }
        for (int i = 0; i < a.lines().size(); i++) {
            ReadLine x = a.lines().get(i);
            ReadLine y = b.lines().get(i);
            if (x.amountCents() != y.amountCents() || !Terms.fold(x.description()).equals(Terms.fold(y.description()))) {
                return false;
            }
        }
        return true;
    }

    private static String code(String c) {
        return c == null || c.isBlank() ? "" : c.trim().toUpperCase(Locale.ROOT);
    }
}
