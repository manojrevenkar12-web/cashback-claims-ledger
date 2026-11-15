package cashback.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cashback.common.Json;
import cashback.product.ledger.Fact;
import cashback.product.ledger.Ledger;
import cashback.product.ledger.RewardStatus;
import cashback.service.sqs.SqsFactConsumer;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

/** At-least-once delivery from SQS: a fact sent twice is applied once, and both messages are deleted. */
@EnabledIfEnvironmentVariable(named = "CASHBACK_TEST_SQS_ENDPOINT", matches = ".+")
@SpringBootTest(properties = {"cashback.demo=false", "cashback.sqs.auto-start=false",
    "cashback.sqs.queue-url=${CASHBACK_TEST_SQS_QUEUE_URL}", "cashback.sqs.endpoint=${CASHBACK_TEST_SQS_ENDPOINT}"})
class SqsTest {

    @Autowired
    SqsFactConsumer consumer;

    @Autowired
    Ledger ledger;

    @Test
    void appliesOnceAndDeletes() {
        String url = System.getenv("CASHBACK_TEST_SQS_QUEUE_URL");
        consumer.client().createQueue(CreateQueueRequest.builder().queueName(url.substring(url.lastIndexOf('/') + 1))
            .build());
        String fact = Json.compact(Fact.encode(new Fact.NetworkUpdate("sq1", "u-sqs", "rs1", "m-nordlicht", "ORD-S1",
            10000, 800, RewardStatus.CONFIRMED, 1)));
        consumer.client().sendMessage(SendMessageRequest.builder().queueUrl(url).messageBody(fact).build());
        consumer.client().sendMessage(SendMessageRequest.builder().queueUrl(url)
            .messageBody(Json.compact(Map.of("detail-type", "network_update", "detail", Json.parse(fact)))).build());
        consumer.client().sendMessage(SendMessageRequest.builder().queueUrl(url).messageBody("not json").build());
        int done = 0;
        for (int i = 0; i < 10 && done < 2; i++) {
            done += consumer.pollOnce(1);
        }
        assertEquals(2, done);
        assertEquals(800, ledger.balance("u-sqs", System.currentTimeMillis()).confirmedCents());
        String left = consumer.client().getQueueAttributes(GetQueueAttributesRequest.builder().queueUrl(url)
            .attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE).build()).attributes()
            .get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE);
        assertEquals("1", left, "only the unreadable message is left, waiting for the dead-letter queue");
    }
}
