package in.bluebustickets.bluebus.notification.application;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import in.bluebustickets.bluebus.notification.domain.Notification;
import in.bluebustickets.bluebus.notification.domain.NotificationStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class NotificationDeliveryProcessor {

    @PersistenceContext
    private EntityManager entityManager;

    private final NotificationProperties properties;

    public NotificationDeliveryProcessor(NotificationProperties properties) {
        this.properties = properties;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Notification> tryClaim(UUID notificationId, Instant now) {
        Optional<Notification> locked = lockDue(notificationId, now);
        if (locked.isEmpty()) {
            return Optional.empty();
        }
        Notification notification = locked.get();
        if (notification.getStatus() != NotificationStatus.PENDING) {
            return Optional.empty();
        }
        Instant leaseUntil = now.plus(Duration.ofMillis(properties.getDelivery().getLeaseMs()));
        notification.claimForDelivery(leaseUntil);
        entityManager.flush();
        entityManager.detach(notification);
        return Optional.of(notification);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSent(UUID notificationId, String providerMessageId, Instant sentAt) {
        Notification locked = entityManager.find(Notification.class, notificationId, LockModeType.PESSIMISTIC_WRITE);
        if (locked == null || locked.getStatus() == NotificationStatus.SENT) {
            return;
        }
        locked.markSent(providerMessageId, sentAt);
        entityManager.flush();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(
            UUID notificationId,
            Instant now,
            boolean retryable,
            String failureCode,
            String failureMessage) {
        Notification locked = entityManager.find(Notification.class, notificationId, LockModeType.PESSIMISTIC_WRITE);
        if (locked == null || locked.getStatus() == NotificationStatus.SENT) {
            return;
        }
        int maxAttempts = properties.getDelivery().getMaxAttempts();
        if (!retryable || locked.getAttemptCount() >= maxAttempts) {
            locked.markFailed(failureCode, failureMessage);
        } else {
            long delay = properties.getDelivery().backoffDelayMs(locked.getAttemptCount());
            locked.scheduleRetry(now.plus(Duration.ofMillis(delay)), failureCode, failureMessage);
        }
        entityManager.flush();
    }

    @SuppressWarnings("unchecked")
    private Optional<Notification> lockDue(UUID notificationId, Instant now) {
        List<Notification> rows = entityManager.createNativeQuery("""
                SELECT *
                FROM notifications
                WHERE id = :id
                  AND status = 'PENDING'
                  AND (next_retry_at IS NULL OR next_retry_at <= :now)
                FOR UPDATE SKIP LOCKED
                """, Notification.class)
                .setParameter("id", notificationId)
                .setParameter("now", Timestamp.from(now))
                .getResultList();
        return rows.stream().findFirst();
    }
}
