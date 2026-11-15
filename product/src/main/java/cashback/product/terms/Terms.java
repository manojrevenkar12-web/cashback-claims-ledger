package cashback.product.terms;

import cashback.common.Json;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** The published programme terms in {@code config/terms.json}. */
public record Terms(Map<String, Merchant> merchants, Map<String, Offer> offers, Map<String, List<String>> categories) {

    public record Merchant(String id, String name, List<String> aliases, String locale, String dateOrder, String network,
                           int cashbackBp, int cookieDays, int claimAfterDays, int claimWithinDays,
                           List<String> excluded, List<String> platformVouchers) {

        public boolean isPlatformVoucher(String code) {
            String c = code.trim().toUpperCase(Locale.ROOT);
            return platformVouchers.stream().anyMatch(v -> v.equalsIgnoreCase(c));
        }

        public boolean knowsName(String text) {
            String n = Terms.fold(text);
            if (n.equals(Terms.fold(name))) {
                return true;
            }
            return aliases.stream().anyMatch(a -> Terms.fold(a).equals(n));
        }
    }

    public record Offer(String id, String merchantId, int cashbackBp, long minSpendCents, long maxCashbackCents,
                        int holdDays, int authExpiryDays) {
    }

    public static Terms load(Path file) {
        return from(Json.read(file));
    }

    public static Terms from(Map<String, Object> root) {
        Map<String, Merchant> merchants = new LinkedHashMap<>();
        for (Map<String, Object> m : Json.objects(root, "merchants")) {
            Merchant merchant = new Merchant(Json.str(m, "id"), Json.str(m, "name"), Json.strings(m, "aliases"),
                Json.str(m, "locale"), Json.str(m, "dateOrder"), Json.str(m, "network"), Json.integer(m, "cashbackBp"),
                Json.integer(m, "cookieDays"), Json.integer(m, "claimAfterDays"), Json.integer(m, "claimWithinDays"),
                Json.strings(m, "excluded"), Json.strings(m, "platformVouchers"));
            merchants.put(merchant.id(), merchant);
        }
        Map<String, Offer> offers = new LinkedHashMap<>();
        for (Map<String, Object> o : Json.objects(root, "offers")) {
            Offer offer = new Offer(Json.str(o, "id"), Json.str(o, "merchantId"), Json.integer(o, "cashbackBp"),
                Json.lng(o, "minSpendCents"), Json.lng(o, "maxCashbackCents"), Json.integer(o, "holdDays"),
                Json.integer(o, "authExpiryDays"));
            offers.put(offer.id(), offer);
        }
        Map<String, List<String>> categories = new LinkedHashMap<>();
        Map<String, Object> cats = Json.obj(root, "categories");
        for (String key : cats.keySet()) {
            if (!key.startsWith("_")) {
                categories.put(key, Json.strings(cats, key));
            }
        }
        return new Terms(merchants, offers, categories);
    }

    public Merchant merchant(String id) {
        Merchant m = merchants.get(id);
        if (m == null) {
            throw new IllegalArgumentException("unknown merchant " + id);
        }
        return m;
    }

    public Offer offer(String id) {
        Offer o = offers.get(id);
        if (o == null) {
            throw new IllegalArgumentException("unknown offer " + id);
        }
        return o;
    }

    public List<Merchant> merchantList() {
        return new ArrayList<>(merchants.values());
    }

    /** The category an order line falls in, decided by the words on the line, or {@code standard}. */
    public String categoryOf(String lineDescription) {
        String text = " " + fold(lineDescription).replaceAll("[^a-z0-9]+", " ") + " ";
        for (Map.Entry<String, List<String>> e : categories.entrySet()) {
            for (String word : e.getValue()) {
                String w = " " + fold(word).replaceAll("[^a-z0-9]+", " ").trim() + " ";
                if (text.contains(w)) {
                    return e.getKey();
                }
            }
        }
        return "standard";
    }

    public Optional<Merchant> merchantNamed(String text) {
        return merchants.values().stream().filter(m -> m.knowsName(text)).findFirst();
    }

    /** Lower case, accents removed, spaces collapsed. */
    public static String fold(String s) {
        String n = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ");
    }
}
