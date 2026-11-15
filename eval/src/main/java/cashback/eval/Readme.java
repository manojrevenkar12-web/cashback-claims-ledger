package cashback.eval;

import java.nio.file.Path;

final class Readme {
    private Readme() {
    }

    static String render(Path root) {
        return "# cashback-claims-ledger\n";
    }
}
