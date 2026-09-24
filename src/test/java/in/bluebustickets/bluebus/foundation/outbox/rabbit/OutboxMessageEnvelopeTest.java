package in.bluebustickets.bluebus.foundation.outbox.rabbit;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboxMessageEnvelopeTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void serializesStableUtf8JsonContract() throws Exception {
        UUID eventId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        UUID aggregateId = UUID.fromString("11111111-2222-3333-4444-555555555555");
        Instant occurredAt = Instant.parse("2026-09-24T08:30:00Z");
        JsonNode payload = objectMapper.readTree("""
                {"bookingId":"11111111-2222-3333-4444-555555555555"}
                """);

        OutboxMessageEnvelope envelope = new OutboxMessageEnvelope(
                eventId,
                "BOOKING_CONFIRMED",
                "BOOKING",
                aggregateId,
                1,
                "corr-1",
                "cause-1",
                occurredAt,
                payload);

        String json = objectMapper.writeValueAsString(envelope);
        JsonNode node = objectMapper.readTree(json);

        assertThat(node.get("eventId").asText()).isEqualTo(eventId.toString());
        assertThat(node.get("eventType").asText()).isEqualTo("BOOKING_CONFIRMED");
        assertThat(node.get("aggregateType").asText()).isEqualTo("BOOKING");
        assertThat(node.get("aggregateId").asText()).isEqualTo(aggregateId.toString());
        assertThat(node.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(node.get("correlationId").asText()).isEqualTo("corr-1");
        assertThat(node.get("causationId").asText()).isEqualTo("cause-1");
        assertThat(node.get("occurredAt").asText()).isEqualTo("2026-09-24T08:30:00Z");
        assertThat(node.get("payload").get("bookingId").asText()).isEqualTo(aggregateId.toString());
        assertThat(json).doesNotContain("accessToken").doesNotContain("password");

        OutboxMessageEnvelope read = objectMapper.readValue(json, OutboxMessageEnvelope.class);
        assertThat(read.eventId()).isEqualTo(eventId);
        assertThat(BookingConfirmedRabbitConsumer.validate(read).eventType()).isEqualTo("BOOKING_CONFIRMED");
    }

    @Test
    void rejectsMissingRequiredFields() {
        assertThatThrownBy(() -> BookingConfirmedRabbitConsumer.validate(
                new OutboxMessageEnvelope(
                        null,
                        "BOOKING_CONFIRMED",
                        "BOOKING",
                        UUID.randomUUID(),
                        1,
                        null,
                        null,
                        Instant.now(),
                        null)))
                .isInstanceOf(MalformedOutboxMessageException.class)
                .hasMessageContaining("eventId");
    }

    @Test
    void rejectsBlankEventType() {
        assertThatThrownBy(() -> BookingConfirmedRabbitConsumer.validate(
                new OutboxMessageEnvelope(
                        UUID.randomUUID(),
                        "   ",
                        "BOOKING",
                        UUID.randomUUID(),
                        1,
                        null,
                        null,
                        Instant.now(),
                        null)))
                .isInstanceOf(MalformedOutboxMessageException.class)
                .hasMessageContaining("eventType");
    }
}
