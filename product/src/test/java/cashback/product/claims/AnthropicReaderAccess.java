package cashback.product.claims;

/** Lets tests outside the package reach the model reader's prompt and reply parsing. */
public final class AnthropicReaderAccess {
    private AnthropicReaderAccess() {
    }

    public static String prompt(Claims.Ticket t) {
        return AnthropicReader.promptFor(t);
    }

    public static Claims.Reading parse(String body) throws ConfirmationReader.ReadFailed {
        return AnthropicReader.parseReply(body);
    }
}
