package cashback.service.graphql;

import cashback.product.claims.ClaimService;
import cashback.product.claims.ClaimService.ClaimRecord;
import cashback.product.claims.Claims;
import cashback.product.ledger.Ledger;
import cashback.product.ledger.RewardFold;
import cashback.service.web.Web;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.graphql.autoconfigure.GraphQlSourceBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.graphql.data.federation.EntityMapping;
import org.springframework.graphql.data.federation.FederationSchemaFactory;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.graphql.execution.DataFetcherExceptionResolver;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.graphql.server.WebGraphQlInterceptor;
import graphql.GraphqlErrorBuilder;
import org.springframework.stereotype.Controller;

/**
 * The Apollo Federation subgraph. {@code User} is an entity owned elsewhere; this subgraph resolves it by id and adds
 * cashback and claims. Who may see what follows the same API-key rules as the REST API.
 */
@Controller
public class Subgraph {

    /** Named User so the federation type resolver, which goes by class name, maps it to the entity. */
    public record User(String id) {
    }

    public record Account(int pendingCents, int confirmedCents, int paidOutCents, int availableCents, int owedCents,
                          List<RewardView> rewards) {
    }

    public record RewardView(String id, String kind, String merchantId, String status, int cashbackCents,
                             String reason) {
    }

    public record CheckView(String name, boolean passed, String detail) {
    }

    public record ClaimView(String id, String userId, String merchantId, String status, String outcome, String reason,
                            int eligibleCents, int cashbackCents, List<CheckView> checks) {
    }

    private final Ledger ledger;
    private final ClaimService claims;

    public Subgraph(Ledger ledger, ClaimService claims) {
        this.ledger = ledger;
        this.claims = claims;
    }

    @EntityMapping
    public User user(@Argument("id") String id) {
        return new User(id);
    }

    @SchemaMapping(typeName = "User", field = "cashback")
    public Account cashback(User user, graphql.GraphQLContext context) {
        caller(context).mayAct(user.id());
        Ledger.Balance b = ledger.balance(user.id(), System.currentTimeMillis());
        List<RewardView> rewards = new ArrayList<>();
        for (RewardFold.Reward r : b.rewards()) {
            rewards.add(new RewardView(r.rewardId(), r.kind().name(), r.merchantId(), r.status().name(),
                Math.toIntExact(r.cashbackCents()), r.reason()));
        }
        return new Account(Math.toIntExact(b.pendingCents()), Math.toIntExact(b.confirmedCents()),
            Math.toIntExact(b.paidOutCents()), Math.toIntExact(b.availableCents()), Math.toIntExact(b.owedCents()),
            rewards);
    }

    @SchemaMapping(typeName = "User", field = "claims")
    public List<ClaimView> userClaims(User user, graphql.GraphQLContext context) {
        caller(context).mayAct(user.id());
        return claims.list(null).stream().filter(c -> c.ticket().userId().equals(user.id())).map(Subgraph::view)
            .toList();
    }

    @QueryMapping
    public ClaimView claim(@Argument("id") String id, graphql.GraphQLContext context) {
        caller(context).mustBeSupport();
        return claims.get(id).map(Subgraph::view).orElse(null);
    }

    @QueryMapping
    public List<ClaimView> claims(@Argument("status") String status, graphql.GraphQLContext context) {
        caller(context).mustBeSupport();
        ClaimService.Status s = status == null ? null : ClaimService.Status.valueOf(status);
        return claims.list(s).stream().map(Subgraph::view).toList();
    }

    static ClaimView view(ClaimRecord c) {
        List<CheckView> checks = new ArrayList<>();
        for (Claims.Check k : c.decision().checks()) {
            checks.add(new CheckView(k.name(), k.passed(), k.detail()));
        }
        long eligible = c.resolution() != null ? c.resolution().eligibleCents() : c.decision().eligibleCents();
        long cashback = c.resolution() != null ? c.resolution().cashbackCents() : c.decision().cashbackCents();
        return new ClaimView(c.ticket().claimId(), c.ticket().userId(), c.ticket().merchantId(), c.status().name(),
            c.finalOutcome().name(), c.resolution() != null ? c.resolution().reason() : c.decision().reason(),
            Math.toIntExact(eligible), Math.toIntExact(cashback), checks);
    }

    static final String CALLER = "cashback.caller";

    static Web.Caller caller(graphql.GraphQLContext context) {
        Object c = context.get(CALLER);
        if (!(c instanceof Web.Caller caller)) {
            throw new Web.Unauthorized("an API key is needed");
        }
        return caller;
    }

    @Configuration
    public static class Wiring {

        @Bean
        FederationSchemaFactory federationSchemaFactory() {
            return new FederationSchemaFactory();
        }

        @Bean
        GraphQlSourceBuilderCustomizer federation(FederationSchemaFactory factory) {
            return builder -> builder.schemaFactory(factory::createGraphQLSchema);
        }

        /** Access errors come back as GraphQL errors the client can read, not as internal errors. */
        @Bean
        DataFetcherExceptionResolver accessErrors() {
            return DataFetcherExceptionResolver.forSingleError((ex, env) -> {
                if (ex instanceof Web.Forbidden || ex instanceof Web.Unauthorized) {
                    return GraphqlErrorBuilder.newError(env).errorType(ErrorType.FORBIDDEN).message(ex.getMessage())
                        .build();
                }
                if (ex instanceof IllegalArgumentException) {
                    return GraphqlErrorBuilder.newError(env).errorType(ErrorType.BAD_REQUEST)
                        .message(ex.getMessage()).build();
                }
                return null;
            });
        }

        /** Hands the caller the API-key filter found to the resolvers. */
        @Bean
        WebGraphQlInterceptor callerInterceptor() {
            return (request, chain) -> {
                Object caller = request.getAttributes().get(Web.Caller.class.getName());
                if (caller != null) {
                    request.configureExecutionInput((input, builder) ->
                        builder.graphQLContext(java.util.Map.of(CALLER, caller)).build());
                }
                return chain.next(request);
            };
        }
    }
}
