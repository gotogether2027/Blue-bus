package in.bluebustickets.bluebus.foundation.outbox;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-event outbox unit of work. Uses PostgreSQL {@code FOR UPDATE SKIP LOCKED}
 * so concurrent application instances remain database-authoritative.
 */
@Service
@ConditionalOnProperty(prefix = "blue-bus.outbox", name = "enabled", matchIfMissing = true)
public class OutboxEventProcessor {

    private static final Logger log = LoggerFactory.getLogger(OutboxEventProcessor.class);

    @PersistenceContext
    private EntityManager entityManager;

    private final List<OutboxEventHandler> handlers;

    public OutboxEventProcessor(List<OutboxEventHandler> handlers) {
        this.handlers = List.copyOf(handlers);
    }

    /**
     * Claim and process one unpublished event. Returns empty when locked by another worker
     * or already published.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Boolean> tryProcess(UUID eventId, Instant now) {
        Optional<OutboxEvent> locked = lockUnpublished(eventId);
        if (locked.isEmpty()) {
            return Optional.empty();
        }

        OutboxEvent event = locked.get();
        if (event.isPublished()) {
            return Optional.empty();
        }

        OutboxEventHandler handler = handlers.stream()
                .filter(candidate -> candidate.eventType().equals(event.getEventType()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No handler registered for outbox event type " + event.getEventType()));

        event.recordAttempt();
        handler.handle(event);
        event.markPublished(now);
        entityManager.flush();
        log.debug("Published outbox event {} type={}", eventId, event.getEventType());
        return Optional.of(Boolean.TRUE);
    }

    /**
     * Persist attempt_count after a failed process transaction rolled back.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailedAttempt(UUID eventId) {
        Optional<OutboxEvent> locked = lockUnpublished(eventId);
        if (locked.isEmpty()) {
            return;
        }
        OutboxEvent event = locked.get();
        if (event.isPublished()) {
            return;
        }
        event.recordAttempt();
        entityManager.flush();
    }

    @SuppressWarnings("unchecked")
    private Optional<OutboxEvent> lockUnpublished(UUID eventId) {
        List<OutboxEvent> rows = entityManager.createNativeQuery("""
                SELECT *
                FROM outbox_events
                WHERE id = :id
                  AND published_at IS NULL
                FOR UPDATE SKIP LOCKED
                """, OutboxEvent.class)
                .setParameter("id", eventId)
                .getResultList();
        return rows.stream().findFirst();
    }
}
