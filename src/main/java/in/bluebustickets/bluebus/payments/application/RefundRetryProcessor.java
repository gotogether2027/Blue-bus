package in.bluebustickets.bluebus.payments.application;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import in.bluebustickets.bluebus.booking.application.BookingPaymentPort;
import in.bluebustickets.bluebus.booking.domain.BookingStatus;
import in.bluebustickets.bluebus.payments.domain.PaymentAttempt;
import in.bluebustickets.bluebus.payments.domain.Refund;
import in.bluebustickets.bluebus.payments.domain.RefundStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Short claim/backoff transactions for due refunds. Provider HTTP stays outside these locks.
 */
@Service
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class RefundRetryProcessor {

    @PersistenceContext
    private EntityManager entityManager;

    private final BookingPaymentPort bookingPaymentPort;
    private final RefundRetryProperties properties;

    public RefundRetryProcessor(BookingPaymentPort bookingPaymentPort, RefundRetryProperties properties) {
        this.bookingPaymentPort = bookingPaymentPort;
        this.properties = properties;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<UUID> tryClaimDueRefund(UUID refundId, Instant now) {
        Optional<Refund> locked = lockDueRefund(refundId, now);
        if (locked.isEmpty()) {
            return Optional.empty();
        }
        Refund refund = locked.get();
        if (!isRetryable(refund) || (refund.getNextRetryAt() != null && refund.getNextRetryAt().isAfter(now))) {
            return Optional.empty();
        }
        if (!workerEligible(refund)) {
            return Optional.empty();
        }
        Instant leaseUntil = now.plus(Duration.ofMillis(properties.getLeaseMs()));
        refund.claimForRetry(leaseUntil);
        entityManager.flush();
        return Optional.of(refund.getId());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void scheduleBackoff(UUID refundId, Instant now) {
        Refund locked = entityManager.find(Refund.class, refundId, LockModeType.PESSIMISTIC_WRITE);
        if (locked == null || !isRetryable(locked)) {
            return;
        }
        if (!workerEligible(locked)) {
            return;
        }
        long delayMs = properties.backoffDelayMs(locked.getAttemptCount());
        locked.scheduleRetry(now.plus(Duration.ofMillis(delayMs)));
        entityManager.flush();
    }

    static boolean isRetryable(Refund refund) {
        if (refund.getProviderRefundId() != null && !refund.getProviderRefundId().isBlank()) {
            return false;
        }
        return refund.getStatus() == RefundStatus.REQUESTED
                || refund.getStatus() == RefundStatus.PROCESSING
                || refund.getStatus() == RefundStatus.FAILED;
    }

    /**
     * Claim both confirmed-cancellation refunds and late-payment compensation refunds.
     * Reads payment without extra locks; refund SKIP LOCKED is already held. Never calls the provider.
     */
    private boolean workerEligible(Refund refund) {
        BookingStatus bookingStatus = bookingPaymentPort.currentStatus(refund.getBookingId());
        PaymentAttempt attempt = entityManager.find(PaymentAttempt.class, refund.getPaymentAttemptId());
        return RefundApplicationService.isWorkerEligible(refund, bookingStatus, attempt);
    }

    @SuppressWarnings("unchecked")
    private Optional<Refund> lockDueRefund(UUID refundId, Instant now) {
        List<Refund> rows = entityManager.createNativeQuery("""
                SELECT *
                FROM refunds
                WHERE id = :id
                  AND provider_refund_id IS NULL
                  AND status IN ('REQUESTED', 'PROCESSING', 'FAILED')
                  AND (next_retry_at IS NULL OR next_retry_at <= :now)
                FOR UPDATE SKIP LOCKED
                """, Refund.class)
                .setParameter("id", refundId)
                .setParameter("now", Timestamp.from(now))
                .getResultList();
        return rows.stream().findFirst();
    }
}
