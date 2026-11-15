package cashback.eval;

import cashback.common.Json;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Checks a report against the gate that was written before any result. */
public final class Gate {

    private Gate() {
    }

    public record Verdict(boolean passed, List<String> lines) {
    }

    @SuppressWarnings("unchecked")
    public static Verdict check(Map<String, Object> gate, Map<String, Object> report) {
        List<String> lines = new ArrayList<>();
        boolean ok = true;
        Map<String, Object> g = Json.obj(gate, "claims");
        Map<String, Object> designs = (Map<String, Object>) Json.obj(report, "claims").get("designs");
        Map<String, Object> d = (Map<String, Object>) designs.get(Json.str(g, "design"));
        Map<String, Object> all = (Map<String, Object>) designs.get("support_all");
        ok &= line(lines, "right share", Json.num(d, "rightShare"), ">=", Json.num(g, "minRight"));
        ok &= line(lines, "invalid share of filed", Json.num(d, "invalidFiledShare"), "<=",
            Json.num(g, "maxInvalidFiledShare"));
        ok &= line(lines, "wrong amount share of filed", Json.num(d, "wrongAmountShareOfFiled"), "<=",
            Json.num(g, "maxWrongAmountShareOfFiled"));
        ok &= line(lines, "late answers", Json.num(d, "late"), "<=", Json.num(g, "maxLate"));
        double share = Json.num(all, "supportHours") == 0 ? 0
            : Json.num(d, "supportHours") / Json.num(all, "supportHours");
        ok &= line(lines, "support hours vs support_all", Math.round(share * 1000) / 1000.0, "<=",
            Json.num(g, "maxSupportHoursShareOfSupportAll"));
        for (String other : Json.strings(g, "mustBeat")) {
            Map<String, Object> o = (Map<String, Object>) designs.get(other);
            ok &= line(lines, "right share beats " + other, Json.num(d, "rightShare"), ">",
                Json.num(o, "rightShare"));
        }
        ok &= line(lines, "audit chain intact", Json.bool(d, "auditIntact") ? 1 : 0, ">=", 1);

        Map<String, Object> lg = Json.obj(gate, "ledger");
        Map<String, Object> ledger = Json.obj(report, "ledger");
        Map<String, Object> l = (Map<String, Object>) ((Map<String, Object>) ledger.get("designs"))
            .get(Json.str(lg, "design"));
        ok &= line(lines, "ledger users exact", Json.num(l, "usersExactShare"), ">=", Json.num(lg, "minUsersExact"));
        ok &= line(lines, "ledger cents off", Json.num(l, "centsOff"), "<=", Json.num(lg, "maxCentsOff"));
        ok &= line(lines, "ledger wrong status", Json.num(l, "wrongStatus"), "<=", Json.num(lg, "maxWrongStatus"));
        ok &= line(lines, "8 parallel consumers agree", Json.bool(ledger, "parallelIdentical") ? 1 : 0, ">=", 1);
        lines.add(ok ? "GATE PASSED" : "GATE FAILED");
        return new Verdict(ok, lines);
    }

    private static boolean line(List<String> lines, String what, double value, String op, double bound) {
        boolean pass = switch (op) {
            case ">=" -> value >= bound;
            case "<=" -> value <= bound;
            case ">" -> value > bound;
            default -> throw new IllegalArgumentException(op);
        };
        lines.add(String.format("%s %-34s %s %s %s", pass ? "pass" : "FAIL", what, fmt(value), op, fmt(bound)));
        return pass;
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }
}
