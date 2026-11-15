package cashback.eval;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** The invariants in AGENTS.md, checked on the source tree. */
class InvariantsTest {

    private static List<Path> sources(String module) throws IOException {
        Path dir = EvalTest.root().resolve(module).resolve("src/main");
        if (!Files.exists(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(p -> p.toString().endsWith(".java") || p.toString().endsWith(".yml")
                || p.toString().endsWith(".graphqls")).toList();
        }
    }

    private static List<String> offenders(String module, Pattern bad) throws IOException {
        List<String> out = new ArrayList<>();
        for (Path p : sources(module)) {
            String text = Files.readString(p);
            if (bad.matcher(text).find()) {
                out.add(EvalTest.root().relativize(p).toString());
            }
        }
        return out;
    }

    @Test
    void nothingThatServesReadsTheTruth() throws IOException {
        Pattern world = Pattern.compile("import\\s+cashback\\.(world|eval)\\b");
        List<String> bad = new ArrayList<>(offenders("product", world));
        bad.addAll(offenders("service", world));
        bad.addAll(offenders("common", Pattern.compile("import\\s+cashback\\.(world|eval|product)\\b")));
        assertTrue(bad.isEmpty(), "product code imports the world: " + bad);
    }

    @Test
    void theWorldDoesNotReadTheProductsSettingsOrCode() throws IOException {
        List<String> bad = offenders("world", Pattern.compile(
            "import\\s+cashback\\.product\\b|product\\.json|designs\\.json|gate\\.json"));
        assertTrue(bad.isEmpty(), "the world reads product code or settings: " + bad);
    }

    @Test
    void moneyIsNeverAFloatingPointNumberInTheProduct() throws IOException {
        List<String> bad = offenders("product", Pattern.compile("\\b(double|float)\\s+\\w*([Cc]ents|[Aa]mount)"));
        bad.addAll(offenders("service", Pattern.compile("\\b(double|float)\\s+\\w*([Cc]ents|[Aa]mount)")));
        assertTrue(bad.isEmpty(), "money held as floating point: " + bad);
    }

    @Test
    void theModelIsNeverAskedForADecision() throws IOException {
        Path reader = EvalTest.root().resolve("product/src/main/java/cashback/product/claims/AnthropicReader.java");
        String text = Files.readString(reader);
        assertTrue(text.contains("never asked whether the claim is valid"));
        assertTrue(!Pattern.compile("(?i)\"[^\"]*(approve|eligible|decide)[^\"]*\"").matcher(
            text.substring(text.indexOf("promptFor"), text.indexOf("public Claims.Reading read"))).find(),
            "the prompt asks the model to judge the claim");
    }
}
