package in.bluebustickets.bluebus.payments.application;

import java.time.Instant;
import java.util.UUID;

import in.bluebustickets.bluebus.foundation.outbox.OutboxEvent;
import in.bluebustickets.bluebus.foundation.outbox.OutboxEventRepository;
import in.bluebustickets.bluebus.payments.domain.Refund;
import in.bluebustickets.bluebus.payments.domain.RefundStatus;

public final class RefundOutboxWriter {

    static final String REFUND_REQUESTED = "REFUND_REQUESTED";
    static final String REFUND_FAILED = "REFUND_FAILED";
    static final String REFUND_SUCCEEDED = "REFUND_SUCCEEDED";

    private RefundOutboxWriter() {
    }

    /**
     * {@code FAILED} stays retryable while {@code provider_refund_id} is null.
     * A customer notification is emitted only after the worker will not retry.
     */
    public static boolean isTerminalFailure(Refund refund) {
        return refund != null
                && refund.getStatus() == RefundStatus.FAILED
                && !RefundRetryProcessor.isRetryable(refund);
    }

    public static void writeRequested(OutboxEventRepository outboxEventRepository, Refund refund, Instant now) {
        outboxEventRepository.insertRefundRequestedIfAbsent(
                UUID.randomUUID(),
                refund.getId(),
                refund.getIdempotencyKey(),
                refund.getId().toString(),
                payload(refund),
                now,
                now);
    }

    public static void writeFailed(OutboxEventRepository outboxEventRepository, Refund refund, Instant now) {
        if (!isTerminalFailure(refund)) {
            return;
        }
        outboxEventRepository.insertRefundFailedIfAbsent(
                UUID.randomUUID(),
                refund.getId(),
                refund.getIdempotencyKey(),
                refund.getId().toString(),
                payload(refund),
                now,
                now);
    }

    public static void writeSucceeded(OutboxEventRepository outboxEventRepository, Refund refund, Instant now) {
        outboxEventRepository.save(new OutboxEvent(
                REFUND_SUCCEEDED,
                "PAYMENT_ATTEMPT",
                refund.getPaymentAttemptId(),
                "{\"refundId\":\"" + refund.getId()
                        + "\",\"paymentAttemptId\":\"" + refund.getPaymentAttemptId() + "\"}",
                now,
                refund.getIdempotencyKey(),
                refund.getId().toString()));
    }

    private static String payload(Refund refund) {
        return "{\"refundId\":\"" + refund.getId()
                + "\",\"paymentAttemptId\":\"" + refund.getPaymentAttemptId()
                + "\",\"bookingId\":\"" + refund.getBookingId() + "\"}";
    }
}
