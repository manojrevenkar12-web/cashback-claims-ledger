package cashback.service.sqs;

import cashback.common.Json;
import cashback.product.ledger.Fact;
import cashback.product.ledger.Ledger;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

/**
 * Consumes facts from SQS (fed by EventBridge rules for the network and card-scheme buses). Delivery is at least
 * once, and that is fine: a fact is deleted only after the ledger applied it, and applying it again is a no-op. A
 * message that cannot be read is left alone, so the queue's redrive policy moves it to the dead-letter queue.
 */
@Component
@ConditionalOnExpression("'${cashback.sqs.queue-url:}' != ''")
public class SqsFactConsumer implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(SqsFactConsumer.class);

    private final SqsClient sqs;
    private final String queueUrl;
    private final Ledger ledger;
    private final MeterRegistry meters;
    private final boolean autoStart;
    private volatile boolean running;
    private Thread worker;

    public SqsFactConsumer(@Value("${cashback.sqs.queue-url}") String queueUrl,
                           @Value("${cashback.sqs.endpoint:}") String endpoint,
                           @Value("${cashback.sqs.region:eu-central-1}") String region,
                           @Value("${cashback.sqs.auto-start:true}") boolean autoStart,
                           Ledger ledger, MeterRegistry meters) {
        this.autoStart = autoStart;
        var b = SqsClient.builder().region(Region.of(region));
        if (endpoint != null && !endpoint.isBlank()) {
            b.endpointOverride(URI.create(endpoint))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local")));
        }
        this.sqs = b.build();
        this.queueUrl = queueUrl;
        this.ledger = ledger;
        this.meters = meters;
    }

    /** One long poll: returns how many messages were applied and deleted. */
    public int pollOnce(int waitSeconds) {
        List<Message> messages = sqs.receiveMessage(ReceiveMessageRequest.builder().queueUrl(queueUrl)
            .maxNumberOfMessages(10).waitTimeSeconds(waitSeconds).build()).messages();
        int done = 0;
        for (Message m : messages) {
            try {
                Fact fact = decode(m.body());
                Ledger.Applied result = ledger.apply(fact);
                meters.counter("cashback.facts", "source", "sqs", "result", result.name().toLowerCase()).increment();
                sqs.deleteMessage(DeleteMessageRequest.builder().queueUrl(queueUrl).receiptHandle(m.receiptHandle())
                    .build());
                done++;
            } catch (IllegalArgumentException | ClassCastException e) {
                meters.counter("cashback.facts", "source", "sqs", "result", "unreadable").increment();
                LOG.warn("leaving unreadable message {} for the dead-letter queue: {}", m.messageId(), e.getMessage());
            }
        }
        return done;
    }

    /** Accepts a raw fact or an EventBridge envelope with the fact in {@code detail}. */
    @SuppressWarnings("unchecked")
    static Fact decode(String body) {
        Map<String, Object> m = Json.object(body);
        if (m.get("detail") instanceof Map<?, ?> detail) {
            m = (Map<String, Object>) detail;
        }
        return Fact.decode(m);
    }

    @Override
    public void start() {
        running = true;
        worker = Thread.ofVirtual().name("sqs-facts").start(() -> {
            while (running) {
                try {
                    pollOnce(10);
                } catch (RuntimeException e) {
                    LOG.warn("polling failed, retrying: {}", e.getMessage());
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        });
    }

    @Override
    public boolean isAutoStartup() {
        return autoStart;
    }

    @Override
    public void stop() {
        running = false;
        if (worker != null) {
            worker.interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    public SqsClient client() {
        return sqs;
    }
}
