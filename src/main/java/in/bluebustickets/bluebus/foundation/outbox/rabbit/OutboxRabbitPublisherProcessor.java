package in.bluebustickets.bluebus.foundation.outbox.rabbit;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import in.bluebustickets.bluebus.foundation.outbox.OutboxEvent;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Short claim/backoff transactions for broker publishing.
 * RabbitMQ I/O stays outside these locks.
 */
@Service
@ConditionalOnProperty(prefix = "blue-bus.rabbitmq", name = "enabled", havingValue = "true")
public class OutboxRabbitPublisherProcessor {

    @PersistenceContext
    private EntityManager entityManager;

    private final RabbitMqProperties properties;

    public OutboxRabbitPublisherProcessor(RabbitMqProperties properties) {
        this.properties = properties;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<OutboxEvent> tryClaim(UUID eventId, Instant now) {
        Optional<OutboxEvent> locked = lockUnpublished(eventId, now);
        if (locked.isEmpty()) {
            return Optional.empty();
        }
        OutboxEvent event = locked.get();
        if (event.isRabbitPublished()) {
            return Optional.empty();
        }
        Instant leaseUntil = now.plus(Duration.ofMillis(properties.getPublisher().getLeaseMs()));
        event.claimRabbitPublish(leaseUntil);
        entityManager.flush();
        entityManager.detach(event);
        return Optional.of(event);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markRabbitPublished(UUID eventId, Instant now) {
        OutboxEvent locked = entityManager.find(OutboxEvent.class, eventId, LockModeType.PESSIMISTIC_WRITE);
        if (locked == null || locked.isRabbitPublished()) {
            return;
        }
        locked.markRabbitPublished(now);
        entityManager.flush();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void scheduleBackoff(UUID eventId, Instant now) {
        OutboxEvent locked = entityManager.find(OutboxEvent.class, eventId, LockModeType.PESSIMISTIC_WRITE);
        if (locked == null || locked.isRabbitPublished()) {
            return;
        }
        long delayMs = properties.getPublisher().backoffDelayMs(locked.getRabbitAttemptCount());
        locked.scheduleRabbitRetry(now.plus(Duration.ofMillis(delayMs)));
        entityManager.flush();
    }

    @SuppressWarnings("unchecked")
    private Optional<OutboxEvent> lockUnpublished(UUID eventId, Instant now) {
        List<OutboxEvent> rows = entityManager.createNativeQuery("""
                SELECT *
                FROM outbox_events
                WHERE id = :id
                  AND rabbit_published_at IS NULL
                  AND (rabbit_next_retry_at IS NULL OR rabbit_next_retry_at <= :now)
                FOR UPDATE SKIP LOCKED
                """, OutboxEvent.class)
                .setParameter("id", eventId)
                .setParameter("now", Timestamp.from(now))
                .getResultList();
        return rows.stream().findFirst();
    }
}
