package cashback.service;

import cashback.common.Json;
import cashback.product.terms.Terms;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The product's settings from {@code config/}: terms, limits, API keys and webhook secrets. Secrets in the environment
 * ({@code CASHBACK_SECRET_<NETWORK>}) override the demo values in the file.
 */
@Component
public class Settings {

    public record ApiKey(String key, String actor, String role) {
    }

    private final Path configDir;
    private final Terms terms;
    private final Map<String, Object> product;
    private final Map<String, ApiKey> keys = new LinkedHashMap<>();
    private final Map<String, String> secrets = new LinkedHashMap<>();

    public Settings(@Value("${cashback.config-dir:config}") String configDir) {
        this.configDir = Path.of(configDir);
        this.terms = Terms.load(this.configDir.resolve("terms.json"));
        this.product = Json.read(this.configDir.resolve("product.json"));
        for (Map<String, Object> k : Json.objects(product, "apiKeys")) {
            ApiKey key = new ApiKey(Json.str(k, "key"), Json.str(k, "actor"), Json.str(k, "role"));
            keys.put(key.key(), key);
        }
        Map<String, Object> s = Json.obj(product, "networkSecrets");
        for (String name : s.keySet()) {
            if (name.startsWith("_")) {
                continue;
            }
            String env = System.getenv("CASHBACK_SECRET_" + name.toUpperCase(Locale.ROOT));
            secrets.put(name, env != null && !env.isBlank() ? env : Json.str(s, name));
        }
    }

    public Path configDir() {
        return configDir;
    }

    public Terms terms() {
        return terms;
    }

    public Optional<ApiKey> key(String presented) {
        return presented == null ? Optional.empty() : Optional.ofNullable(keys.get(presented));
    }

    public List<ApiKey> keys() {
        return List.copyOf(keys.values());
    }

    public Optional<String> secret(String source) {
        return Optional.ofNullable(secrets.get(source));
    }

    public int maxReads() {
        return Json.integer(Json.obj(product, "claims"), "maxReads");
    }

    public long supportAboveCashbackCents() {
        return Json.lng(Json.obj(product, "claims"), "supportAboveCashbackCents");
    }

    public long payoutThresholdCents() {
        return Json.lng(Json.obj(product, "ledger"), "payoutThresholdCents");
    }

    public int maxWriteRetries() {
        return Json.integer(Json.obj(product, "ledger"), "maxWriteRetries");
    }

    public Map<String, Object> model() {
        return Json.obj(product, "model");
    }
}
