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
import in.bluebustickets.bluebus.payments.api.dto.PaymentInitiationResponse;
import in.bluebustickets.bluebus.payments.api.dto.PaymentResponse;
import in.bluebustickets.bluebus.payments.domain.PaymentAttempt;
import in.bluebustickets.bluebus.payments.domain.PaymentStatus;
import in.bluebustickets.bluebus.payments.provider.PaymentProvider;
import in.bluebustickets.bluebus.payments.provider.PaymentProviderRegistry;
import in.bluebustickets.bluebus.payments.repository.PaymentAttemptRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class PaymentInitiationService {

    private final PaymentAttemptRepository paymentAttemptRepository;
    private final PaymentProviderRegistry providerRegistry;
    private final PaymentProperties properties;
    private final PaymentInitiationWorker worker;
    private final Clock clock;
    private final ConcurrentHashMap<UUID, Object> providerCallLocks = new ConcurrentHashMap<>();

    public PaymentInitiationService(
            PaymentAttemptRepository paymentAttemptRepository,
            PaymentProviderRegistry providerRegistry,
            PaymentProperties properties,
            PaymentInitiationWorker worker,
            Clock clock) {
        this.paymentAttemptRepository = paymentAttemptRepository;
        this.providerRegistry = providerRegistry;
        this.properties = properties;
        this.worker = worker;
        this.clock = clock;
    }

    public PaymentInitiationResponse initiate(UUID userId, UUID bookingId, String idempotencyKey) {
        String key = normalizeRequired(idempotencyKey, "Idempotency-Key");
        String providerCode = properties.getDefaultProvider();
        PaymentProvider provider = providerRegistry.require(providerCode);
        String fingerprint = sha256Hex(bookingId + "|" + providerCode);

        PaymentAttempt existing = paymentAttemptRepository
                .findByUserIdAndIdempotencyKey(userId, key)
                .orElse(null);
        if (existing != null) {
            return resumeOrReturn(existing, bookingId, fingerprint, provider);
        }

        PaymentReservation reservation;
        try {
            reservation = worker.reserve(userId, bookingId, key, fingerprint, providerCode, clock.instant());
        } catch (DataIntegrityViolationException exception) {
            PaymentAttempt raced = paymentAttemptRepository
                    .findByUserIdAndIdempotencyKey(userId, key)
                    .orElse(null);
            if (raced != null) {
                return resumeOrReturn(raced, bookingId, fingerprint, provider);
            }
            throw new ApplicationConflictException("Payment initiation conflicts with an active attempt.");
        }

        if (!reservation.created()) {
            return resumeOrReturn(reservation.attempt(), bookingId, fingerprint, provider);
        }

        return completeProviderOrder(reservation.attempt(), provider);
    }

    /**
     * Worker recovery: reuse the same provider initiation path and Razorpay
     * {@code payment_attempt_id} idempotency key. Never confirms a booking.
     */
    public void recoverProviderOrder(UUID attemptId) {
        PaymentAttempt attempt = paymentAttemptRepository.findById(attemptId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment attempt was not found."));
        if (!needsProviderRecovery(attempt)) {
            return;
        }
        PaymentProvider provider = providerRegistry.require(attempt.getProvider());
        completeProviderOrder(attempt, provider);
    }

    private PaymentInitiationResponse resumeOrReturn(
            PaymentAttempt existing,
            UUID bookingId,
            String fingerprint,
            PaymentProvider provider) {
        if (!existing.getBookingId().equals(bookingId)
                || !Objects.equals(existing.getRequestFingerprint(), fingerprint)) {
            throw new ApplicationConflictException(
                    "Idempotency key was reused with a different payment request.");
        }
        if (needsProviderRecovery(existing)) {
            return completeProviderOrder(existing, provider);
        }
        return toInitiationResponse(existing);
    }

    private PaymentInitiationResponse completeProviderOrder(PaymentAttempt attempt, PaymentProvider provider) {
        Object lock = providerCallLocks.computeIfAbsent(attempt.getId(), id -> new Object());
        try {
            synchronized (lock) {
                PaymentAttempt current = paymentAttemptRepository.findById(attempt.getId())
                        .orElseThrow(() -> new ResourceNotFoundException("Payment attempt was not found."));
                if (!needsProviderRecovery(current)) {
                    return toInitiationResponse(current);
                }
                PaymentProvider.ProviderInitiationResult providerResult = provider.initiate(
                        new PaymentProvider.ProviderInitiationCommand(
                                current.getId(),
                                current.getMerchantReference(),
                                current.getRequestedAmount(),
                                current.getCurrency(),
                                current.getBookingPaymentExpiresAt()));
                return toInitiationResponse(worker.completeInitiation(
                        current.getId(), providerResult, clock.instant()));
            }
        } finally {
            providerCallLocks.remove(attempt.getId(), lock);
        }
    }

    private static boolean needsProviderRecovery(PaymentAttempt attempt) {
        return attempt.getStatus() == PaymentStatus.INITIATING
                && (attempt.getProviderOrderId() == null || attempt.getProviderOrderId().isBlank());
    }

    @Transactional(readOnly = true)
    public PaymentResponse getOwned(UUID userId, UUID attemptId) {
        PaymentAttempt attempt = paymentAttemptRepository.findById(attemptId).orElse(null);
        if (attempt == null || !attempt.getUserId().equals(userId)) {
            throw new ResourceNotFoundException("Payment attempt was not found.");
        }
        return toPaymentResponse(attempt);
    }

    static PaymentInitiationResponse toInitiationResponse(PaymentAttempt attempt) {
        return new PaymentInitiationResponse(
                attempt.getId(),
                attempt.getBookingId(),
                attempt.getProvider(),
                attempt.getMerchantReference(),
                attempt.getProviderOrderId(),
                attempt.getCheckoutReference(),
                attempt.getRequestedAmount(),
                attempt.getCurrency(),
                attempt.getStatus(),
                attempt.getDisposition(),
                attempt.getBookingPaymentExpiresAt());
    }

    static PaymentResponse toPaymentResponse(PaymentAttempt attempt) {
        return new PaymentResponse(
                attempt.getId(),
                attempt.getBookingId(),
                attempt.getProvider(),
                attempt.getMerchantReference(),
                attempt.getProviderOrderId(),
                attempt.getRequestedAmount(),
                attempt.getCapturedAmount(),
                attempt.getCurrency(),
                attempt.getStatus(),
                attempt.getDisposition(),
                attempt.getCreatedAt(),
                attempt.getProcessedAt());
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

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    record PaymentReservation(PaymentAttempt attempt, boolean created) {
    }

    @Service
    @ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
    static class PaymentInitiationWorker {

        private final BookingPaymentPort bookingPaymentPort;
        private final PaymentAttemptRepository paymentAttemptRepository;
        private final OutboxEventRepository outboxEventRepository;
        private final Logger log = LoggerFactory.getLogger(PaymentInitiationWorker.class);

        PaymentInitiationWorker(
                BookingPaymentPort bookingPaymentPort,
                PaymentAttemptRepository paymentAttemptRepository,
                OutboxEventRepository outboxEventRepository) {
            this.bookingPaymentPort = bookingPaymentPort;
            this.paymentAttemptRepository = paymentAttemptRepository;
            this.outboxEventRepository = outboxEventRepository;
        }

        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public PaymentReservation reserve(
                UUID userId,
                UUID bookingId,
                String key,
                String fingerprint,
                String provider,
                Instant now) {
            var booking = bookingPaymentPort.lockOwnedForPaymentInitiation(bookingId, userId, now);
            PaymentAttempt existing = paymentAttemptRepository
                    .findByUserIdAndIdempotencyKey(userId, key)
                    .orElse(null);
            if (existing != null) {
                if (!existing.getBookingId().equals(bookingId)
                        || !Objects.equals(existing.getRequestFingerprint(), fingerprint)) {
                    throw new ApplicationConflictException(
                            "Idempotency key was reused with a different payment request.");
                }
                return new PaymentReservation(existing, false);
            }
            if (paymentAttemptRepository.existsByBookingIdAndStatusIn(
                    bookingId, List.of(PaymentStatus.INITIATING, PaymentStatus.PENDING))) {
                throw new ApplicationConflictException("A payment attempt is already in progress.");
            }

            PaymentAttempt attempt = new PaymentAttempt(
                    bookingId,
                    userId,
                    provider,
                    "BBPAY-" + UUID.randomUUID(),
                    key,
                    fingerprint,
                    booking.totalAmount(),
                    booking.currency(),
                    booking.paymentExpiresAt());
            return new PaymentReservation(paymentAttemptRepository.saveAndFlush(attempt), true);
        }

        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public PaymentAttempt completeInitiation(
                UUID attemptId,
                PaymentProvider.ProviderInitiationResult result,
                Instant now) {
            PaymentAttempt unlocked = paymentAttemptRepository.findById(attemptId)
                    .orElseThrow(() -> new ResourceNotFoundException("Payment attempt was not found."));
            var booking = bookingPaymentPort.lockForPaymentOutcome(unlocked.getBookingId());
            PaymentAttempt attempt = paymentAttemptRepository.findByIdForUpdate(attemptId)
                    .orElseThrow(() -> new ResourceNotFoundException("Payment attempt was not found."));

            boolean wasInitiating = attempt.getStatus() == PaymentStatus.INITIATING;
            attempt.markInitiated(
                    result.providerOrderId(), result.providerStatus(), result.checkoutReference());
            boolean becamePending = wasInitiating && attempt.getStatus() == PaymentStatus.PENDING;
            if (booking.status() != BookingStatus.PENDING_PAYMENT
                    || !booking.paymentExpiresAt().isAfter(now)) {
                attempt.markExpired(result.providerStatus(), now);
            }
            if (becamePending && attempt.getStatus() == PaymentStatus.PENDING) {
                outboxEventRepository.save(new OutboxEvent(
                        "PAYMENT_INITIATED",
                        "PAYMENT_ATTEMPT",
                        attempt.getId(),
                        "{\"paymentAttemptId\":\"" + attempt.getId()
                                + "\",\"bookingId\":\"" + attempt.getBookingId() + "\"}",
                        now,
                        attempt.getIdempotencyKey(),
                        null));
                log.info(
                        "PAYMENT_INITIATED paymentAttemptId={} bookingId={} provider={} providerOrderId={}",
                        attempt.getId(),
                        attempt.getBookingId(),
                        attempt.getProvider(),
                        attempt.getProviderOrderId());
            }
            return paymentAttemptRepository.saveAndFlush(attempt);
        }
    }
}
