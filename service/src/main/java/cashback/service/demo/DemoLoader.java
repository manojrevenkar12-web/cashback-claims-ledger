package cashback.service.demo;

import cashback.common.Json;
import cashback.product.claims.ClaimService;
import cashback.product.claims.Claims;
import cashback.product.ledger.Fact;
import cashback.product.ledger.Ledger;
import cashback.service.store.ClickStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Loads {@code demo/seed.json}: clicks, delivered network and card events, and claims as shoppers filed them. The
 * seed holds only what the platform would receive, never the simulated truth. Facts and claims are idempotent, so
 * loading it on every start is safe on any store.
 */
@Component
@ConditionalOnProperty(name = "cashback.demo", havingValue = "true")
public class DemoLoader implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(DemoLoader.class);

    private final Ledger ledger;
    private final ClaimService claims;
    private final ClickStore clicks;
    private final Path seed;

    public DemoLoader(Ledger ledger, ClaimService claims, ClickStore clicks,
                      @Value("${cashback.demo-seed:demo/seed.json}") String seed) {
        this.ledger = ledger;
        this.claims = claims;
        this.clicks = clicks;
        this.seed = Path.of(seed);
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!Files.exists(seed)) {
            LOG.info("no demo seed at {}; starting empty", seed);
            return;
        }
        Map<String, Object> s = Json.read(seed);
        for (Map<String, Object> c : Json.objects(s, "clicks")) {
            clicks.add(new Claims.Click(Json.str(c, "clickId"), Json.str(c, "userId"), Json.str(c, "merchantId"),
                Json.lng(c, "atMillis")));
        }
        record Step(long at, Fact fact, Claims.Ticket ticket) {
        }
        List<Step> steps = new ArrayList<>();
        for (Map<String, Object> e : Json.objects(s, "events")) {
            steps.add(new Step(Json.lng(e, "deliveredAtMillis"), Fact.decode(Json.obj(e, "fact")), null));
        }
        for (Map<String, Object> t : Json.objects(s, "tickets")) {
            Claims.Ticket ticket = Claims.Ticket.fromJson(t);
            steps.add(new Step(ticket.filedAtMillis(), null, ticket));
        }
        // Events and claims in the order they reached the platform, so each claim sees what was known when filed.
        steps.sort(Comparator.comparingLong(Step::at).thenComparing(st -> st.ticket() == null ? 0 : 1));
        int facts = 0;
        int filed = 0;
        for (Step st : steps) {
            if (st.fact() != null) {
                ledger.apply(st.fact());
                facts++;
            } else {
                claims.submit(st.ticket(), "shopper:" + st.ticket().userId());
                filed++;
            }
        }
        LOG.info("demo seed loaded: {} clicks, {} facts, {} claims", Json.list(s, "clicks").size(), facts, filed);
    }
}
