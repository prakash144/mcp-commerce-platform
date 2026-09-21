package com.commerce.order.job;

import com.commerce.order.entity.OutboxEvent;
import com.commerce.order.event.OrderEvents;
import com.commerce.order.repository.OutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.avro.specific.SpecificRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Polling publisher for the transactional outbox. Claims a batch with
 * {@code FOR UPDATE SKIP LOCKED}, publishes each event, then deletes the row in
 * the same transaction. If publishing a row fails, the row is left untouched and
 * reclaimed next poll; if the delete never commits (crash after publish), the
 * row is re-published — at-least-once, which consumers are built to dedupe.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxRelayJob {

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, SpecificRecord> kafkaTemplate;

    @Value("${commerce.order.outbox.poll-interval:5s}")
    private String pollInterval;

    @Value("${commerce.order.outbox.batch-size:10}")
    private int batchSize;

    @Scheduled(fixedDelayString = "${commerce.order.outbox.poll-interval:5s}")
    @Transactional
    public void publishPending() {
        List<OutboxEvent> batch = outboxRepository.claimBatch(batchSize);
        if (batch.isEmpty()) {
            return;
        }
        log.info("evt=outbox.poll batch={}", batch.size());
        for (OutboxEvent row : batch) {
            String topic = OrderEvents.topicFor(row.getEventType());
            try {
                SpecificRecord record = OrderEvents.fromJson(row.getEventType(), row.getPayload());
                kafkaTemplate.send(topic, row.getAggregateId().toString(), record)
                        .get(10, TimeUnit.SECONDS);
                outboxRepository.delete(row);
                log.info("evt=outbox.published topic={} orderId={} eventType={}",
                        topic, row.getAggregateId(), row.getEventType());
            } catch (Exception e) {
                log.warn("evt=outbox.publish.failed topic={} orderId={} eventType={} error={}",
                        topic, row.getAggregateId(), row.getEventType(), e.getMessage());
            }
        }
    }
}