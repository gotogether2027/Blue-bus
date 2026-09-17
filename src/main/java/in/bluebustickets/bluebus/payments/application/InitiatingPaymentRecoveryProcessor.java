package in.bluebustickets.bluebus.payments.application;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import in.bluebustickets.bluebus.payments.domain.PaymentAttempt;
import in.bluebustickets.bluebus.payments.domain.PaymentStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Short claim/backoff transactions for stale INITIATING attempts.
 * Provider HTTP stays outside these locks.
 */
@Service
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class InitiatingPaymentRecoveryProcessor {

    @PersistenceContext
    private EntityManager entityManager;

    private final InitiatingPaymentRecoveryProperties properties;

    public InitiatingPaymentRecoveryProcessor(InitiatingPaymentRecoveryProperties properties) {
        this.properties = properties;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<UUID> tryClaimDueAttempt(UUID paymentAttemptId, Instant now) {
        Optional<PaymentAttempt> locked = lockDueAttempt(paymentAttemptId, now);
        if (locked.isEmpty()) {
            return Optional.empty();
        }
        PaymentAttempt attempt = locked.get();
        if (!isRecoverable(attempt) || (attempt.getNextRetryAt() != null && attempt.getNextRetryAt().isAfter(now))) {
            return Optional.empty();
        }
        if (attempt.getCreatedAt() != null && attempt.getCreatedAt().isAfter(staleBefore(now))) {
            return Optional.empty();
        }
        Instant leaseUntil = now.plus(Duration.ofMillis(properties.getLeaseMs()));
        attempt.claimForRetry(leaseUntil);
        entityManager.flush();
        return Optional.of(attempt.getId());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void scheduleBackoff(UUID paymentAttemptId, Instant now) {
        PaymentAttempt locked = entityManager.find(
                PaymentAttempt.class, paymentAttemptId, LockModeType.PESSIMISTIC_WRITE);
        if (locked == null || !isRecoverable(locked)) {
            return;
        }
        long delayMs = properties.backoffDelayMs(locked.getAttemptCount());
        locked.scheduleRetry(now.plus(Duration.ofMillis(delayMs)));
        entityManager.flush();
    }

    static boolean isRecoverable(PaymentAttempt attempt) {
        return attempt.getStatus() == PaymentStatus.INITIATING
                && (attempt.getProviderOrderId() == null || attempt.getProviderOrderId().isBlank());
    }

    public Instant staleBefore(Instant now) {
        return now.minus(Duration.ofMillis(properties.getStaleThresholdMs()));
    }

    @SuppressWarnings("unchecked")
    private Optional<PaymentAttempt> lockDueAttempt(UUID paymentAttemptId, Instant now) {
        List<PaymentAttempt> rows = entityManager.createNativeQuery("""
                SELECT *
                FROM payment_attempts
                WHERE id = :id
                  AND status = 'INITIATING'
                  AND provider_order_id IS NULL
                  AND (next_retry_at IS NULL OR next_retry_at <= :now)
                  AND created_at <= :staleBefore
                FOR UPDATE SKIP LOCKED
                """, PaymentAttempt.class)
                .setParameter("id", paymentAttemptId)
                .setParameter("now", Timestamp.from(now))
                .setParameter("staleBefore", Timestamp.from(staleBefore(now)))
                .getResultList();
        return rows.stream().findFirst();
    }
}
