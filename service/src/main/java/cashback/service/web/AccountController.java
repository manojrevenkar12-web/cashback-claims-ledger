package cashback.service.web;

import cashback.common.Json;
import cashback.product.audit.Audit;
import cashback.product.ledger.Ledger;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** A shopper's cashback: balance, rewards and payouts. */
@RestController
public class AccountController {

    private final Ledger ledger;
    private final Audit audit;

    public AccountController(Ledger ledger, Audit audit) {
        this.ledger = ledger;
        this.audit = audit;
    }

    @GetMapping("/api/users/{userId}/account")
    public ResponseEntity<String> account(@PathVariable("userId") String userId, HttpServletRequest request) {
        Web.caller(request).mayAct(userId);
        return Web.json(HttpStatus.OK, Views.balance(ledger.balance(userId, System.currentTimeMillis())));
    }

    @PostMapping("/api/users/{userId}/payouts")
    public ResponseEntity<String> payout(@PathVariable("userId") String userId, @RequestBody(required = false) String body,
                                         HttpServletRequest request) {
        Web.Caller caller = Web.caller(request);
        caller.mayAct(userId);
        String payoutId = Json.str(Web.body(body), "payoutId");
        if (payoutId == null || payoutId.isBlank()) {
            throw new IllegalArgumentException("payoutId is needed; it makes the request safe to retry");
        }
        long now = System.currentTimeMillis();
        Ledger.PayoutResult result = ledger.requestPayout(userId, payoutId, now);
        Map<String, Object> out = new LinkedHashMap<>();
        return switch (result) {
            case Ledger.Paid p -> {
                if (!p.repeated()) {
                    audit.record(now, caller.actor(), "payout.paid", userId,
                        p.payout().payoutId() + " " + p.payout().amountCents() + " cents");
                }
                out.put("payoutId", p.payout().payoutId());
                out.put("amountCents", p.payout().amountCents());
                out.put("repeated", p.repeated());
                yield Web.json(p.repeated() ? HttpStatus.OK : HttpStatus.CREATED, out);
            }
            case Ledger.Refused r -> {
                out.put("error", r.reason());
                out.put("availableCents", r.availableCents());
                yield Web.json(HttpStatus.CONFLICT, out);
            }
        };
    }
}
