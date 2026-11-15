package cashback.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cashback.common.Json;
import cashback.product.ledger.Fact;
import cashback.product.ledger.RewardStatus;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "cashback.demo=false")
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ApiTest {

    @Autowired
    MockMvc mvc;

    Http http;

    @BeforeEach
    void setUp() {
        http = new Http(mvc);
    }

    static String update(String event, String user, String reward, String merchant, long cashback, RewardStatus s,
                         long version) {
        return Json.compact(Fact.encode(new Fact.NetworkUpdate(event, user, reward, merchant, "ORD-" + reward,
            cashback * 10, cashback, s, version)));
    }

    @Test
    void theApiNeedsAKey() throws Exception {
        assertEquals(401, http.get("/api/claims", null).status());
        assertEquals(401, http.get("/api/claims", "wrong").status());
        assertEquals(403, http.get("/api/claims", Http.SHOPPER).status());
        assertEquals(200, http.get("/api/claims", Http.SUPPORT).status());
        assertEquals(401, http.graphql(null, "{ claims { id } }", Map.of()).status());
        assertEquals(200, http.get("/actuator/health", null).status());
    }

    @Test
    void postbacksMustBeSignedAndAreAppliedOnce() throws Exception {
        String body = update("e1", "u-demo", "r1", "m-nordlicht", 1500, RewardStatus.CONFIRMED, 1);
        Http.Reply unsigned = http.post("/api/postbacks/tidewater", null, body);
        assertEquals(401, unsigned.status());
        Http.Reply wrongSecret = http.signed("/api/postbacks/tidewater", "guess", body);
        assertEquals(401, wrongSecret.status());
        Http.Reply ok = http.signed("/api/postbacks/tidewater", "demo-tidewater-secret", body);
        assertEquals(202, ok.status());
        assertEquals(1L, ok.body().get("applied"));
        Http.Reply again = http.signed("/api/postbacks/tidewater", "demo-tidewater-secret", body);
        assertEquals(1L, again.body().get("duplicates"));
        Http.Reply account = http.get("/api/users/u-demo/account", Http.SHOPPER);
        assertEquals(1500L, account.body().get("confirmedCents"));
    }

    @Test
    void aNetworkCannotReportAnotherNetworksMerchantAndABadBatchChangesNothing() throws Exception {
        String good = update("e1", "u-demo", "r1", "m-nordlicht", 1500, RewardStatus.CONFIRMED, 1);
        String foreign = update("e2", "u-demo", "r2", "m-kaffeewerk", 900, RewardStatus.CONFIRMED, 1);
        String batch = "{\"events\":[" + good + "," + foreign + "]}";
        Http.Reply r = http.signed("/api/postbacks/tidewater", "demo-tidewater-secret", batch);
        assertEquals(400, r.status());
        assertEquals(0L, http.get("/api/users/u-demo/account", Http.SHOPPER).body().get("confirmedCents"));
    }

    @Test
    void cardEventsAndPayouts() throws Exception {
        long old = LocalDate.of(2026, 1, 5).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        List<Fact> facts = List.of(new Fact.CardAuth("a1", "u-demo", "t1", "o-sportwelt-store", 40_000, old),
            new Fact.CardClearing("c1", "u-demo", "t1", "o-sportwelt-store", 40_000, old + 3_600_000));
        StringBuilder events = new StringBuilder("{\"events\":[");
        for (int i = 0; i < facts.size(); i++) {
            events.append(i > 0 ? "," : "").append(Json.compact(Fact.encode(facts.get(i))));
        }
        String body = events.append("]}").toString();
        assertEquals(202, http.signed("/api/card-events", "demo-card-secret", body).status());
        assertEquals(2000L, http.get("/api/users/u-demo/account", Http.SHOPPER).body().get("availableCents"));
        assertEquals(403, http.get("/api/users/u-other/account", Http.SHOPPER).status());

        Http.Reply paid = http.post("/api/users/u-demo/payouts", Http.SHOPPER, Map.of("payoutId", "p1"));
        assertEquals(201, paid.status());
        assertEquals(2000L, paid.body().get("amountCents"));
        Http.Reply repeat = http.post("/api/users/u-demo/payouts", Http.SHOPPER, Map.of("payoutId", "p1"));
        assertEquals(200, repeat.status());
        assertEquals(true, repeat.body().get("repeated"));
        assertEquals(409, http.post("/api/users/u-demo/payouts", Http.SHOPPER, Map.of("payoutId", "p2")).status());
    }

    @Test
    void aClaimIsDecidedAndSupportResolvesTheRest() throws Exception {
        long click = LocalDate.of(2026, 8, 3).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        assertEquals(202, http.post("/api/clicks", Http.SUPPORT, Map.of("clickId", "k1", "userId", "u-demo",
            "merchantId", "m-nordlicht", "atMillis", click)).status());
        String text = """
            Bestellbestätigung – Nordlicht Mode
            Bestellnummer: NO-48213-77
            Bestelldatum: 03.08.2026
            Artikel:
              2 x Wollpullover                      179,80 €
              1 x Geschenkkarte 25 €                 25,00 €
            Gutscheincode SHOOP10                  -20,48 €
            Versand                                  4,95 €
            Gesamtsumme                            189,27 €
            """;
        Map<String, Object> ticket = new LinkedHashMap<>();
        ticket.put("claimId", "c-1");
        ticket.put("userId", "u-demo");
        ticket.put("merchantId", "m-nordlicht");
        ticket.put("orderId", "NO-48213-77");
        ticket.put("orderDate", "2026-08-03");
        ticket.put("amountCents", 18927L);
        ticket.put("confirmationText", text);
        Http.Reply mine = http.post("/api/claims", Http.SHOPPER, ticket);
        assertEquals(201, mine.status());
        // Filed today, months after the order: past Nordlicht's 90-day window.
        assertEquals("REJECTED", mine.body().get("status"));
        assertEquals("too_late", mine.body().get("reason"));

        ticket.put("userId", "u-other");
        ticket.put("claimId", "c-2");
        assertEquals(403, http.post("/api/claims", Http.SHOPPER, ticket).status());
        Http.Reply copy = http.post("/api/claims", Http.SUPPORT, ticket);
        assertEquals("NEEDS_SUPPORT", copy.body().get("status"));
        assertEquals("order_claimed_by_other_account", copy.body().get("deskReason"));
        assertEquals(403, http.post("/api/claims/c-2/resolution", Http.SHOPPER,
            Map.of("verdict", "REJECT", "reason", "x")).status());
        assertEquals(400, http.post("/api/claims/c-2/resolution", Http.SUPPORT, Map.of("verdict", "REJECT")).status());
        Http.Reply resolved = http.post("/api/claims/c-2/resolution", Http.SUPPORT,
            Map.of("verdict", "REJECT", "reason", "the same order was claimed from another account"));
        assertEquals("REJECTED", resolved.body().get("status"));

        @SuppressWarnings("unchecked")
        Map<String, Object> counts = (Map<String, Object>) http.get("/api/claims", Http.SUPPORT).body().get("counts");
        assertEquals(2L, counts.get("REJECTED"));
        Http.Reply audit = http.get("/api/audit/verify", Http.SUPPORT);
        assertEquals(true, audit.body().get("intact"));
        assertTrue((Long) audit.body().get("entries") >= 3);
        assertEquals(404, http.get("/api/claims/nope", Http.SUPPORT).status());
    }

    @Test
    void malformedInputIsRefusedPlainly() throws Exception {
        assertEquals(400, http.post("/api/claims", Http.SUPPORT, "{").status());
        assertEquals(400, http.post("/api/claims", Http.SUPPORT, Map.of("userId", "u")).status());
        Http.Reply bad = http.signed("/api/postbacks/tidewater", "demo-tidewater-secret",
            "{\"type\":\"network_update\",\"eventId\":\"e\"}");
        assertEquals(400, bad.status());
        assertFalse(String.valueOf(bad.body().get("error")).isBlank());
    }

    @Test
    void metricsAreExposed() throws Exception {
        String body = update("e9", "u-demo", "r9", "m-nordlicht", 100, RewardStatus.PENDING, 1);
        http.signed("/api/postbacks/tidewater", "demo-tidewater-secret", body);
        var r = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
            .get("/actuator/prometheus")).andReturn();
        assertEquals(200, r.getResponse().getStatus());
        assertTrue(r.getResponse().getContentAsString().contains("cashback_facts_total"));
    }
}
