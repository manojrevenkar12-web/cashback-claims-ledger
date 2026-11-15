package cashback.service.store;

import cashback.product.ledger.LedgerStore;
import cashback.product.ledger.Records.AccountRecord;
import cashback.product.ledger.Records.RewardRecord;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.Projection;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.ResourceInUseException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

/**
 * The ledger in one DynamoDB table: partition key the user, sort key {@code REWARD#<id>} or {@code ACCOUNT}, so a
 * balance is one query. Writes are conditional puts on the version attribute; a GSI on the order key answers "has a
 * network reported this order?" for the claims desk.
 */
public final class DynamoLedgerStore implements LedgerStore {

    private static final String REWARD = "REWARD#";
    private static final String ACCOUNT = "ACCOUNT";
    private static final String BY_ORDER = "byOrder";

    private final DynamoDbClient dynamo;
    private final String table;

    public DynamoLedgerStore(DynamoDbClient dynamo, String table) {
        this.dynamo = dynamo;
        this.table = table;
    }

    /** Creates the table and its index if they do not exist (local development and CI). */
    public void createTableIfMissing() {
        try {
            dynamo.createTable(CreateTableRequest.builder()
                .tableName(table)
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(
                    AttributeDefinition.builder().attributeName("pk").attributeType(ScalarAttributeType.S).build(),
                    AttributeDefinition.builder().attributeName("sk").attributeType(ScalarAttributeType.S).build(),
                    AttributeDefinition.builder().attributeName("orderKey").attributeType(ScalarAttributeType.S)
                        .build())
                .keySchema(
                    KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build(),
                    KeySchemaElement.builder().attributeName("sk").keyType(KeyType.RANGE).build())
                .globalSecondaryIndexes(GlobalSecondaryIndex.builder()
                    .indexName(BY_ORDER)
                    .keySchema(KeySchemaElement.builder().attributeName("orderKey").keyType(KeyType.HASH).build())
                    .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
                    .build())
                .build());
        } catch (ResourceInUseException alreadyThere) {
            // fine: someone created it first
        }
        dynamo.waiter().waitUntilTableExists(DescribeTableRequest.builder().tableName(table).build());
    }

    private static AttributeValue s(String v) {
        return AttributeValue.builder().s(v).build();
    }

    private static AttributeValue n(long v) {
        return AttributeValue.builder().n(Long.toString(v)).build();
    }

    private Map<String, AttributeValue> key(String userId, String sk) {
        return Map.of("pk", s(userId), "sk", s(sk));
    }

    private static RewardRecord reward(Map<String, AttributeValue> item) {
        return new RewardRecord(item.get("pk").s(), item.get("sk").s().substring(REWARD.length()),
            PostgresStores.facts(item.get("facts").s()), Long.parseLong(item.get("version").n()));
    }

    @Override
    public Optional<RewardRecord> reward(String userId, String rewardId) {
        Map<String, AttributeValue> item = dynamo.getItem(GetItemRequest.builder().tableName(table)
            .key(key(userId, REWARD + rewardId)).consistentRead(true).build()).item();
        return item == null || item.isEmpty() ? Optional.empty() : Optional.of(reward(item));
    }

    @Override
    public boolean putReward(RewardRecord next) {
        Map<String, AttributeValue> item = new HashMap<>(key(next.userId(), REWARD + next.rewardId()));
        item.put("facts", s(PostgresStores.json(next.facts())));
        item.put("version", n(next.version()));
        next.orderKey().ifPresent(o -> item.put("orderKey", s(o)));
        return conditionalPut(item, next.version());
    }

    private boolean conditionalPut(Map<String, AttributeValue> item, long version) {
        PutItemRequest.Builder put = PutItemRequest.builder().tableName(table).item(item);
        if (version == 1) {
            put.conditionExpression("attribute_not_exists(pk)");
        } else {
            put.conditionExpression("version = :expected")
                .expressionAttributeValues(Map.of(":expected", n(version - 1)));
        }
        try {
            dynamo.putItem(put.build());
            return true;
        } catch (ConditionalCheckFailedException lost) {
            return false;
        }
    }

    @Override
    public List<RewardRecord> rewards(String userId) {
        List<RewardRecord> out = new ArrayList<>();
        QueryRequest q = QueryRequest.builder().tableName(table).consistentRead(true)
            .keyConditionExpression("pk = :u AND begins_with(sk, :p)")
            .expressionAttributeValues(Map.of(":u", s(userId), ":p", s(REWARD))).build();
        for (Map<String, AttributeValue> item : dynamo.queryPaginator(q).items()) {
            out.add(reward(item));
        }
        return out;
    }

    @Override
    public Optional<RewardRecord> rewardForOrder(String merchantId, String orderId) {
        QueryRequest q = QueryRequest.builder().tableName(table).indexName(BY_ORDER)
            .keyConditionExpression("orderKey = :k")
            .expressionAttributeValues(Map.of(":k", s(RewardRecord.orderKey(merchantId, orderId)))).limit(1).build();
        List<Map<String, AttributeValue>> items = dynamo.query(q).items();
        if (items.isEmpty()) {
            return Optional.empty();
        }
        Map<String, AttributeValue> hit = items.get(0);
        // The index is eventually consistent; read the record itself for its current facts.
        return reward(hit.get("pk").s(), hit.get("sk").s().substring(REWARD.length()));
    }

    @Override
    public AccountRecord account(String userId) {
        Map<String, AttributeValue> item = dynamo.getItem(GetItemRequest.builder().tableName(table)
            .key(key(userId, ACCOUNT)).consistentRead(true).build()).item();
        if (item == null || item.isEmpty()) {
            return AccountRecord.empty(userId);
        }
        return new AccountRecord(userId, PostgresStores.payouts(item.get("payouts").s()),
            Long.parseLong(item.get("version").n()));
    }

    @Override
    public boolean putAccount(AccountRecord next) {
        Map<String, AttributeValue> item = new HashMap<>(key(next.userId(), ACCOUNT));
        item.put("payouts", s(PostgresStores.payoutsJson(next.payouts())));
        item.put("version", n(next.version()));
        return conditionalPut(item, next.version());
    }
}
