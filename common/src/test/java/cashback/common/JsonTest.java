package cashback.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonTest {

    @Test
    void readsWhatItWrites() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", "Café \"Nord\"\n");
        m.put("cents", 1234L);
        m.put("share", 0.25);
        m.put("ok", true);
        m.put("none", null);
        m.put("list", List.of(1L, 2L, 3L));
        m.put("nested", Map.of("a", List.of(Map.of("b", "c"))));
        assertEquals(m, Json.parse(Json.pretty(m)));
        assertEquals(m, Json.parse(Json.compact(m)));
    }

    @Test
    void integersStayIntegers() {
        Map<String, Object> m = Json.object("{\"a\": 10, \"b\": 10.5, \"c\": -3, \"d\": 1e3}");
        assertEquals(10L, m.get("a"));
        assertEquals(10.5, m.get("b"));
        assertEquals(-3L, m.get("c"));
        assertEquals(1000.0, m.get("d"));
    }

    @Test
    void unicodeEscapes() {
        assertEquals("€ é", Json.parse("\"\\u20ac \\u00e9\""));
    }

    @Test
    void rejectsBrokenInput() {
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{\"a\": }"));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("[1, 2"));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{} x"));
    }

    @Test
    void keyedDrawsAreStableAndSeparate() {
        Keyed k = new Keyed("s");
        assertEquals(k.u("p", 1, "a"), k.u("p", 1, "a"));
        assertNotEquals(k.u("p", 1, "a"), k.u("p", 1, "b"));
        assertNotEquals(k.u("p", 1), new Keyed("t").u("p", 1));
        for (int i = 0; i < 1000; i++) {
            double u = k.u("range", i);
            assertTrue(u >= 0 && u < 1);
            long b = k.between(3, 5, "b", i);
            assertTrue(b >= 3 && b <= 5);
        }
    }
}
