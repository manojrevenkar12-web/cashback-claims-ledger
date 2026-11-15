package cashback.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cashback.common.Json;
import cashback.product.ledger.Fact;
import cashback.product.ledger.Ledger;
import cashback.product.ledger.RewardStatus;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** The Apollo Federation subgraph: the SDL a router composes, entity resolution, and the same access rules. */
@SpringBootTest(properties = "cashback.demo=false")
@AutoConfigureMockMvc
class SubgraphTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    Ledger ledger;

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(Http.Reply r) {
        assertEquals(200, r.status(), String.valueOf(r.body()));
        assertNotNull(r.body().get("data"), String.valueOf(r.body()));
        return (Map<String, Object>) r.body().get("data");
    }

    @Test
    void publishesAFederationSchemaWithUserAsAnEntity() throws Exception {
        Http http = new Http(mvc);
        @SuppressWarnings("unchecked")
        Map<String, Object> service = (Map<String, Object>) data(http.graphql(Http.SUPPORT,
            "{ _service { sdl } }", Map.of())).get("_service");
        String sdl = (String) service.get("sdl");
        assertTrue(sdl.contains("@key(fields: \"id\")"), sdl);
        assertTrue(sdl.contains("specs.apollo.dev/federation"), sdl);
    }

    @Test
    @SuppressWarnings("unchecked")
    void resolvesAUserReferenceToTheirCashback() throws Exception {
        ledger.apply(new Fact.NetworkUpdate("g1", "u-demo", "rg1", "m-nordlicht", "ORD-G1", 10000, 800,
            RewardStatus.CONFIRMED, 1));
        ledger.apply(new Fact.NetworkUpdate("g2", "u-demo", "rg2", "m-nordlicht", "ORD-G2", 5000, 400,
            RewardStatus.PENDING, 1));
        Http http = new Http(mvc);
        String query = "query($r: [_Any!]!) { _entities(representations: $r) { ... on User { id "
            + "cashback { pendingCents confirmedCents availableCents rewards { id status cashbackCents } } } } }";
        Map<String, Object> vars = Map.of("r", List.of(Map.of("__typename", "User", "id", "u-demo")));
        List<Object> entities = (List<Object>) data(http.graphql(Http.SHOPPER, query, vars)).get("_entities");
        Map<String, Object> user = (Map<String, Object>) entities.get(0);
        Map<String, Object> cashback = (Map<String, Object>) user.get("cashback");
        assertEquals("u-demo", user.get("id"));
        assertEquals(800L, cashback.get("confirmedCents"));
        assertEquals(400L, cashback.get("pendingCents"));
        assertEquals(2, ((List<Object>) cashback.get("rewards")).size());

        Map<String, Object> other = Map.of("r", List.of(Map.of("__typename", "User", "id", "u-someone")));
        Http.Reply denied = http.graphql(Http.SHOPPER, query, other);
        assertTrue(Json.compact(denied.body()).contains("FORBIDDEN"), Json.compact(denied.body()));
    }

    @Test
    void supportListsClaimsByStatus() throws Exception {
        Http http = new Http(mvc);
        Map<String, Object> d = data(http.graphql(Http.SUPPORT, "{ claims(status: NEEDS_SUPPORT) { id status } }",
            Map.of()));
        assertNotNull(d.get("claims"));
        Http.Reply shopper = http.graphql(Http.SHOPPER, "{ claims { id } }", Map.of());
        assertTrue(Json.compact(shopper.body()).contains("FORBIDDEN"));
    }
}
