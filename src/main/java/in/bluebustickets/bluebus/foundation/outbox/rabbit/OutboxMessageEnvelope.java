package in.bluebustickets.bluebus.foundation.outbox.rabbit;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Stable UTF-8 JSON contract published to RabbitMQ. {@code eventId} is the
 * existing outbox row id — never a second broker identity.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OutboxMessageEnvelope(
        UUID eventId,
        String eventType,
        String aggregateType,
        UUID aggregateId,
        int schemaVersion,
        String correlationId,
        String causationId,
        Instant occurredAt,
        JsonNode payload) {
}
