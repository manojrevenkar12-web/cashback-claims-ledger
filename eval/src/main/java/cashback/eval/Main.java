package cashback.eval;

import cashback.common.Json;
import cashback.common.Keyed;
import cashback.product.terms.Terms;
import cashback.world.Model.Order;
import cashback.world.World;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code evaluate dev} writes {@code reports/dev.json}; {@code gate} checks it against {@code config/gate.json};
 * {@code heldout} runs the held-out world once and locks it; {@code readme} regenerates the README from the reports.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws IOException {
        Path root = Path.of(System.getProperty("cashback.root", "."));
        String command = args.length > 0 ? args[0] : "evaluate";
        switch (command) {
            case "evaluate" -> {
                Map<String, Object> report = evaluate(root, "world.json", "dev");
                Json.write(root.resolve("reports/dev.json"), report);
                System.out.println(summary(report));
            }
            case "heldout" -> heldout(root);
            case "gate" -> {
                Gate.Verdict v = Gate.check(Json.read(root.resolve("config/gate.json")),
                    Json.read(root.resolve("reports/dev.json")));
                v.lines().forEach(System.out::println);
                if (!v.passed()) {
                    System.exit(1);
                }
            }
            case "readme" -> Files.writeString(root.resolve("README.md"), Readme.render(root));
            case "seed" -> {
                Files.createDirectories(root.resolve("demo"));
                Files.writeString(root.resolve("demo/seed.json"), Json.compact(Seed.build(root)) + "\n");
            }
            default -> throw new IllegalArgumentException("unknown command " + command);
        }
    }

    public static Map<String, Object> evaluate(Path root, String worldFile, String name) {
        Path config = root.resolve("config");
        World w = World.load(config, worldFile);
        Terms terms = Terms.load(config.resolve("terms.json"));
        Map<String, Object> product = Json.read(config.resolve("product.json"));
        Map<String, Object> designs = Json.read(config.resolve("designs.json"));
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("world", worldSummary(w, name));
        report.put("claims", new ClaimsEval(w, terms, product, designs).run());
        report.put("ledger", new LedgerEval(w, terms).run());
        return report;
    }

    private static Map<String, Object> worldSummary(World w, String name) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("seed", w.k.seed());
        m.put("from", w.start.toString());
        m.put("to", w.start.plusDays(w.days - 1).toString());
        m.put("shoppers", (long) Json.integer(w.cfg, "shoppers"));
        m.put("merchants", (long) w.shops.size());
        m.put("onlineOrders", (long) w.orders.size());
        m.put("trackedOrders", w.orders.stream().filter(Order::tracked).count());
        m.put("cardTransactions", w.rewardTruths.keySet().stream().filter(x -> x.contains("|t-")).count());
        m.put("claims", (long) w.claims.size());
        m.put("claimTruths", ClaimsEval.truthMix(w));
        return m;
    }

    private static void heldout(Path root) throws IOException {
        Path lock = root.resolve("config/heldout.lock");
        if (Files.exists(lock)) {
            throw new IllegalStateException("the held-out world was already run (" + lock + "); it is run once");
        }
        Map<String, Object> report = evaluate(root, "heldout.json", "heldout");
        Json.write(root.resolve("reports/heldout.json"), report);
        Gate.Verdict v = Gate.check(Json.read(root.resolve("config/gate.json")), report);
        Map<String, Object> l = new LinkedHashMap<>();
        l.put("ranOn", LocalDate.now().toString());
        l.put("configSha256", configHash(root));
        l.put("gatePassed", v.passed());
        l.put("gateLines", v.lines());
        Json.write(lock, l);
        System.out.println(summary(report));
        v.lines().forEach(System.out::println);
    }

    static String configHash(Path root) throws IOException {
        StringBuilder all = new StringBuilder();
        for (String f : List.of("terms.json", "product.json", "designs.json", "gate.json", "heldout.json")) {
            all.append(Files.readString(root.resolve("config").resolve(f)));
        }
        return Keyed.hex(Keyed.sha256(all.toString()));
    }

    @SuppressWarnings("unchecked")
    static String summary(Map<String, Object> report) {
        StringBuilder out = new StringBuilder();
        Map<String, Object> claims = (Map<String, Object>) Json.obj(report, "claims").get("designs");
        for (Map.Entry<String, Object> e : claims.entrySet()) {
            Map<String, Object> d = (Map<String, Object>) e.getValue();
            out.append(String.format("%-12s right %.4f  filed %5d  invalid %.4f  wrongAmt %3d  support %6.1f h  late %4d%n",
                e.getKey(), d.get("rightShare"), d.get("filed"), d.get("invalidFiledShare"), d.get("wrongAmounts"),
                d.get("supportHours"), d.get("late")));
        }
        Map<String, Object> ledger = (Map<String, Object>) Json.obj(report, "ledger").get("designs");
        for (Map.Entry<String, Object> e : ledger.entrySet()) {
            Map<String, Object> d = (Map<String, Object>) e.getValue();
            out.append(String.format("%-14s users exact %.4f  cents off %8d  wrong status %5d%n", e.getKey(),
                d.get("usersExactShare"), d.get("centsOff"), d.get("wrongStatus")));
        }
        return out.toString();
    }
}
