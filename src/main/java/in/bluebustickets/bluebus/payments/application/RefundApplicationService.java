package in.bluebustickets.bluebus.payments.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import in.bluebustickets.bluebus.booking.application.BookingPaymentPort;
import in.bluebustickets.bluebus.booking.domain.BookingStatus;
import in.bluebustickets.bluebus.foundation.api.error.ApplicationConflictException;
import in.bluebustickets.bluebus.foundation.api.error.ResourceNotFoundException;
import in.bluebustickets.bluebus.foundation.outbox.OutboxEvent;
import in.bluebustickets.bluebus.foundation.outbox.OutboxEventRepository;
import in.bluebustickets.bluebus.payments.api.dto.RefundResponse;
import in.bluebustickets.bluebus.payments.domain.PaymentAttempt;
import in.bluebustickets.bluebus.payments.domain.PaymentDisposition;
import in.bluebustickets.bluebus.payments.domain.PaymentStatus;
import in.bluebustickets.bluebus.payments.domain.Refund;
import in.bluebustickets.bluebus.payments.domain.RefundStatus;
import in.bluebustickets.bluebus.payments.provider.PaymentProvider;
import in.bluebustickets.bluebus.payments.provider.PaymentProviderRegistry;
import in.bluebustickets.bluebus.payments.repository.PaymentAttemptRepository;
import in.bluebustickets.bluebus.payments.repository.RefundRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class RefundApplicationService {

    public static final String COMPENSATION_REASON = "LATE_PAYMENT_COMPENSATION";

    private final PaymentAttemptRepository paymentAttemptRepository;
    private final RefundRepository refundRepository;
    private final PaymentProviderRegistry providerRegistry;
    private final BookingPaymentPort bookingPaymentPort;
    private final RefundWorker worker;
    private final Clock clock;
    private final ConcurrentHashMap<UUID, Object> refundCallLocks = new ConcurrentHashMap<>();

    public RefundApplicationService(
            PaymentAttemptRepository paymentAttemptRepository,
            RefundRepository refundRepository,
            PaymentProviderRegistry providerRegistry,
            BookingPaymentPort bookingPaymentPort,
            RefundWorker worker,
            Clock clock) {
        this.paymentAttemptRepository = paymentAttemptRepository;
        this.refundRepository = refundRepository;
        this.providerRegistry = providerRegistry;
        this.bookingPaymentPort = bookingPaymentPort;
        this.worker = worker;
        this.clock = clock;
    }

    public RefundResponse refund(UUID userId, UUID paymentAttemptId, String idempotencyKey, String reason) {
        String key = normalizeRequired(idempotencyKey, "Idempotency-Key");
        PaymentAttempt attempt = requireOwnedSucceeded(userId, paymentAttemptId);
        requireCancellationRefundFlow(attempt.getBookingId());
        return executeRefund(attempt, key, normalizeReason(reason));
    }

    /**
     * Internal entry used after confirmed booking cancellation commits.
     * Skips the public orphan-refund gate because the booking is already {@code REFUND_PENDING}.
     */
    public RefundResponse refundForCancelledBooking(
            UUID userId,
            UUID paymentAttemptId,
            String idempotencyKey,
            String reason) {
        String key = normalizeRequired(idempotencyKey, "Idempotency-Key");
        PaymentAttempt attempt = requireOwnedSucceeded(userId, paymentAttemptId);
        BookingStatus status = bookingStatus(attempt.getBookingId());
        if (status != BookingStatus.REFUND_PENDING && status != BookingStatus.REFUNDED) {
            throw new ApplicationConflictException(
                    "Refund is only available after confirmed booking cancellation.");
        }
        return executeRefund(attempt, key, normalizeReason(reason));
    }

    private RefundResponse executeRefund(PaymentAttempt attempt, String key, String normalizedReason) {
        String fingerprint = requestFingerprint(
                attempt.getId(), attempt.getCapturedAmount(), attempt.getCurrency());

        Refund existing = refundRepository
                .findByPaymentAttemptIdAndIdempotencyKey(attempt.getId(), key)
                .orElse(null);
        if (existing == null) {
            // One logical refund per captured payment once cancellation has started.
            existing = refundRepository.findByPaymentAttemptIdAndStatusIn(
                            attempt.getId(),
                            List.of(
                                    RefundStatus.REQUESTED,
                                    RefundStatus.PROCESSING,
                                    RefundStatus.SUCCEEDED,
                                    RefundStatus.FAILED))
                    .stream()
                    .findFirst()
                    .orElse(null);
        }
        if (existing != null) {
            return resumeOrReturn(existing, fingerprint, attempt);
        }

        Refund reserved;
        try {
            reserved = worker.reserve(attempt.getId(), key, fingerprint, normalizedReason, clock.instant());
        } catch (DataIntegrityViolationException exception) {
            Refund raced = refundRepository
                    .findByPaymentAttemptIdAndIdempotencyKey(attempt.getId(), key)
                    .orElse(null);
            if (raced != null) {
                return resumeOrReturn(raced, fingerprint, attempt);
            }
            throw new ApplicationConflictException("Refund request conflicts with an active refund.");
        }

        if (!needsProviderRefund(reserved)) {
            return toResponse(reserved);
        }
        return completeProviderRefund(reserved, attempt);
    }

    /**
     * Worker/system path: provider HTTP then existing {@code RefundWorker.complete}.
     * Caller must not hold booking locks. Reuses {@code refund.id} as the Razorpay idempotency key.
     * Handles confirmed-cancellation refunds ({@code REFUND_PENDING}) and late-payment
     * compensation refunds ({@code SUCCEEDED}/{@code REQUIRES_RESOLUTION}).
     */
    public void executeProviderRefund(UUID refundId) {
        Refund current = refundRepository.findById(refundId)
                .orElseThrow(() -> new ResourceNotFoundException("Refund was not found."));
        if (!needsProviderRefund(current)) {
            return;
        }
        PaymentAttempt attempt = paymentAttemptRepository.findById(current.getPaymentAttemptId())
                .orElseThrow(() -> new ResourceNotFoundException("Payment attempt was not found."));
        BookingStatus status = bookingStatus(current.getBookingId());
        if (!isWorkerEligible(current, status, attempt)) {
            return;
        }
        completeProviderRefund(current, attempt);
    }

    public static String requestFingerprint(UUID paymentAttemptId, java.math.BigDecimal amount, String currency) {
        return sha256Hex(paymentAttemptId + "|" + amount + "|" + currency);
    }

    public static String cancellationIdempotencyKey(UUID cancellationId) {
        return "booking-cancel-" + cancellationId;
    }

    public static String compensationIdempotencyKey(UUID paymentAttemptId) {
        return "late-payment-" + paymentAttemptId;
    }

    public static boolean isCompensationIdempotencyKey(String idempotencyKey) {
        return idempotencyKey != null && idempotencyKey.startsWith("late-payment-");
    }

    /**
     * Cancellation refunds stay on {@code REFUND_PENDING}. Compensation refunds are keyed
     * {@code late-payment-{paymentAttemptId}} and never enter the trip-cancel booking path.
     */
    public static boolean isWorkerEligible(Refund refund, BookingStatus bookingStatus, PaymentAttempt attempt) {
        if (bookingStatus == BookingStatus.REFUND_PENDING) {
            return true;
        }
        return isCompensationRefund(refund, attempt);
    }

    public static boolean isCompensationRefund(Refund refund, PaymentAttempt attempt) {
        return refund != null
                && attempt != null
                && attempt.getDisposition() == PaymentDisposition.REQUIRES_RESOLUTION
                && isCompensationIdempotencyKey(refund.getIdempotencyKey());
    }

    private void requireCancellationRefundFlow(UUID bookingId) {
        BookingStatus status = bookingStatus(bookingId);
        if (status != BookingStatus.REFUND_PENDING && status != BookingStatus.REFUNDED) {
            throw new ApplicationConflictException(
                    "Refund is only available after confirmed booking cancellation.");
        }
    }

    private BookingStatus bookingStatus(UUID bookingId) {
        return bookingPaymentPort.currentStatus(bookingId);
    }

    private RefundResponse resumeOrReturn(Refund existing, String fingerprint, PaymentAttempt attempt) {
        if (!Objects.equals(existing.getRequestFingerprint(), fingerprint)) {
            throw new ApplicationConflictException("Idempotency key was reused with a different refund request.");
        }
        if (needsProviderRefund(existing)) {
            return completeProviderRefund(existing, attempt);
        }
        return toResponse(existing);
    }

    private RefundResponse completeProviderRefund(Refund refund, PaymentAttempt attempt) {
        Object lock = refundCallLocks.computeIfAbsent(refund.getId(), id -> new Object());
        try {
            synchronized (lock) {
                Refund current = refundRepository.findById(refund.getId())
                        .orElseThrow(() -> new ResourceNotFoundException("Refund was not found."));
                if (!needsProviderRefund(current)) {
                    return toResponse(current);
                }
                PaymentProvider provider = providerRegistry.require(attempt.getProvider());
                PaymentProvider.ProviderRefundResult result = provider.refund(
                        new PaymentProvider.ProviderRefundCommand(
                                current.getId(),
                                attempt.getProviderPaymentId(),
                                current.getAmount(),
                                current.getCurrency(),
                                current.getId().toString()));
                return toResponse(worker.complete(current.getId(), result, clock.instant()));
            }
        } finally {
            refundCallLocks.remove(refund.getId(), lock);
        }
    }

    private PaymentAttempt requireOwnedSucceeded(UUID userId, UUID paymentAttemptId) {
        PaymentAttempt attempt = paymentAttemptRepository.findById(paymentAttemptId).orElse(null);
        if (attempt == null || !attempt.getUserId().equals(userId)) {
            throw new ResourceNotFoundException("Payment attempt was not found.");
        }
        if (attempt.getStatus() != PaymentStatus.SUCCEEDED
                || attempt.getCapturedAmount() == null
                || attempt.getProviderPaymentId() == null
                || attempt.getProviderPaymentId().isBlank()) {
            throw new ApplicationConflictException("Payment is not eligible for refund.");
        }
        return attempt;
    }

    private static boolean needsProviderRefund(Refund refund) {
        return (refund.getStatus() == RefundStatus.REQUESTED
                || refund.getStatus() == RefundStatus.PROCESSING
                || refund.getStatus() == RefundStatus.FAILED)
                && (refund.getProviderRefundId() == null || refund.getProviderRefundId().isBlank());
    }

    static RefundResponse toResponse(Refund refund) {
        return new RefundResponse(
                refund.getId(),
                refund.getPaymentAttemptId(),
                refund.getBookingId(),
                refund.getProvider(),
                refund.getProviderRefundId(),
                refund.getAmount(),
                refund.getCurrency(),
                refund.getReason(),
                refund.getStatus(),
                refund.getRequestedAt(),
                refund.getProcessedAt());
    }

    private static String normalizeRequired(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        String normalized = value.trim();
        if (normalized.length() > 100) {
            throw new IllegalArgumentException(field + " must be at most 100 characters");
        }
        return normalized;
    }

    private static String normalizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "CUSTOMER_REQUEST";
        }
        String normalized = reason.trim();
        if (normalized.length() > 100) {
            throw new IllegalArgumentException("reason must be at most 100 characters");
        }
        return normalized;
    }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    @Service
    @ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
    static class RefundWorker {

        private final BookingPaymentPort bookingPaymentPort;
        private final PaymentAttemptRepository paymentAttemptRepository;
        private final RefundRepository refundRepository;
        private final OutboxEventRepository outboxEventRepository;

        RefundWorker(
                BookingPaymentPort bookingPaymentPort,
                PaymentAttemptRepository paymentAttemptRepository,
                RefundRepository refundRepository,
                OutboxEventRepository outboxEventRepository) {
            this.bookingPaymentPort = bookingPaymentPort;
            this.paymentAttemptRepository = paymentAttemptRepository;
            this.refundRepository = refundRepository;
            this.outboxEventRepository = outboxEventRepository;
        }

        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public Refund reserve(
                UUID paymentAttemptId,
                String key,
                String fingerprint,
                String reason,
                Instant now) {
            PaymentAttempt unlocked = paymentAttemptRepository.findById(paymentAttemptId)
                    .orElseThrow(() -> new ResourceNotFoundException("Payment attempt was not found."));
            var booking = bookingPaymentPort.lockForPaymentOutcome(unlocked.getBookingId());
            if (booking.status() != BookingStatus.REFUND_PENDING
                    && booking.status() != BookingStatus.REFUNDED) {
                throw new ApplicationConflictException(
                        "Refund is only available after confirmed booking cancellation.");
            }
            PaymentAttempt attempt = paymentAttemptRepository.findByIdForUpdate(paymentAttemptId)
                    .orElseThrow(() -> new ResourceNotFoundException("Payment attempt was not found."));
            if (attempt.getStatus() != PaymentStatus.SUCCEEDED || attempt.getCapturedAmount() == null) {
                throw new ApplicationConflictException("Payment is not eligible for refund.");
            }
            Refund existing = refundRepository
                    .findByPaymentAttemptIdAndIdempotencyKey(attempt.getId(), key)
                    .orElse(null);
            if (existing != null) {
                if (!Objects.equals(existing.getRequestFingerprint(), fingerprint)) {
                    throw new ApplicationConflictException(
                            "Idempotency key was reused with a different refund request.");
                }
                return existing;
            }
            Refund refund = new Refund(
                    attempt.getId(),
                    attempt.getBookingId(),
                    attempt.getProvider(),
                    key,
                    fingerprint,
                    attempt.getCapturedAmount(),
                    attempt.getCurrency(),
                    reason,
                    now);
            return refundRepository.saveAndFlush(refund);
        }

        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public Refund complete(
                UUID refundId,
                PaymentProvider.ProviderRefundResult result,
                Instant now) {
            Refund unlocked = refundRepository.findById(refundId)
                    .orElseThrow(() -> new ResourceNotFoundException("Refund was not found."));
            bookingPaymentPort.lockForPaymentOutcome(unlocked.getBookingId());
            paymentAttemptRepository.findByIdForUpdate(unlocked.getPaymentAttemptId())
                    .orElseThrow(() -> new ResourceNotFoundException("Payment attempt was not found."));
            Refund refund = refundRepository.findByIdForUpdate(refundId)
                    .orElseThrow(() -> new ResourceNotFoundException("Refund was not found."));

            String providerStatus = result.providerStatus();
            if (isFailed(providerStatus)) {
                if (refund.getStatus() != RefundStatus.SUCCEEDED) {
                    refund.markFailed(providerStatus, "PROVIDER_FAILED", now);
                }
                return refundRepository.saveAndFlush(refund);
            }
            if (isSucceeded(providerStatus)) {
                RefundStatus previous = refund.getStatus();
                refund.markSucceeded(result.providerRefundId(), providerStatus, now);
                if (previous != RefundStatus.SUCCEEDED) {
                    bookingPaymentPort.markRefunded(refund.getBookingId());
                    outboxEventRepository.save(new OutboxEvent(
                            "REFUND_SUCCEEDED",
                            "PAYMENT_ATTEMPT",
                            refund.getPaymentAttemptId(),
                            "{\"refundId\":\"" + refund.getId()
                                    + "\",\"paymentAttemptId\":\"" + refund.getPaymentAttemptId() + "\"}",
                            now,
                            refund.getIdempotencyKey(),
                            refund.getId().toString()));
                }
                return refundRepository.saveAndFlush(refund);
            }
            refund.markProcessing(result.providerRefundId(), providerStatus);
            return refundRepository.saveAndFlush(refund);
        }

        private static boolean isSucceeded(String providerStatus) {
            if (providerStatus == null) {
                return false;
            }
            String status = providerStatus.trim().toLowerCase();
            return "processed".equals(status) || "succeeded".equals(status);
        }

        private static boolean isFailed(String providerStatus) {
            return providerStatus != null && "failed".equalsIgnoreCase(providerStatus.trim());
        }
    }
}
