package cashback.product.claims;

import cashback.product.claims.Claims.ReadLine;
import cashback.product.claims.Claims.Reading;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A reader without a model: it knows the layout merchants use for order confirmations in German, French, Spanish and
 * English. It serves the demo when no model key is set and is the reference the model reader is tested against.
 */
public final class LayoutReader implements ConfirmationReader {

    private static final String[] ORDER_NO = {"bestellnummer", "n° de commande", "numéro de commande",
        "número de pedido", "order number", "order no"};
    private static final String[] DATE = {"bestelldatum", "date de commande", "fecha del pedido", "order date"};
    private static final String[] VOUCHER = {"gutscheincode", "code promo", "código promocional", "promo code"};
    private static final String[] SHIPPING = {"versand", "livraison", "envío", "envio", "shipping"};
    private static final String[] TOTAL = {"gesamtsumme", "total ttc", "total"};
    private static final Pattern ITEM = Pattern.compile("^(\\d+)\\s*[x×]\\s+(.+?)\\s{2,}(-?\\S.*)$");
    private static final Pattern TITLE = Pattern.compile("^.+?\\s[–-]\\s(.+)$");

    @Override
    public Reading read(Claims.Ticket ticket, int attempt) throws ReadFailed {
        return parse(ticket.confirmationText());
    }

    public static Reading parse(String text) throws ReadFailed {
        String merchant = "";
        String orderId = "";
        String date = "";
        String voucher = null;
        long discount = 0;
        long shipping = 0;
        Long total = null;
        List<ReadLine> lines = new ArrayList<>();
        for (String raw : text.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty()) {
                continue;
            }
            String lower = line.toLowerCase(Locale.ROOT);
            Matcher item = ITEM.matcher(line);
            if (item.matches()) {
                lines.add(new ReadLine(item.group(2).strip(), cents(item.group(3))));
                continue;
            }
            String label;
            if ((label = startsWith(lower, ORDER_NO)) != null) {
                orderId = afterColon(line, label);
            } else if ((label = startsWith(lower, DATE)) != null) {
                date = afterColon(line, label);
            } else if ((label = startsWith(lower, VOUCHER)) != null) {
                String rest = line.substring(label.length()).strip();
                String[] parts = rest.split("\\s+", 2);
                if (parts.length < 2) {
                    throw new ReadFailed("voucher line without an amount");
                }
                voucher = parts[0].replace(":", "").strip();
                discount = Math.abs(cents(parts[1]));
            } else if ((label = startsWith(lower, SHIPPING)) != null) {
                shipping = cents(line.substring(label.length()).replace(":", ""));
            } else if ((label = startsWith(lower, TOTAL)) != null) {
                total = cents(line.substring(label.length()).replace(":", ""));
            } else if (merchant.isEmpty()) {
                Matcher title = TITLE.matcher(line);
                if (title.matches()) {
                    merchant = title.group(1).strip();
                }
            }
        }
        if (total == null) {
            throw new ReadFailed("no total found");
        }
        return new Reading(merchant, orderId, date, lines, voucher, discount, shipping, total);
    }

    private static String startsWith(String lower, String[] labels) {
        for (String l : labels) {
            if (lower.startsWith(l)) {
                return l;
            }
        }
        return null;
    }

    private static String afterColon(String line, String label) {
        String rest = line.substring(label.length());
        int colon = rest.indexOf(':');
        return (colon >= 0 ? rest.substring(colon + 1) : rest).strip();
    }

    /** "1.234,56 €", "1 234,56 €" and "€1,234.56" are all 123456 cents. */
    static long cents(String printed) throws ReadFailed {
        String s = printed.replace("€", "").replace("EUR", "").replace(" ", "").replace(" ", "")
            .replace(" ", "").replace("-", "").strip();
        if (s.isEmpty()) {
            throw new ReadFailed("no amount in \"" + printed + "\"");
        }
        int comma = s.lastIndexOf(',');
        int dot = s.lastIndexOf('.');
        int decimal = Math.max(comma, dot);
        String whole;
        String fraction;
        if (decimal >= 0 && s.length() - decimal - 1 == 2) {
            whole = s.substring(0, decimal).replace(".", "").replace(",", "");
            fraction = s.substring(decimal + 1);
        } else {
            whole = s.replace(".", "").replace(",", "");
            fraction = "00";
        }
        if (!whole.matches("\\d+") || !fraction.matches("\\d{2}")) {
            throw new ReadFailed("not an amount: \"" + printed + "\"");
        }
        return Long.parseLong(whole) * 100 + Long.parseLong(fraction);
    }
}
