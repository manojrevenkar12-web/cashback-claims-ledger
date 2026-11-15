package cashback.common;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON reader and writer. Integers come back as {@code Long}, other numbers as {@code Double}, objects as
 * insertion-ordered maps. The core modules use only the standard library, so settings, facts and reports go through
 * this one class.
 */
public final class Json {

    private Json() {
    }

    public static Object parse(String text) {
        Parser p = new Parser(text);
        p.skip();
        Object value = p.value();
        p.skip();
        if (p.pos != text.length()) {
            throw p.error("trailing characters");
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(String text) {
        Object value = parse(text);
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("expected a JSON object");
        }
        return (Map<String, Object>) value;
    }

    public static Map<String, Object> read(Path file) {
        try {
            return object(Files.readString(file));
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + file, e);
        }
    }

    public static void write(Path file, Object value) {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(file, pretty(value) + "\n");
        } catch (IOException e) {
            throw new IllegalStateException("cannot write " + file, e);
        }
    }

    public static String compact(Object value) {
        StringBuilder out = new StringBuilder();
        emit(out, value, -1, 0);
        return out.toString();
    }

    public static String pretty(Object value) {
        StringBuilder out = new StringBuilder();
        emit(out, value, 2, 0);
        return out.toString();
    }

    // ---- typed access -------------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Map<String, Object> m, String key) {
        Object v = require(m, key);
        if (!(v instanceof Map)) {
            throw new IllegalArgumentException(key + " is not an object");
        }
        return (Map<String, Object>) v;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Map<String, Object> m, String key) {
        Object v = require(m, key);
        if (!(v instanceof List)) {
            throw new IllegalArgumentException(key + " is not a list");
        }
        return (List<Object>) v;
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> objects(Map<String, Object> m, String key) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : list(m, key)) {
            out.add((Map<String, Object>) o);
        }
        return out;
    }

    public static List<String> strings(Map<String, Object> m, String key) {
        List<String> out = new ArrayList<>();
        if (!m.containsKey(key) || m.get(key) == null) {
            return out;
        }
        for (Object o : list(m, key)) {
            out.add((String) o);
        }
        return out;
    }

    public static String str(Map<String, Object> m, String key) {
        Object v = require(m, key);
        return (String) v;
    }

    public static String strOr(Map<String, Object> m, String key, String fallback) {
        Object v = m.get(key);
        return v == null ? fallback : (String) v;
    }

    public static long lng(Map<String, Object> m, String key) {
        Object v = require(m, key);
        if (v instanceof Long l) {
            return l;
        }
        throw new IllegalArgumentException(key + " is not an integer");
    }

    public static long lngOr(Map<String, Object> m, String key, long fallback) {
        return m.get(key) == null ? fallback : lng(m, key);
    }

    public static int integer(Map<String, Object> m, String key) {
        return Math.toIntExact(lng(m, key));
    }

    public static double num(Map<String, Object> m, String key) {
        Object v = require(m, key);
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        throw new IllegalArgumentException(key + " is not a number");
    }

    public static boolean bool(Map<String, Object> m, String key) {
        return (Boolean) require(m, key);
    }

    private static Object require(Map<String, Object> m, String key) {
        if (!m.containsKey(key)) {
            throw new IllegalArgumentException("missing key: " + key);
        }
        return m.get(key);
    }

    // ---- writer -------------------------------------------------------------------------------------------------

    private static void emit(StringBuilder out, Object v, int indent, int depth) {
        if (v == null) {
            out.append("null");
        } else if (v instanceof String s) {
            quote(out, s);
        } else if (v instanceof Boolean || v instanceof Long || v instanceof Integer || v instanceof Short) {
            out.append(v);
        } else if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw new IllegalArgumentException("not a JSON number: " + d);
            }
            if (d == Math.rint(d) && Math.abs(d) < 1e15) {
                out.append((long) d).append(".0");
            } else {
                out.append(d);
            }
        } else if (v instanceof Map<?, ?> map) {
            if (map.isEmpty()) {
                out.append("{}");
                return;
            }
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                newline(out, indent, depth + 1);
                quote(out, String.valueOf(e.getKey()));
                out.append(indent < 0 ? ":" : ": ");
                emit(out, e.getValue(), indent, depth + 1);
            }
            newline(out, indent, depth);
            out.append('}');
        } else if (v instanceof Iterable<?> items) {
            List<Object> all = new ArrayList<>();
            items.forEach(all::add);
            if (all.isEmpty()) {
                out.append("[]");
                return;
            }
            boolean flat = all.stream().noneMatch(o -> o instanceof Map || o instanceof Iterable);
            out.append('[');
            for (int i = 0; i < all.size(); i++) {
                if (i > 0) {
                    out.append(flat && indent >= 0 ? ", " : ",");
                }
                if (!flat) {
                    newline(out, indent, depth + 1);
                }
                emit(out, all.get(i), indent, depth + 1);
            }
            if (!flat) {
                newline(out, indent, depth);
            }
            out.append(']');
        } else {
            throw new IllegalArgumentException("cannot write " + v.getClass());
        }
    }

    private static void newline(StringBuilder out, int indent, int depth) {
        if (indent >= 0) {
            out.append('\n').append(" ".repeat(indent * depth));
        }
    }

    private static void quote(StringBuilder out, String s) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    // ---- parser -------------------------------------------------------------------------------------------------

    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) {
            this.s = s;
        }

        Object value() {
            if (pos >= s.length()) {
                throw error("unexpected end");
            }
            char c = s.charAt(pos);
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            pos++;
            skip();
            if (peek() == '}') {
                pos++;
                return m;
            }
            while (true) {
                skip();
                if (peek() != '"') {
                    throw error("expected a key");
                }
                String key = string();
                skip();
                expect(':');
                skip();
                m.put(key, value());
                skip();
                char c = next();
                if (c == '}') {
                    return m;
                }
                if (c != ',') {
                    throw error("expected , or }");
                }
            }
        }

        List<Object> array() {
            List<Object> list = new ArrayList<>();
            pos++;
            skip();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                skip();
                list.add(value());
                skip();
                char c = next();
                if (c == ']') {
                    return list;
                }
                if (c != ',') {
                    throw error("expected , or ]");
                }
            }
        }

        String string() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (true) {
                char c = next();
                if (c == '"') {
                    return out.toString();
                }
                if (c == '\\') {
                    char e = next();
                    switch (e) {
                        case '"', '\\', '/' -> out.append(e);
                        case 'b' -> out.append('\b');
                        case 'f' -> out.append('\f');
                        case 'n' -> out.append('\n');
                        case 'r' -> out.append('\r');
                        case 't' -> out.append('\t');
                        case 'u' -> {
                            if (pos + 4 > s.length()) {
                                throw error("bad unicode escape");
                            }
                            out.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            pos += 4;
                        }
                        default -> throw error("bad escape");
                    }
                } else {
                    out.append(c);
                }
            }
        }

        Object number() {
            int start = pos;
            if (peek() == '-') {
                pos++;
            }
            boolean fraction = false;
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c >= '0' && c <= '9') {
                    pos++;
                } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    fraction = true;
                    pos++;
                } else {
                    break;
                }
            }
            String text = s.substring(start, pos);
            if (text.isEmpty() || text.equals("-")) {
                throw error("expected a value");
            }
            return fraction ? (Object) Double.parseDouble(text) : (Object) Long.parseLong(text);
        }

        Object literal(String word, Object value) {
            if (!s.startsWith(word, pos)) {
                throw error("expected " + word);
            }
            pos += word.length();
            return value;
        }

        void skip() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
                pos++;
            }
        }

        char peek() {
            return pos < s.length() ? s.charAt(pos) : '\0';
        }

        char next() {
            if (pos >= s.length()) {
                throw error("unexpected end");
            }
            return s.charAt(pos++);
        }

        void expect(char c) {
            if (next() != c) {
                throw error("expected " + c);
            }
        }

        IllegalArgumentException error(String what) {
            return new IllegalArgumentException("JSON " + what + " at " + pos);
        }
    }
}
