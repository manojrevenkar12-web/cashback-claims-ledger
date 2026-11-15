package cashback.service;

import static org.junit.jupiter.api.Assertions.assertTrue;

import cashback.product.claims.ClaimService;
import cashback.product.ledger.Ledger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** The demo seed loads: claims in several states and facts in the ledger. It holds no simulated truth. */
@SpringBootTest(properties = "cashback.demo=true")
class DemoTest {

    @Autowired
    ClaimService claims;

    @Autowired
    Ledger ledger;

    @Test
    void theSeedLoads() {
        assertTrue(claims.list(null).size() > 30);
        assertTrue(claims.list(ClaimService.Status.FILED).size() > 0);
        assertTrue(claims.list(ClaimService.Status.NEEDS_SUPPORT).size() > 0);
        assertTrue(ledger.balance("u-demo", System.currentTimeMillis()).rewards().size() > 0);
    }
}
