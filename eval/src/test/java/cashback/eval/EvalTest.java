package cashback.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cashback.common.Json;
import cashback.product.claims.Claims;
import cashback.product.claims.LayoutReader;
import cashback.product.terms.Cashback;
import cashback.product.terms.Terms;
import cashback.world.Engine;
import cashback.world.Model.Line;
import cashback.world.Model.Order;
import cashback.world.World;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class EvalTest {

    static Path root() {
        Path p = Path.of("").toAbsolutePath();
        while (!p.resolve("config/terms.json").toFile().exists()) {
            p = p.getParent();
        }
        return p;
    }

    private static World world() {
        Map<String, Object> cfg = Json.read(root().resolve("config/world.json"));
        cfg.put("shoppers", 1500L);
        return new World(Json.read(root().resolve("config/terms.json")), cfg);
    }

    @Test
    void theTwoCashbackEnginesAgree() {
        World w = world();
        Terms terms = Terms.load(root().resolve("config/terms.json"));
        for (Order o : w.orders) {
            Terms.Merchant m = terms.merchant(o.shop().id());
            List<Cashback.Line> lines = new ArrayList<>();
            for (Line l : o.lines()) {
                lines.add(new Cashback.Line(l.description(), l.cents(), !m.excluded().contains(
                    terms.categoryOf(l.description()))));
            }
            long product = Cashback.eligibleValue(lines, o.discountCents());
            assertEquals(Engine.eligible(o.lines(), o.discountCents(), o.shop()), product, o.orderId());
            assertEquals(Engine.cashback(product, m.cashbackBp()), Cashback.ofAmount(product, m.cashbackBp()));
        }
        Random rnd = new Random(1);
        for (int i = 0; i < 20000; i++) {
            long a = 1 + rnd.nextInt(50000);
            long b = 1 + rnd.nextInt(50000);
            long d = rnd.nextInt((int) (a + b));
            List<Line> wl = List.of(new Line("x", a, "standard"), new Line("y", b, "gift_card"));
            List<Cashback.Line> pl = List.of(new Cashback.Line("x", a, true), new Cashback.Line("y", b, false));
            assertEquals(Engine.eligible(wl, d, w.shops.get("m-nordlicht")), Cashback.eligibleValue(pl, d));
        }
    }

    @Test
    void theLayoutReaderReadsEveryConfirmationInTheWorld() throws Exception {
        World w = world();
        Terms terms = Terms.load(root().resolve("config/terms.json"));
        for (Order o : w.orders) {
            Claims.Reading r = LayoutReader.parse(o.confirmation());
            assertEquals(o.orderId(), r.orderId());
            assertEquals(o.totalCents(), r.totalCents(), o.confirmation());
            assertEquals(o.discountCents(), r.discountCents());
            assertEquals(o.shippingCents(), r.shippingCents());
            assertEquals(o.lines().size(), r.lines().size());
            assertTrue(r.addsUp());
            assertTrue(terms.merchant(o.shop().id()).knowsName(r.merchantName()));
            for (int i = 0; i < o.lines().size(); i++) {
                assertEquals(o.lines().get(i).cents(), r.lines().get(i).amountCents());
                assertEquals(o.lines().get(i).category(), terms.categoryOf(r.lines().get(i).description()),
                    r.lines().get(i).description());
            }
        }
    }

    @Test
    void theQueueAnswersInOrderWithinItsMinutes() {
        List<long[]> cases = new ArrayList<>();
        long day = 86_400_000L;
        for (int i = 0; i < 10; i++) {
            cases.add(new long[]{0, 60});
        }
        Stats.Queue q = Stats.queue(cases, 120, 3);
        assertEquals(4, q.maxDays(), "ten hours of work at two hours a day takes five days");
        assertEquals(2, q.late());
        assertEquals(0, Stats.queue(List.of(new long[]{5 * day, 10}), 120, 3).maxDays());
    }

    @Test
    void theGateReadsTheReport() {
        Map<String, Object> gate = Json.read(root().resolve("config/gate.json"));
        Map<String, Object> report = Json.read(root().resolve("reports/dev.json"));
        Gate.Verdict v = Gate.check(gate, report);
        assertTrue(v.lines().size() > 10);
        @SuppressWarnings("unchecked")
        Map<String, Object> desk = (Map<String, Object>) ((Map<String, Object>) Json.obj(report, "claims")
            .get("designs")).get("desk");
        desk.put("rightShare", 0.5);
        assertFalse(Gate.check(gate, report).passed());
    }

    @Test
    void theBootstrapIsPairedAndRepeatable() {
        boolean[] a = new boolean[500];
        boolean[] b = new boolean[500];
        for (int i = 0; i < 500; i++) {
            a[i] = i % 10 != 0;
            b[i] = i % 5 != 0;
        }
        Map<String, Object> m = Stats.pairedBootstrap(new cashback.common.Keyed("t"), "p", a, b);
        assertEquals(0.1, (Double) m.get("difference"), 1e-9);
        assertTrue((Double) m.get("lo90") <= 0.1 && (Double) m.get("hi90") >= 0.1);
        assertEquals(m, Stats.pairedBootstrap(new cashback.common.Keyed("t"), "p", a, b));
    }
}
