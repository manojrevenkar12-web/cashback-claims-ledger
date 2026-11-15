package cashback.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cashback.common.Json;
import cashback.world.Model.Order;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorldTest {

    private static Path config() {
        Path p = Path.of("").toAbsolutePath();
        while (!p.resolve("config/terms.json").toFile().exists()) {
            p = p.getParent();
        }
        return p.resolve("config");
    }

    private static World small(String seed) {
        Map<String, Object> cfg = Json.read(config().resolve("world.json"));
        cfg.put("seed", seed);
        cfg.put("shoppers", 600L);
        return new World(Json.read(config().resolve("terms.json")), cfg);
    }

    @Test
    void theSameSeedMakesTheSameWorld() {
        World a = small("s1");
        World b = small("s1");
        assertEquals(a.claims, b.claims);
        assertEquals(a.events, b.events);
        assertEquals(a.rewardTruths, b.rewardTruths);
        assertNotEquals(a.claims, small("s2").claims);
    }

    @Test
    void networksReportBeforeAnyClaimWindowOpens() {
        World w = small("s1");
        long maxHours = Json.lng(w.section("networks"), "reportHoursMax");
        for (Model.Shop s : w.shops.values()) {
            assertTrue(maxHours < s.claimAfterDays() * 24L, s.id() + " opens claims before networks report");
        }
    }

    @Test
    void trackedOrdersHaveEventsAndUntrackedHaveNone() {
        World w = small("s1");
        java.util.Set<String> reported = new java.util.HashSet<>();
        for (Model.Event e : w.events) {
            if (e.fact() instanceof Model.NetworkState n) {
                reported.add(n.orderId());
            }
        }
        for (Order o : w.orders) {
            if (!o.tracked()) {
                assertTrue(!reported.contains(o.orderId()) || w.orders.stream().filter(x -> x.orderId()
                    .equals(o.orderId())).count() > 1);
            }
        }
        assertTrue(reported.size() > w.orders.size() / 2);
    }

    @Test
    void printsMoneyAndDatesTheLocalWay() {
        assertEquals("1.234,56 €", Render.money(123456, "de"));
        assertEquals("1 234,56 €", Render.money(123456, "fr"));
        assertEquals("€1,234.56", Render.money(123456, "en"));
        assertEquals("0,05 €", Render.money(5, "es"));
        assertEquals("03.08.2026", Render.date(LocalDate.of(2026, 8, 3), "de", "dmy"));
        assertEquals("08/03/2026", Render.date(LocalDate.of(2026, 8, 3), "en", "mdy"));
    }

    @Test
    void theWorldsArithmeticRoundsDown() {
        Model.Shop shop = small("s1").shops.get("m-nordlicht");
        List<Model.Line> lines = List.of(new Model.Line("a", 17980, "standard"), new Model.Line("g", 2500,
            "gift_card"));
        assertEquals(16182, Engine.eligible(lines, 2048, shop));
        assertEquals(1294, Engine.cashback(16182, 800));
    }
}
