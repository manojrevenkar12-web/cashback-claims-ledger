package cashback.service;

import cashback.product.audit.Audit;
import cashback.product.claims.AnthropicReader;
import cashback.product.claims.ClaimDesk;
import cashback.product.claims.ClaimService;
import cashback.product.claims.ConfirmationReader;
import cashback.product.claims.LayoutReader;
import cashback.product.ledger.Ledger;
import cashback.product.ledger.LedgerStore;
import cashback.product.ledger.MemoryLedgerStore;
import cashback.product.ledger.RewardFold;
import cashback.service.store.ClickStore;
import cashback.service.store.DynamoLedgerStore;
import cashback.service.store.PostgresStores;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * Wires the product core into the service. Which stores back it is a setting: memory (the default and the demo),
 * Postgres for claims, audit and clicks when {@code DATABASE_URL} is set, and the ledger on memory, Postgres or
 * DynamoDB.
 */
@Configuration
public class CoreConfig {

    /** The Postgres stores, when a database is configured. */
    public record Database(Optional<PostgresStores> stores) {
    }

    @Bean
    Database database(@Value("${cashback.database-url:}") String url) {
        if (url == null || url.isBlank()) {
            return new Database(Optional.empty());
        }
        return new Database(Optional.of(new PostgresStores(dataSource(url))));
    }

    static HikariDataSource dataSource(String url) {
        HikariConfig c = new HikariConfig();
        if (url.startsWith("jdbc:")) {
            c.setJdbcUrl(url);
        } else {
            // postgres://user:password@host:port/db, the form most platforms hand out
            URI u = URI.create(url);
            String[] auth = u.getUserInfo() == null ? new String[0] : u.getUserInfo().split(":", 2);
            int port = u.getPort() < 0 ? 5432 : u.getPort();
            c.setJdbcUrl("jdbc:postgresql://" + u.getHost() + ":" + port + u.getPath());
            if (auth.length > 0) {
                c.setUsername(auth[0]);
            }
            if (auth.length > 1) {
                c.setPassword(auth[1]);
            }
        }
        c.setMaximumPoolSize(10);
        return new HikariDataSource(c);
    }

    @Bean
    LedgerStore ledgerStore(Database db, @Value("${cashback.store:memory}") String store,
                            @Value("${cashback.dynamodb.endpoint:}") String endpoint,
                            @Value("${cashback.dynamodb.region:eu-central-1}") String region,
                            @Value("${cashback.dynamodb.table:cashback-ledger}") String table,
                            @Value("${cashback.dynamodb.create-table:false}") boolean createTable) {
        return switch (store) {
            case "memory" -> new MemoryLedgerStore();
            case "postgres" -> db.stores().orElseThrow(() -> new IllegalStateException(
                "cashback.store=postgres needs DATABASE_URL")).ledger();
            case "dynamodb" -> {
                DynamoLedgerStore d = new DynamoLedgerStore(dynamo(endpoint, region), table);
                if (createTable) {
                    d.createTableIfMissing();
                }
                yield d;
            }
            default -> throw new IllegalStateException("unknown cashback.store " + store);
        };
    }

    static DynamoDbClient dynamo(String endpoint, String region) {
        var b = DynamoDbClient.builder().region(Region.of(region));
        if (endpoint != null && !endpoint.isBlank()) {
            b.endpointOverride(URI.create(endpoint))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local")));
        }
        return b.build();
    }

    @Bean
    RewardFold rewardFold(Settings settings) {
        return new RewardFold(settings.terms());
    }

    @Bean
    Ledger ledger(LedgerStore store, RewardFold fold, Settings settings) {
        return new Ledger(store, fold, settings.payoutThresholdCents(), settings.maxWriteRetries());
    }

    @Bean
    ClickStore clickStore(Database db) {
        return db.stores().map(PostgresStores::clicks).orElseGet(ClickStore.Memory::new);
    }

    @Bean
    Audit audit(Database db) {
        return new Audit(db.stores().map(PostgresStores::audit).orElseGet(Audit.MemoryLog::new));
    }

    @Bean
    ConfirmationReader confirmationReader(Settings settings) {
        String key = System.getenv("ANTHROPIC_API_KEY");
        if (key == null || key.isBlank()) {
            return new LayoutReader();
        }
        Map<String, Object> m = settings.model();
        return new AnthropicReader((String) m.get("apiUrl"), key, (String) m.get("name"),
            ((Long) m.get("maxTokens")).intValue());
    }

    /** Files claims with the networks. The demo records them; a real network client keys each by the claim id. */
    @Bean
    ClaimService.NetworkFiler networkFiler() {
        Map<String, String> filed = new ConcurrentHashMap<>();
        return (claim, eligibleCents) -> filed.computeIfAbsent(claim.ticket().claimId(),
            id -> "demo-" + claim.ticket().merchantId() + "-" + id);
    }

    @Bean
    ClaimService claimService(Database db, Settings settings, ClickStore clicks, Ledger ledger,
                              ConfirmationReader reader, ClaimService.NetworkFiler filer, Audit audit) {
        ClaimDesk.Factory desks = ClaimDesk.factory(settings.terms(), clicks,
            (merchant, order, asOf) -> ledger.rewardForOrder(merchant, order, asOf)
                .map(r -> r.status().name().toLowerCase()),
            reader, settings.maxReads(), settings.supportAboveCashbackCents());
        ClaimService.ClaimStore store = db.stores().map(PostgresStores::claims)
            .orElseGet(ClaimService.MemoryClaimStore::new);
        return new ClaimService(store, desks, filer, audit, System::currentTimeMillis);
    }
}
