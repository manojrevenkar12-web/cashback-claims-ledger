package cashback.product.claims;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a printed order date into a day. "03/11/2026" is 3 November at a French shop and 11 March at a shop that
 * prints month first; the merchant's date order in the terms decides, never a guess.
 */
public final class OrderDates {

    private static final Pattern NUMBERS = Pattern.compile("(\\d{1,4})\\D+(\\d{1,2})\\D+(\\d{1,4})");

    private OrderDates() {
    }

    public static Optional<LocalDate> parse(String printed, String dateOrder) {
        if (printed == null) {
            return Optional.empty();
        }
        Matcher m = NUMBERS.matcher(printed);
        if (!m.find()) {
            return Optional.empty();
        }
        List<Integer> n = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            n.add(Integer.parseInt(m.group(i)));
        }
        try {
            if (m.group(1).length() == 4) {
                return Optional.of(LocalDate.of(n.get(0), n.get(1), n.get(2)));
            }
            if (m.group(3).length() != 4) {
                return Optional.empty();
            }
            return switch (dateOrder) {
                case "dmy" -> Optional.of(LocalDate.of(n.get(2), n.get(1), n.get(0)));
                case "mdy" -> Optional.of(LocalDate.of(n.get(2), n.get(0), n.get(1)));
                default -> throw new IllegalArgumentException("unknown date order " + dateOrder);
            };
        } catch (DateTimeException e) {
            return Optional.empty();
        }
    }
}
