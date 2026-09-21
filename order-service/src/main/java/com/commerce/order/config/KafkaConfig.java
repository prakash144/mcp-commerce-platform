package com.commerce.order.config;

import com.commerce.order.event.OrderEvents;
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka wiring for the saga. The important pieces for the outbox/DLQ story:
 * 1. Producer uses the Confluent Avro serializer against the Schema Registry
 *    ({@code auto.register.schemas=false} — schemas are registered up-front by
 *    scripts/register-schemas.sh so producers can't drift the contract).
 * 2. Consumers read Avro back into the generated SpecificRecords
 *    ({@code specific.avro.reader=true}).
 * 3. The value deserializer is wrapped in an {@link ErrorHandlingDeserializer}
 *    so a poison record (undecodable Avro) is not silently skipped — Spring
 *    records it and the {@link DefaultErrorHandler} pushes it to the DLQ.
 * 4. A business/logic failure is retried 3x (1s backoff), then DLQ'd rather
 *    than blocking the partition forever.
 */
@Configuration
public class KafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConfig.class);

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${commerce.kafka.schema-registry-url}")
    private String schemaRegistryUrl;

    private Map<String, Object> kafkaProps() {
        return new HashMap<>(Map.of(
                "bootstrap.servers", bootstrapServers
        ));
    }

    @Bean
    public ProducerFactory<String, SpecificRecord> producerFactory() {
        Map<String, Object> props = new HashMap<>(kafkaProps());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, KafkaAvroSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");   // durability: wait for ISR ack
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, schemaRegistryUrl);
        props.put("auto.register.schemas", false);
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public ConsumerFactory<String, SpecificRecord> consumerFactory() {
        Map<String, Object> props = new HashMap<>(kafkaProps());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

        // Decode into the generated specific records; poison records surface as
        // a DeserializationException handled by the error handler instead of an
        // unchecked crash that wedges the consumer.
        KafkaAvroDeserializer avro = new KafkaAvroDeserializer();
        avro.configure(Map.of(
                AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, schemaRegistryUrl,
                "specific.avro.reader", true
        ), false);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                new ErrorHandlingDeserializer<>(avro));
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, SpecificRecord> kafkaTemplate(
            ProducerFactory<String, SpecificRecord> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, SpecificRecord>
    kafkaListenerContainerFactory(ConsumerFactory<String, SpecificRecord> consumerFactory,
                                  KafkaTemplate<String, SpecificRecord> kafkaTemplate) {
        ConcurrentKafkaListenerContainerFactory<String, SpecificRecord> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        factory.setCommonErrorHandler(defaultErrorHandler(kafkaTemplate));
        return factory;
    }

    /** Retry transient failures briefly, then DLQ the record instead of stalling. */
    private DefaultErrorHandler defaultErrorHandler(KafkaTemplate<String, SpecificRecord> template) {
        ConsumerRecordRecoverer recoverer = (record, ex) -> {
            // Route failed business records to <topic>.<group>.DLQ so they can be
            // replayed after a fix. Corrupt/undecodable bytes are already logged
            // loudly by the ErrorHandlingDeserializer and can't be replayed — we
            // skip re-publishing those rather than masking the poison.
            Object value = record.value();
            String key = record.key() == null ? null : record.key().toString();
            if (value instanceof SpecificRecord sr) {
                log.warn("evt=order.event.dlq topic={} key={} error={}",
                        record.topic(), key, ex.getMessage());
                template.send(OrderEvents.dlqTopic(record.topic()), key, sr);
            }
        };
        return new DefaultErrorHandler(recoverer, new FixedBackOff(1_000L, 3));
    }
}