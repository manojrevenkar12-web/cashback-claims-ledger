package cashback.product;

import cashback.product.claims.Claims;
import cashback.product.terms.Terms;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;

/** Shared test data: the real published terms and a few hand-written confirmations. */
public final class Fixtures {

    public static final Terms TERMS = Terms.load(root().resolve("config/terms.json"));
    public static final long DAY = 86_400_000L;

    private Fixtures() {
    }

    public static Path root() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !p.resolve("config/terms.json").toFile().exists()) {
            p = p.getParent();
        }
        if (p == null) {
            throw new IllegalStateException("run tests inside the repository");
        }
        return p;
    }

    public static long at(String isoDay, int hour) {
        return LocalDate.parse(isoDay).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() + hour * 3_600_000L;
    }

    /** A Nordlicht order: two jumpers, a gift card, our voucher, shipping. */
    public static final String NORDLICHT = """
        Bestellbestätigung – Nordlicht Mode
        Bestellnummer: NO-48213-77
        Bestelldatum: 03.08.2026
        Artikel:
          2 x Wollpullover                      179,80 €
          1 x Geschenkkarte 25 €                 25,00 €
        Gutscheincode SHOOP10                  -20,48 €
        Versand                                  4,95 €
        Gesamtsumme                            189,27 €
        """;

    /** Brightcart prints month first: 08/03/2026 is 3 August. */
    public static final String BRIGHTCART = """
        Order confirmation – Brightcart
        Order number: BR-11111-22
        Order date: 08/03/2026
        Items:
          1 x Wireless mouse                    €1,049.90
        Shipping                                €0.00
        Total                                   €1,049.90
        """;

    public static Claims.Ticket ticket(String id, String user, String merchant, String filedDay, String orderId,
                                       String orderDay, long amount, String text) {
        return new Claims.Ticket(id, user, merchant, at(filedDay, 10), orderId, LocalDate.parse(orderDay), amount,
            text);
    }
}
