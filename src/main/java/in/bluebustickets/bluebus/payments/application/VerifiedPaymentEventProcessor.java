package in.bluebustickets.bluebus.payments.application;

import java.time.Clock;
import java.time.Instant;
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
import in.bluebustickets.bluebus.payments.provider.PaymentProvider.ProviderEventType;
import in.bluebustickets.bluebus.payments.repository.PaymentAttemptRepository;
import in.bluebustickets.bluebus.payments.repository.PaymentProviderEventRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class VerifiedPaymentEventProcessor {

    private final PaymentProviderEventRepository eventRepository;
    private final PaymentAttemptRepository attemptRepository;
    private final BookingPaymentPort bookingPaymentPort;
    private final OutboxEventRepository outboxEventRepository;
    private final Clock clock;

    public VerifiedPaymentEventProcessor(
            PaymentProviderEventRepository eventRepository,
            PaymentAttemptRepository attemptRepository,
            BookingPaymentPort bookingPaymentPort,
            OutboxEventRepository outboxEventRepository,
            Clock clock) {
        this.eventRepository = eventRepository;
        this.attemptRepository = attemptRepository;
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

        PaymentAttempt candidate = attemptRepository
                .findByProviderAndMerchantReference(event.getProvider(), event.getMerchantReference())
                .orElse(null);
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
            case UNKNOWN -> {
                event.ignore("UNSUPPORTED_EVENT_TYPE", now);
                yield new PaymentProcessingResult(
                        attempt.getId(), attempt.getStatus(), attempt.getDisposition(),
                        booking.status(), false, "UNSUPPORTED_EVENT_TYPE");
            }
        };
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
                event.requireReview(attempt.getResolutionReason(), now);
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
            writeOutbox("BOOKING_CONFIRMED", attempt, event, now);
            event.complete("BOOKING_CONFIRMED", now);
        } else {
            writeOutbox("PAYMENT_REQUIRES_RESOLUTION", attempt, event, now);
            event.requireReview(resolutionReason, now);
        }

        return new PaymentProcessingResult(
                attempt.getId(), attempt.getStatus(), attempt.getDisposition(),
                finalBookingStatus,
                disposition == PaymentDisposition.REQUIRES_RESOLUTION,
                event.getProcessingResult());
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
