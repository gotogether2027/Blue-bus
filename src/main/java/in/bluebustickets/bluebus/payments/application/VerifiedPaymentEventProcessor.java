package in.bluebustickets.bluebus.payments.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import in.bluebustickets.bluebus.booking.application.BookingPaymentPort;
import in.bluebustickets.bluebus.booking.domain.BookingStatus;
import in.bluebustickets.bluebus.foundation.outbox.OutboxEvent;
import in.bluebustickets.bluebus.foundation.outbox.OutboxEventRepository;
import in.bluebustickets.bluebus.payments.domain.PaymentAttempt;
import in.bluebustickets.bluebus.payments.domain.PaymentDisposition;
import in.bluebustickets.bluebus.payments.domain.PaymentProviderEvent;
import in.bluebustickets.bluebus.payments.domain.PaymentStatus;
import in.bluebustickets.bluebus.payments.domain.ProviderEventProcessingStatus;
import in.bluebustickets.bluebus.payments.domain.Refund;
import in.bluebustickets.bluebus.payments.domain.RefundStatus;
import in.bluebustickets.bluebus.payments.provider.PaymentProvider.ProviderEventType;
import in.bluebustickets.bluebus.payments.repository.PaymentAttemptRepository;
import in.bluebustickets.bluebus.payments.repository.PaymentProviderEventRepository;
import in.bluebustickets.bluebus.payments.repository.RefundRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class VerifiedPaymentEventProcessor {

    private final PaymentProviderEventRepository eventRepository;
    private final PaymentAttemptRepository attemptRepository;
    private final RefundRepository refundRepository;
    private final BookingPaymentPort bookingPaymentPort;
    private final OutboxEventRepository outboxEventRepository;
    private final Clock clock;

    public VerifiedPaymentEventProcessor(
            PaymentProviderEventRepository eventRepository,
            PaymentAttemptRepository attemptRepository,
            RefundRepository refundRepository,
            BookingPaymentPort bookingPaymentPort,
            OutboxEventRepository outboxEventRepository,
            Clock clock) {
        this.eventRepository = eventRepository;
        this.attemptRepository = attemptRepository;
        this.refundRepository = refundRepository;
        this.bookingPaymentPort = bookingPaymentPort;
        this.outboxEventRepository = outboxEventRepository;
        this.clock = clock;
    }

    /**
     * Processes only events already verified and persisted by the webhook boundary.
     * Lock order after the independent inbox row is booking → payment attempt → allocations.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PaymentProcessingResult process(UUID eventId) {
        PaymentProviderEvent event = eventRepository.findByIdForUpdate(eventId).orElseThrow();
        if (isTerminal(event.getProcessingStatus())) {
            return resultFromStored(event);
        }

        PaymentAttempt candidate = resolveAttempt(event);
        Instant now = clock.instant();
        if (candidate == null) {
            event.requireReview("UNKNOWN_PAYMENT_REFERENCE", now);
            return new PaymentProcessingResult(
                    null, null, null, null, true, "UNKNOWN_PAYMENT_REFERENCE");
        }

        var booking = bookingPaymentPort.lockForPaymentOutcome(candidate.getBookingId());
        PaymentAttempt attempt = attemptRepository.findByIdForUpdate(candidate.getId()).orElseThrow();
        event.startProcessing(attempt.getId());

        ProviderEventType type;
        try {
            type = ProviderEventType.valueOf(event.getEventType());
        } catch (IllegalArgumentException exception) {
            type = ProviderEventType.UNKNOWN;
        }

        return switch (type) {
            case PAYMENT_PENDING -> processPending(event, attempt, booking.status(), now);
            case PAYMENT_FAILED -> processFailed(event, attempt, booking.status(), now);
            case PAYMENT_SUCCEEDED -> processSuccess(event, attempt, booking, now);
            case REFUND_SUCCEEDED -> processRefund(event, attempt, booking.status(), now, true);
            case REFUND_FAILED -> processRefund(event, attempt, booking.status(), now, false);
            case UNKNOWN -> {
                event.ignore("UNSUPPORTED_EVENT_TYPE", now);
                yield new PaymentProcessingResult(
                        attempt.getId(), attempt.getStatus(), attempt.getDisposition(),
                        booking.status(), false, "UNSUPPORTED_EVENT_TYPE");
            }
        };
    }

    private PaymentAttempt resolveAttempt(PaymentProviderEvent event) {
        if (event.getMerchantReference() != null && !event.getMerchantReference().isBlank()) {
            var byReference = attemptRepository.findByProviderAndMerchantReference(
                    event.getProvider(), event.getMerchantReference());
            if (byReference.isPresent()) {
                return byReference.get();
            }
        }
        if (event.getProviderOrderId() != null && !event.getProviderOrderId().isBlank()) {
            var byOrder = attemptRepository.findByProviderAndProviderOrderId(
                    event.getProvider(), event.getProviderOrderId());
            if (byOrder.isPresent()) {
                return byOrder.get();
            }
        }
        if (event.getProviderPaymentId() != null && !event.getProviderPaymentId().isBlank()) {
            return attemptRepository.findByProviderAndProviderPaymentId(
                    event.getProvider(), event.getProviderPaymentId()).orElse(null);
        }
        return null;
    }

    private PaymentProcessingResult processPending(
            PaymentProviderEvent event,
            PaymentAttempt attempt,
            BookingStatus bookingStatus,
            Instant now) {
        if (attempt.getStatus() != PaymentStatus.INITIATING
                && attempt.getStatus() != PaymentStatus.PENDING) {
            event.ignore("STALE_PENDING_EVENT", now);
        } else {
            attempt.markPending(
                    event.getProviderStatus(), event.getProviderOccurredAt(), now);
            event.complete("PAYMENT_PENDING", now);
        }
        return new PaymentProcessingResult(
                attempt.getId(), attempt.getStatus(), attempt.getDisposition(),
                bookingStatus, false, event.getProcessingResult());
    }

    private PaymentProcessingResult processFailed(
            PaymentProviderEvent event,
            PaymentAttempt attempt,
            BookingStatus bookingStatus,
            Instant now) {
        if (attempt.getStatus() == PaymentStatus.SUCCEEDED) {
            event.ignore("SUCCESS_ALREADY_RECORDED", now);
        } else if (attempt.getStatus() == PaymentStatus.FAILED) {
            event.complete("DUPLICATE_FAILURE", now);
        } else if (attempt.getStatus() != PaymentStatus.INITIATING
                && attempt.getStatus() != PaymentStatus.PENDING) {
            event.ignore("STALE_FAILURE_EVENT", now);
        } else {
            attempt.markFailed(
                    event.getProviderStatus(),
                    event.getFailureCode(),
                    event.getProviderOccurredAt(),
                    now);
            event.complete("PAYMENT_FAILED", now);
            writeOutbox("PAYMENT_FAILED", attempt, event, now);
        }
        return new PaymentProcessingResult(
                attempt.getId(), attempt.getStatus(), attempt.getDisposition(),
                bookingStatus, false, event.getProcessingResult());
    }

    private PaymentProcessingResult processSuccess(
            PaymentProviderEvent event,
            PaymentAttempt attempt,
            BookingPaymentPort.PaymentBookingSnapshot booking,
            Instant now) {
        if (attempt.getStatus() == PaymentStatus.SUCCEEDED) {
            if (!sameSuccessfulPayment(event, attempt)) {
                event.requireReview("CONFLICTING_SUCCESS_REFERENCE", now);
            } else if (attempt.getDisposition() == PaymentDisposition.REQUIRES_RESOLUTION) {
                persistCompensationRefundIfEligible(attempt, attempt.getResolutionReason(), now);
                if (isLatePaymentCompensationReason(attempt.getResolutionReason())) {
                    event.complete("DUPLICATE_SUCCESS", now);
                } else {
                    event.requireReview(attempt.getResolutionReason(), now);
                }
            } else {
                event.complete("DUPLICATE_SUCCESS", now);
            }
            return new PaymentProcessingResult(
                    attempt.getId(), attempt.getStatus(), attempt.getDisposition(),
                    booking.status(),
                    event.getProcessingStatus() == ProviderEventProcessingStatus.REQUIRES_REVIEW,
                    event.getProcessingResult());
        }

        String resolutionReason = successResolutionReason(event, attempt, booking);
        PaymentDisposition disposition;
        BookingStatus finalBookingStatus = booking.status();
        if (resolutionReason == null) {
            finalBookingStatus = bookingPaymentPort.confirmLockedPendingPayment(booking.bookingId());
            disposition = PaymentDisposition.APPLIED_TO_BOOKING;
        } else {
            disposition = PaymentDisposition.REQUIRES_RESOLUTION;
        }

        attempt.markSucceeded(
                event.getProviderOrderId(),
                event.getProviderPaymentId(),
                event.getProviderStatus(),
                event.getAmount(),
                event.getProviderOccurredAt(),
                now,
                disposition,
                resolutionReason);

        writeOutbox("PAYMENT_SUCCEEDED", attempt, event, now);
        if (disposition == PaymentDisposition.APPLIED_TO_BOOKING) {
            // BOOKING_CONFIRMED is written atomically inside confirmLockedPendingPayment.
            event.complete("BOOKING_CONFIRMED", now);
        } else {
            writeOutbox("PAYMENT_REQUIRES_RESOLUTION", attempt, event, now);
            persistCompensationRefundIfEligible(attempt, resolutionReason, now);
            if (isLatePaymentCompensationReason(resolutionReason)) {
                event.complete("LATE_PAYMENT_COMPENSATION", now);
            } else {
                event.requireReview(resolutionReason, now);
            }
        }

        return new PaymentProcessingResult(
                attempt.getId(), attempt.getStatus(), attempt.getDisposition(),
                finalBookingStatus,
                disposition == PaymentDisposition.REQUIRES_RESOLUTION,
                event.getProcessingResult());
    }

    private PaymentProcessingResult processRefund(
            PaymentProviderEvent event,
            PaymentAttempt attempt,
            BookingStatus bookingStatus,
            Instant now,
            boolean succeeded) {
        Refund refund = resolveRefund(event, attempt);
        if (refund == null) {
            event.requireReview("UNKNOWN_REFUND_REFERENCE", now);
            return new PaymentProcessingResult(
                    attempt.getId(), attempt.getStatus(), attempt.getDisposition(),
                    bookingStatus, true, "UNKNOWN_REFUND_REFERENCE");
        }
        Refund locked = refundRepository.findByIdForUpdate(refund.getId()).orElse(refund);
        if (succeeded) {
            if (locked.getStatus() == RefundStatus.SUCCEEDED) {
                event.complete("DUPLICATE_REFUND", now);
                BookingStatus finalStatus = bookingPaymentPort.markRefunded(attempt.getBookingId());
                return new PaymentProcessingResult(
                        attempt.getId(), attempt.getStatus(), attempt.getDisposition(),
                        finalStatus, false, event.getProcessingResult());
            } else {
                String providerRefundId = locked.getProviderRefundId() != null
                        ? locked.getProviderRefundId()
                        : event.getProviderOrderId();
                if (providerRefundId == null) {
                    providerRefundId = event.getProviderEventId();
                }
                locked.markSucceeded(providerRefundId, event.getProviderStatus(), now);
                BookingStatus finalStatus = bookingPaymentPort.markRefunded(attempt.getBookingId());
                event.complete("REFUND_SUCCEEDED", now);
                writeOutbox("REFUND_SUCCEEDED", attempt, event, now);
                return new PaymentProcessingResult(
                        attempt.getId(), attempt.getStatus(), attempt.getDisposition(),
                        finalStatus, false, event.getProcessingResult());
            }
        } else if (locked.getStatus() == RefundStatus.SUCCEEDED) {
            event.ignore("REFUND_ALREADY_SUCCEEDED", now);
            BookingStatus finalStatus = bookingPaymentPort.markRefunded(attempt.getBookingId());
            return new PaymentProcessingResult(
                    attempt.getId(), attempt.getStatus(), attempt.getDisposition(),
                    finalStatus, false, event.getProcessingResult());
        } else {
            locked.markFailed(event.getProviderStatus(), event.getFailureCode(), now);
            event.complete("REFUND_FAILED", now);
        }
        return new PaymentProcessingResult(
                attempt.getId(), attempt.getStatus(), attempt.getDisposition(),
                bookingStatus, false, event.getProcessingResult());
    }

    private Refund resolveRefund(PaymentProviderEvent event, PaymentAttempt attempt) {
        if (event.getProviderOrderId() != null) {
            var byProviderId = refundRepository.findByProviderAndProviderRefundId(
                    event.getProvider(), event.getProviderOrderId());
            if (byProviderId.isPresent()) {
                return byProviderId.get();
            }
        }
        var open = refundRepository.findByPaymentAttemptIdAndStatusIn(
                attempt.getId(),
                List.of(RefundStatus.REQUESTED, RefundStatus.PROCESSING, RefundStatus.FAILED));
        if (open.size() == 1) {
            return open.get(0);
        }
        return open.stream()
                .filter(refund -> event.getAmount() == null || refund.getAmount().compareTo(event.getAmount()) == 0)
                .findFirst()
                .orElse(null);
    }

    private static boolean sameSuccessfulPayment(
            PaymentProviderEvent event, PaymentAttempt attempt) {
        return event.getProviderPaymentId() != null
                && event.getProviderPaymentId().equals(attempt.getProviderPaymentId())
                && event.getProviderOrderId() != null
                && event.getProviderOrderId().equals(attempt.getProviderOrderId())
                && event.getAmount() != null
                && attempt.getCapturedAmount() != null
                && event.getAmount().compareTo(attempt.getCapturedAmount()) == 0
                && event.getCurrency() != null
                && event.getCurrency().equalsIgnoreCase(attempt.getCurrency());
    }

    private static String successResolutionReason(
            PaymentProviderEvent event,
            PaymentAttempt attempt,
            BookingPaymentPort.PaymentBookingSnapshot booking) {
        if (event.getAmount() == null
                || event.getAmount().compareTo(attempt.getRequestedAmount()) != 0
                || event.getAmount().compareTo(booking.totalAmount()) != 0) {
            return "AMOUNT_MISMATCH";
        }
        if (event.getCurrency() == null
                || !event.getCurrency().equalsIgnoreCase(attempt.getCurrency())
                || !event.getCurrency().equalsIgnoreCase(booking.currency())) {
            return "CURRENCY_MISMATCH";
        }
        if (event.getProviderOrderId() == null || event.getProviderPaymentId() == null) {
            return "MISSING_PROVIDER_REFERENCE";
        }
        if (attempt.getProviderOrderId() != null
                && !attempt.getProviderOrderId().equals(event.getProviderOrderId())) {
            return "PROVIDER_ORDER_MISMATCH";
        }
        if (booking.status() == BookingStatus.EXPIRED) {
            return "BOOKING_EXPIRED";
        }
        if (booking.status() == BookingStatus.CANCELLED) {
            return "BOOKING_CANCELLED";
        }
        if (booking.status() == BookingStatus.CONFIRMED) {
            return "BOOKING_ALREADY_CONFIRMED";
        }
        if (booking.status() != BookingStatus.PENDING_PAYMENT) {
            return "BOOKING_NOT_PAYABLE";
        }
        Instant financialTime = event.getProviderOccurredAt() != null
                ? event.getProviderOccurredAt()
                : event.getReceivedAt();
        if (financialTime.isAfter(booking.paymentExpiresAt())) {
            return "PAYMENT_AFTER_DEADLINE";
        }
        return null;
    }

    /**
     * Late captured money against a non-travel-valid booking is refunded in full from the
     * persisted captured amount. The refund row is inserted in this payment transaction so a
     * crash before provider HTTP is recovered by the existing V19 worker. No provider call here.
     * <p>
     * Concurrent process() of the same attempt serializes on {@code payment_attempts FOR UPDATE}
     * (after the booking lock). Every refund insert for an attempt — this method,
     * {@code BookingCancellationService.persistRequestedRefund}, and {@code RefundWorker.reserve} —
     * takes that same payment row lock, so skip-if-any-refund cannot race. Unique
     * {@code (payment_attempt_id, idempotency_key)} with key {@code late-payment-{id}} is the
     * same-key fallback. No extra unique-on-attempt schema is required.
     */
    private void persistCompensationRefundIfEligible(
            PaymentAttempt attempt, String resolutionReason, Instant now) {
        if (!isLatePaymentCompensationReason(resolutionReason)
                || attempt.getCapturedAmount() == null
                || attempt.getCurrency() == null) {
            return;
        }
        if (!refundRepository.findByPaymentAttemptIdOrderByCreatedAtDesc(attempt.getId()).isEmpty()) {
            return;
        }
        String key = RefundApplicationService.compensationIdempotencyKey(attempt.getId());
        String fingerprint = RefundApplicationService.requestFingerprint(
                attempt.getId(), attempt.getCapturedAmount(), attempt.getCurrency());
        Refund refund = new Refund(
                attempt.getId(),
                attempt.getBookingId(),
                attempt.getProvider(),
                key,
                fingerprint,
                attempt.getCapturedAmount(),
                attempt.getCurrency(),
                RefundApplicationService.COMPENSATION_REASON,
                now);
        try {
            refundRepository.saveAndFlush(refund);
        } catch (DataIntegrityViolationException ignored) {
            // Concurrent duplicate success already inserted (payment_attempt_id, idempotency_key).
        }
    }

    static boolean isLatePaymentCompensationReason(String reason) {
        return "BOOKING_EXPIRED".equals(reason)
                || "BOOKING_CANCELLED".equals(reason)
                || "BOOKING_NOT_PAYABLE".equals(reason)
                || "PAYMENT_AFTER_DEADLINE".equals(reason);
    }

    private void writeOutbox(
            String eventType,
            PaymentAttempt attempt,
            PaymentProviderEvent event,
            Instant now) {
        outboxEventRepository.save(new OutboxEvent(
                eventType,
                eventType.startsWith("BOOKING_") ? "BOOKING" : "PAYMENT_ATTEMPT",
                eventType.startsWith("BOOKING_") ? attempt.getBookingId() : attempt.getId(),
                "{\"paymentAttemptId\":\"" + attempt.getId()
                        + "\",\"bookingId\":\"" + attempt.getBookingId() + "\"}",
                now,
                event.getProviderEventId(),
                event.getId().toString()));
    }

    private static boolean isTerminal(ProviderEventProcessingStatus status) {
        return status == ProviderEventProcessingStatus.PROCESSED
                || status == ProviderEventProcessingStatus.IGNORED
                || status == ProviderEventProcessingStatus.REQUIRES_REVIEW;
    }

    private static PaymentProcessingResult resultFromStored(PaymentProviderEvent event) {
        return new PaymentProcessingResult(
                event.getPaymentAttemptId(),
                null,
                null,
                null,
                event.getProcessingStatus() == ProviderEventProcessingStatus.REQUIRES_REVIEW,
                event.getProcessingResult());
    }

    public record PaymentProcessingResult(
            UUID paymentAttemptId,
            PaymentStatus paymentStatus,
            PaymentDisposition disposition,
            BookingStatus bookingStatus,
            boolean requiresResolution,
            String result) {
    }
}
