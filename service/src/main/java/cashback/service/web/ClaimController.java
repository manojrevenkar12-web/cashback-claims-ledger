package cashback.service.web;

import cashback.common.Json;
import cashback.product.audit.Audit;
import cashback.product.claims.ClaimService;
import cashback.product.claims.ClaimService.ClaimRecord;
import cashback.product.claims.Claims;
import cashback.product.terms.Terms;
import cashback.service.Settings;
import cashback.service.store.ClickStore;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Missing-cashback claims: shoppers submit, the desk decides, support resolves the rest. */
@RestController
public class ClaimController {

    private final ClaimService claims;
    private final ClickStore clicks;
    private final Settings settings;
    private final Audit audit;
    private final MeterRegistry meters;

    public ClaimController(ClaimService claims, ClickStore clicks, Settings settings, Audit audit,
                           MeterRegistry meters) {
        this.claims = claims;
        this.clicks = clicks;
        this.settings = settings;
        this.audit = audit;
        this.meters = meters;
    }

    @PostMapping("/api/claims")
    public ResponseEntity<String> submit(@RequestBody(required = false) String body, HttpServletRequest request) {
        Web.Caller caller = Web.caller(request);
        Map<String, Object> m = Web.body(body);
        String userId = Json.str(m, "userId");
        caller.mayAct(userId);
        String merchantId = Json.str(m, "merchantId");
        settings.terms().merchant(merchantId);
        String text = Json.str(m, "confirmationText");
        if (text == null || text.isBlank() || text.length() > 20_000) {
            throw new IllegalArgumentException("paste the order confirmation (up to 20,000 characters)");
        }
        String claimId = Json.strOr(m, "claimId", "cl-" + UUID.randomUUID());
        Claims.Ticket ticket = new Claims.Ticket(claimId, userId, merchantId, System.currentTimeMillis(),
            Json.str(m, "orderId"), LocalDate.parse(Json.str(m, "orderDate")), Json.lng(m, "amountCents"), text);
        ClaimRecord c = claims.submit(ticket, caller.actor());
        meters.counter("cashback.claims", "outcome", c.decision().outcome().name()).increment();
        return Web.json(HttpStatus.CREATED, Views.claim(c, settings.terms()));
    }

    @GetMapping("/api/claims")
    public ResponseEntity<String> list(@RequestParam(name = "status", required = false) String status,
                                       HttpServletRequest request) {
        Web.caller(request).mustBeSupport();
        ClaimService.Status s = status == null || status.isBlank() ? null : ClaimService.Status.valueOf(status);
        List<Object> out = new ArrayList<>();
        Terms terms = settings.terms();
        for (ClaimRecord c : claims.list(s)) {
            out.add(Views.summary(c, terms));
        }
        Map<String, Object> counts = new LinkedHashMap<>();
        for (ClaimService.Status each : ClaimService.Status.values()) {
            counts.put(each.name(), 0L);
        }
        for (ClaimRecord c : claims.list(null)) {
            counts.merge(c.status().name(), 1L, (a, b) -> (Long) a + (Long) b);
        }
        return Web.json(HttpStatus.OK, Map.of("claims", out, "counts", counts));
    }

    @GetMapping("/api/claims/{claimId}")
    public ResponseEntity<String> get(@PathVariable("claimId") String claimId, HttpServletRequest request) {
        ClaimRecord c = claims.get(claimId).orElseThrow(() -> new NoSuchElementException("no claim " + claimId));
        Web.caller(request).mayAct(c.ticket().userId());
        Map<String, Object> out = Views.claim(c, settings.terms());
        List<Object> history = new ArrayList<>();
        for (Claims.Click k : clicks.clicks(c.ticket().userId(), c.ticket().merchantId())) {
            history.add(Map.of("clickId", k.clickId(), "atMillis", k.atMillis()));
        }
        out.put("clicks", history);
        return Web.json(HttpStatus.OK, out);
    }

    @PostMapping("/api/claims/{claimId}/resolution")
    public ResponseEntity<String> resolve(@PathVariable("claimId") String claimId,
                                          @RequestBody(required = false) String body, HttpServletRequest request) {
        Web.Caller caller = Web.caller(request);
        caller.mustBeSupport();
        Map<String, Object> m = Web.body(body);
        Claims.Outcome verdict = Claims.Outcome.valueOf(Json.str(m, "verdict"));
        String reason = Json.strOr(m, "reason", "");
        if (reason.isBlank()) {
            throw new IllegalArgumentException("a reason is needed; the shopper reads it");
        }
        Long eligible = m.get("eligibleCents") == null ? null : Json.lng(m, "eligibleCents");
        ClaimRecord c = claims.resolve(claimId, caller.actor(), verdict, reason, eligible);
        return Web.json(HttpStatus.OK, Views.claim(c, settings.terms()));
    }

    @PostMapping("/api/claims/{claimId}/retry-filing")
    public ResponseEntity<String> retry(@PathVariable("claimId") String claimId, HttpServletRequest request) {
        Web.Caller caller = Web.caller(request);
        caller.mustBeSupport();
        return Web.json(HttpStatus.OK, Views.claim(claims.retryFiling(claimId, caller.actor()), settings.terms()));
    }

    /** The platform records a click-out; the desk reads these when a claim comes in. */
    @PostMapping("/api/clicks")
    public ResponseEntity<String> click(@RequestBody(required = false) String body, HttpServletRequest request) {
        Web.caller(request).mustBeSupport();
        Map<String, Object> m = Web.body(body);
        settings.terms().merchant(Json.str(m, "merchantId"));
        clicks.add(new Claims.Click(Json.str(m, "clickId"), Json.str(m, "userId"), Json.str(m, "merchantId"),
            Json.lng(m, "atMillis")));
        return Web.json(HttpStatus.ACCEPTED, Map.of("recorded", Json.str(m, "clickId")));
    }

    @GetMapping("/api/merchants")
    public ResponseEntity<String> merchants() {
        List<Object> out = new ArrayList<>();
        for (Terms.Merchant m : settings.terms().merchantList()) {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("id", m.id());
            v.put("name", m.name());
            v.put("network", m.network());
            v.put("cashbackBp", (long) m.cashbackBp());
            v.put("cookieDays", (long) m.cookieDays());
            v.put("claimAfterDays", (long) m.claimAfterDays());
            v.put("claimWithinDays", (long) m.claimWithinDays());
            out.add(v);
        }
        return Web.json(HttpStatus.OK, Map.of("merchants", out));
    }

    @GetMapping("/api/audit/verify")
    public ResponseEntity<String> verify(HttpServletRequest request) {
        Web.caller(request).mustBeSupport();
        Audit.Verification v = audit.verify();
        return Web.json(HttpStatus.OK, Map.of("intact", v.intact(), "entries", v.entries(),
            "firstBrokenSeq", v.firstBrokenSeq()));
    }

    /** The evaluation report, for the console's results page. */
    @GetMapping("/api/results")
    public ResponseEntity<String> results() throws java.io.IOException {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String name : List.of("dev", "heldout")) {
            Path p = settings.configDir().resolveSibling("reports").resolve(name + ".json");
            out.put(name, Files.exists(p) ? Json.parse(Files.readString(p)) : null);
        }
        return Web.json(HttpStatus.OK, out);
    }
}
