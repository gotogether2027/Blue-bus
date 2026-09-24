package in.bluebustickets.bluebus.foundation.outbox.rabbit;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

@Entity
@Table(name = "processed_events")
@IdClass(ProcessedEvent.Id.class)
public class ProcessedEvent {

    @jakarta.persistence.Id
    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @jakarta.persistence.Id
    @Column(name = "consumer_name", nullable = false, length = 100, updatable = false)
    private String consumerName;

    @Column(name = "processed_at", nullable = false, updatable = false)
    private Instant processedAt;

    protected ProcessedEvent() {
    }

    public ProcessedEvent(UUID eventId, String consumerName, Instant processedAt) {
        this.eventId = eventId;
        this.consumerName = consumerName;
        this.processedAt = processedAt;
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getConsumerName() {
        return consumerName;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }

    public static final class Id implements Serializable {

        private UUID eventId;
        private String consumerName;

        public Id() {
        }

        public Id(UUID eventId, String consumerName) {
            this.eventId = eventId;
            this.consumerName = consumerName;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Id id)) {
                return false;
            }
            return Objects.equals(eventId, id.eventId)
                    && Objects.equals(consumerName, id.consumerName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(eventId, consumerName);
        }
    }
}
