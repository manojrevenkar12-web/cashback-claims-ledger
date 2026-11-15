package cashback.service.web;

import cashback.product.ledger.Fact;
import cashback.product.ledger.Ledger;
import cashback.product.terms.Terms;
import cashback.service.Settings;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Signed webhooks from affiliate networks and the card scheme. A batch is checked whole before any fact is applied,
 * so a bad batch changes nothing; facts are idempotent, so a network may resend freely.
 */
@RestController
public class IntakeController {

    private final Settings settings;
    private final Ledger ledger;
    private final MeterRegistry meters;

    public IntakeController(Settings settings, Ledger ledger, MeterRegistry meters) {
        this.settings = settings;
        this.ledger = ledger;
        this.meters = meters;
    }

    @PostMapping("/api/postbacks/{network}")
    public ResponseEntity<String> postback(@PathVariable("network") String network,
                                           @RequestHeader(name = "X-Signature", required = false) String signature,
                                           @RequestBody(required = false) String body) {
        return intake(network, signature, body, true);
    }

    @PostMapping("/api/card-events")
    public ResponseEntity<String> card(@RequestHeader(name = "X-Signature", required = false) String signature,
                                       @RequestBody(required = false) String body) {
        return intake("card", signature, body, false);
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<String> intake(String source, String signature, String body, boolean network) {
        String secret = settings.secret(source).orElse(null);
        if (secret == null || body == null || !Web.signed(secret, body, signature)) {
            meters.counter("cashback.intake.rejected", "source", source, "why", "signature").increment();
            return Web.json(HttpStatus.UNAUTHORIZED, Map.of("error", "missing or wrong signature"));
        }
        Map<String, Object> m = Web.body(body);
        List<Object> raw = m.containsKey("events") ? (List<Object>) m.get("events") : List.of(m);
        List<Fact> facts = new ArrayList<>();
        for (Object o : raw) {
            Fact f = Fact.decode((Map<String, Object>) o);
            check(f, source, network);
            facts.add(f);
        }
        long applied = 0;
        long duplicates = 0;
        for (Fact f : facts) {
            if (ledger.apply(f) == Ledger.Applied.APPLIED) {
                applied++;
            } else {
                duplicates++;
            }
        }
        meters.counter("cashback.facts", "source", source, "result", "applied").increment(applied);
        meters.counter("cashback.facts", "source", source, "result", "duplicate").increment(duplicates);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("applied", applied);
        out.put("duplicates", duplicates);
        return Web.json(HttpStatus.ACCEPTED, out);
    }

    /** A network may only report on its own merchants, and the card scheme only on card offers. */
    private void check(Fact f, String source, boolean network) {
        Terms terms = settings.terms();
        if (network) {
            if (!(f instanceof Fact.NetworkUpdate n)) {
                throw new IllegalArgumentException("networks send network_update facts");
            }
            if (!terms.merchant(n.merchantId()).network().equals(source)) {
                throw new IllegalArgumentException(n.merchantId() + " is not on network " + source);
            }
            return;
        }
        String offer = switch (f) {
            case Fact.CardAuth a -> a.offerId();
            case Fact.CardClearing c -> c.offerId();
            case Fact.CardRefund r -> r.offerId();
            case Fact.NetworkUpdate n -> throw new IllegalArgumentException("the card scheme sends card facts");
        };
        terms.offer(offer);
    }
}
