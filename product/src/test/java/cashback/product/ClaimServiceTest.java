package cashback.product;

import static cashback.product.Fixtures.BRIGHTCART;
import static cashback.product.Fixtures.NORDLICHT;
import static cashback.product.Fixtures.TERMS;
import static cashback.product.Fixtures.at;
import static cashback.product.Fixtures.ticket;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cashback.product.audit.Audit;
import cashback.product.claims.ClaimDesk;
import cashback.product.claims.ClaimService;
import cashback.product.claims.ClaimService.ClaimRecord;
import cashback.product.claims.ClaimService.Status;
import cashback.product.claims.Claims;
import cashback.product.claims.Claims.Outcome;
import cashback.product.claims.LayoutReader;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ClaimServiceTest {

    private final AtomicInteger filings = new AtomicInteger();
    private boolean networkDown;
    private final Audit.MemoryLog log = new Audit.MemoryLog();
    private final ClaimService.MemoryClaimStore store = new ClaimService.MemoryClaimStore();

    private ClaimService service() {
        List<Claims.Click> clicks = List.of(new Claims.Click("k1", "u1", "m-nordlicht", at("2026-08-03", 8)),
            new Claims.Click("k2", "u1", "m-brightcart", at("2026-08-03", 8)),
            new Claims.Click("k3", "u2", "m-nordlicht", at("2026-08-03", 8)));
        ClaimDesk.Factory desks = ClaimDesk.factory(TERMS, (u, m) -> clicks.stream()
                .filter(c -> c.userId().equals(u) && c.merchantId().equals(m)).toList(),
            (m, o, t) -> Optional.empty(), new LayoutReader(), 3, 4000);
        return new ClaimService(store, desks, (claim, eligible) -> {
            if (networkDown) {
                throw new IllegalStateException("network unreachable");
            }
            return "net-" + filings.incrementAndGet();
        }, new Audit(log), () -> 1L);
    }

    private static Claims.Ticket nordlicht(String id, String user) {
        return ticket(id, user, "m-nordlicht", "2026-08-20", "NO-48213-77", "2026-08-03", 18927, NORDLICHT);
    }

    @Test
    void aClearClaimIsFiledOnceEvenWhenSubmittedTwice() {
        ClaimService s = service();
        ClaimRecord first = s.submit(nordlicht("c1", "u1"), "shopper:u1");
        ClaimRecord again = s.submit(nordlicht("c1", "u1"), "shopper:u1");
        assertEquals(Status.FILED, first.status());
        assertEquals(first, again);
        assertEquals(1, filings.get());
        assertEquals(1294, first.filedCashbackCents());
    }

    @Test
    void theSameOrderFromASecondAccountGoesToAPerson() {
        ClaimService s = service();
        s.submit(nordlicht("c1", "u1"), "shopper:u1");
        ClaimRecord copy = s.submit(nordlicht("c2", "u2"), "shopper:u2");
        assertEquals(Status.NEEDS_SUPPORT, copy.status());
        assertEquals("order_claimed_by_other_account", copy.decision().reason());
        ClaimRecord rejected = s.resolve("c2", "support:ana", Outcome.REJECT, "copied confirmation", null);
        assertEquals(Status.REJECTED, rejected.status());
        assertEquals(1, filings.get());
    }

    @Test
    void aPersonApprovesAHighValueClaimAtTheComputedAmountAndItIsFiledOnce() {
        ClaimService s = service();
        ClaimRecord c = s.submit(ticket("c3", "u1", "m-brightcart", "2026-08-20", "BR-11111-22", "2026-08-03", 104990,
            BRIGHTCART), "shopper:u1");
        assertEquals("high_value", c.decision().reason());
        ClaimRecord filed = s.resolve("c3", "support:ana", Outcome.FILE, "checked the receipt", null);
        ClaimRecord twice = s.resolve("c3", "support:ben", Outcome.REJECT, "late second opinion", null);
        assertEquals(Status.FILED, filed.status());
        assertEquals(4199, filed.filedCashbackCents());
        assertEquals(filed, twice, "a resolved claim is not resolved again");
        assertEquals(1, filings.get());
        assertThrows(IllegalArgumentException.class,
            () -> s.resolve("c3", "support:ana", Outcome.SUPPORT, "x", null));
    }

    @Test
    void aFilingTheNetworkMissedIsRetriedWithTheSameKey() {
        ClaimService s = service();
        networkDown = true;
        assertThrows(IllegalStateException.class, () -> s.submit(nordlicht("c1", "u1"), "shopper:u1"));
        assertEquals(Status.FILING, s.get("c1").orElseThrow().status());
        networkDown = false;
        ClaimRecord filed = s.retryFiling("c1", "ops:retry");
        assertEquals(Status.FILED, filed.status());
        assertEquals(filed, s.retryFiling("c1", "ops:retry"));
        assertEquals(1, filings.get());
    }

    @Test
    void theAuditChainShowsTamperingAndNamesTheActor() {
        ClaimService s = service();
        s.submit(nordlicht("c1", "u1"), "shopper:u1");
        s.submit(nordlicht("c2", "u2"), "shopper:u2");
        s.resolve("c2", "support:ana", Outcome.REJECT, "copied", null);
        Audit audit = new Audit(log);
        assertTrue(audit.verify().intact());
        assertEquals("support:ana", audit.entries().get(audit.entries().size() - 1).actor());
        Audit.Entry e = log.all().get(1);
        log.tamper(1, new Audit.Entry(e.seq(), e.atMillis(), e.actor(), e.action(), e.subject(), "FILE eligible",
            e.prevHash(), e.hash()));
        Audit.Verification v = audit.verify();
        assertFalse(v.intact());
        assertEquals(2, v.firstBrokenSeq());
    }

    @Test
    void claimsSurviveTheirJsonForm() {
        ClaimService s = service();
        ClaimRecord c = s.submit(nordlicht("c1", "u1"), "shopper:u1");
        assertEquals(c, ClaimRecord.fromJson(cashback.common.Json.object(cashback.common.Json.compact(c.toJson()))));
    }
}
