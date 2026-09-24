package in.bluebustickets.bluebus.foundation.outbox;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    @Id
    private UUID id;

    @Column(name = "event_type", nullable = false, length = 100, updatable = false)
    private String eventType;

    @Column(name = "aggregate_type", nullable = false, length = 100, updatable = false)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, updatable = false)
    private UUID aggregateId;

    @Column(name = "schema_version", nullable = false, updatable = false)
    private int schemaVersion;

    @Column(name = "correlation_id", length = 100, updatable = false)
    private String correlationId;

    @Column(name = "causation_id", length = 100, updatable = false)
    private String causationId;

    @Column(name = "payload_json", nullable = false, columnDefinition = "text", updatable = false)
    private String payloadJson;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "rabbit_published_at")
    private Instant rabbitPublishedAt;

    @Column(name = "rabbit_attempt_count", nullable = false)
    private int rabbitAttemptCount;

    @Column(name = "rabbit_next_retry_at")
    private Instant rabbitNextRetryAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected OutboxEvent() {
    }

    public OutboxEvent(
            String eventType,
            String aggregateType,
            UUID aggregateId,
            String payloadJson,
            Instant occurredAt,
            String correlationId,
            String causationId) {
        this.id = UUID.randomUUID();
        this.eventType = eventType;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.schemaVersion = 1;
        this.payloadJson = payloadJson;
        this.occurredAt = occurredAt;
        this.createdAt = occurredAt;
        this.correlationId = correlationId;
        this.causationId = causationId;
        this.attemptCount = 0;
        this.rabbitAttemptCount = 0;
    }

    public void markPublished(Instant at) {
        if (at == null) {
            throw new IllegalArgumentException("publishedAt is required");
        }
        if (this.publishedAt == null) {
            this.publishedAt = at;
        }
    }

    public void recordAttempt() {
        this.attemptCount++;
    }

    public boolean isPublished() {
        return publishedAt != null;
    }

    /**
     * Broker confirm received. Does not mark {@link #publishedAt}; that timestamp
     * remains the local outbox-processor completion signal.
     */
    public void markRabbitPublished(Instant at) {
        if (at == null) {
            throw new IllegalArgumentException("rabbitPublishedAt is required");
        }
        if (this.rabbitPublishedAt == null) {
            this.rabbitPublishedAt = at;
            this.rabbitNextRetryAt = null;
        }
    }

    public void claimRabbitPublish(Instant leaseUntil) {
        if (leaseUntil == null) {
            throw new IllegalArgumentException("rabbit leaseUntil is required");
        }
        this.rabbitAttemptCount++;
        this.rabbitNextRetryAt = leaseUntil;
    }

    public void scheduleRabbitRetry(Instant nextRetryAt) {
        if (nextRetryAt == null) {
            throw new IllegalArgumentException("rabbitNextRetryAt is required");
        }
        if (this.rabbitPublishedAt != null) {
            return;
        }
        this.rabbitNextRetryAt = nextRetryAt;
    }

    public boolean isRabbitPublished() {
        return rabbitPublishedAt != null;
    }

    public UUID getId() {
        return id;
    }

    public String getEventType() {
        return eventType;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public UUID getAggregateId() {
        return aggregateId;
    }

    public int getSchemaVersion() {
        return schemaVersion;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public String getCausationId() {
        return causationId;
    }

    public String getPayloadJson() {
        return payloadJson;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public Instant getRabbitPublishedAt() {
        return rabbitPublishedAt;
    }

    public int getRabbitAttemptCount() {
        return rabbitAttemptCount;
    }

    public Instant getRabbitNextRetryAt() {
        return rabbitNextRetryAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
