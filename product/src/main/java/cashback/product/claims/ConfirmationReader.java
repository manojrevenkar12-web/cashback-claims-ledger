package cashback.product.claims;

/**
 * Reads an order confirmation into fields. A model may do this; the desk never takes a reading on trust, it checks
 * the arithmetic and asks for agreement before anything depends on it.
 */
public interface ConfirmationReader {

    /** {@code attempt} starts at 1; a reader may use it to read independently on a second look. */
    Claims.Reading read(Claims.Ticket ticket, int attempt) throws ReadFailed;

    final class ReadFailed extends Exception {
        public ReadFailed(String message) {
            super(message);
        }

        public ReadFailed(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
