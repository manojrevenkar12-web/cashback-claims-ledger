package cashback.world;

import cashback.world.Model.Line;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Prints order confirmations the way merchants in each market lay them out. */
public final class Render {

    private Render() {
    }

    private record Labels(String title, String orderNo, String date, String items, String voucher, String shipping,
                          String total) {
    }

    private static final Map<String, Labels> LABELS = Map.of(
        "de", new Labels("Bestellbestätigung", "Bestellnummer", "Bestelldatum", "Artikel", "Gutscheincode",
            "Versand", "Gesamtsumme"),
        "fr", new Labels("Confirmation de commande", "N° de commande", "Date de commande", "Articles", "Code promo",
            "Livraison", "Total TTC"),
        "es", new Labels("Confirmación de pedido", "Número de pedido", "Fecha del pedido", "Artículos",
            "Código promocional", "Envío", "Total"),
        "en", new Labels("Order confirmation", "Order number", "Order date", "Items", "Promo code", "Shipping",
            "Total"));

    public static String confirmation(String locale, String dateOrder, String shopName, String orderId, LocalDate day,
                                      List<Line> lines, List<Integer> quantities, String voucher, long discount,
                                      long shipping, long total) {
        Labels l = LABELS.get(locale);
        StringBuilder out = new StringBuilder();
        out.append(l.title()).append(" – ").append(shopName).append('\n');
        out.append(l.orderNo()).append(": ").append(orderId).append('\n');
        out.append(l.date()).append(": ").append(date(day, locale, dateOrder)).append('\n');
        out.append(l.items()).append(":\n");
        for (int i = 0; i < lines.size(); i++) {
            String left = "  " + quantities.get(i) + " x " + lines.get(i).description();
            out.append(pad(left, 40)).append(money(lines.get(i).cents(), locale)).append('\n');
        }
        if (voucher != null) {
            out.append(pad(l.voucher() + " " + voucher, 40)).append('-').append(money(discount, locale)).append('\n');
        }
        out.append(pad(l.shipping(), 40)).append(money(shipping, locale)).append('\n');
        out.append(pad(l.total(), 40)).append(money(total, locale)).append('\n');
        return out.toString();
    }

    static String pad(String s, int width) {
        return s.length() >= width - 2 ? s + "    " : s + " ".repeat(width - s.length());
    }

    public static String date(LocalDate d, String locale, String dateOrder) {
        String dd = String.format("%02d", d.getDayOfMonth());
        String mm = String.format("%02d", d.getMonthValue());
        String yyyy = String.valueOf(d.getYear());
        if (dateOrder.equals("mdy")) {
            return mm + "/" + dd + "/" + yyyy;
        }
        return locale.equals("de") ? dd + "." + mm + "." + yyyy : dd + "/" + mm + "/" + yyyy;
    }

    public static String money(long cents, String locale) {
        long euros = cents / 100;
        long rest = cents % 100;
        String grouped = group(euros, locale.equals("en") ? "," : locale.equals("fr") ? " " : ".");
        if (locale.equals("en")) {
            return "€" + grouped + "." + String.format("%02d", rest);
        }
        return grouped + "," + String.format("%02d", rest) + " €";
    }

    private static String group(long n, String sep) {
        String s = Long.toString(n);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (i > 0 && (s.length() - i) % 3 == 0) {
                out.append(sep);
            }
            out.append(s.charAt(i));
        }
        return out.toString();
    }
}
