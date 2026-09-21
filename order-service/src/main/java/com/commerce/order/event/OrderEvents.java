package com.commerce.order.event;

import com.commerce.events.OrderCancelled;
import com.commerce.events.OrderCreated;
import com.commerce.events.PaymentFailed;
import com.commerce.events.PaymentRefunded;
import com.commerce.events.PaymentSucceeded;
import com.commerce.events.PaymentVoided;
import org.apache.avro.Schema;
import org.apache.avro.io.DatumReader;
import org.apache.avro.io.Decoder;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.io.Encoder;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.io.JsonDecoder;
import org.apache.avro.io.JsonEncoder;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.avro.specific.SpecificDatumWriter;
import org.apache.avro.specific.SpecificRecord;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Single home for the event <-> topic mapping and Avro (de)serialization used by
 * the outbox relay and the payment-event consumers. The generated
 * {@link SpecificRecord}s come from common/events/avro/*.avsc via the
 * avro-maven-plugin, so the contract lives in ONE place.
 */
public final class OrderEvents {

    public static final String PAYMENTS_SUCCEEDED = "payments.succeeded";
    public static final String PAYMENTS_FAILED = "payments.failed";
    public static final String PAYMENTS_VOIDED = "payments.voided";
    public static final String PAYMENTS_REFUNDED = "payments.refunded";

    public static final String GROUP_ORDER_SAGA = "order-saga";

    private static final String TOPIC_ORDERS_CREATED = "orders.created";
    private static final String TOPIC_ORDERS_CANCELLED = "orders.cancelled";

    private OrderEvents() {
    }

    public static String topicFor(String eventType) {
        return switch (eventType) {
            case "OrderCreated" -> TOPIC_ORDERS_CREATED;
            case "OrderCancelled" -> TOPIC_ORDERS_CANCELLED;
            case "PaymentSucceeded" -> PAYMENTS_SUCCEEDED;
            case "PaymentFailed" -> PAYMENTS_FAILED;
            case "PaymentVoided" -> PAYMENTS_VOIDED;
            case "PaymentRefunded" -> PAYMENTS_REFUNDED;
            default -> throw new IllegalArgumentException("unknown event type: " + eventType);
        };
    }

    /** The DLQ a failing consumer sends poison/business-error records to. */
    public static String dlqTopic(String topic) {
        return topic + "." + GROUP_ORDER_SAGA + ".DLQ";
    }

    private static Schema schemaFor(String eventType) {
        return switch (eventType) {
            case "OrderCreated" -> OrderCreated.getClassSchema();
            case "OrderCancelled" -> OrderCancelled.getClassSchema();
            case "PaymentSucceeded" -> PaymentSucceeded.getClassSchema();
            case "PaymentFailed" -> PaymentFailed.getClassSchema();
            case "PaymentVoided" -> PaymentVoided.getClassSchema();
            case "PaymentRefunded" -> PaymentRefunded.getClassSchema();
            default -> throw new IllegalArgumentException("unknown event type: " + eventType);
        };
    }

    /** Rebuild the typed generated record from the JSON stored in the outbox. */
    public static SpecificRecord fromJson(String eventType, String json) {
        Schema schema = schemaFor(eventType);
        try {
            SpecificDatumReader<SpecificRecord> reader = new SpecificDatumReader<>(schema);
            Decoder decoder = DecoderFactory.get().jsonDecoder(schema, json);
            return reader.read(null, decoder);
        } catch (Exception e) {
            throw new IllegalArgumentException("cannot decode " + eventType + ": " + json, e);
        }
    }

    /** Serialize a typed record to the JSON form the outbox stores. */
    static String toJson(SpecificRecord record) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            Encoder encoder = EncoderFactory.get().jsonEncoder(record.getSchema(), out);
            new SpecificDatumWriter<>(record.getSchema()).write(record, encoder);
            encoder.flush();
            return out.toString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("cannot encode " + record.getSchema().getFullName(), e);
        }
    }
}