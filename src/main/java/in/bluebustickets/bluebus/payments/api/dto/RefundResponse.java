package in.bluebustickets.bluebus.payments.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import in.bluebustickets.bluebus.payments.domain.RefundStatus;

public record RefundResponse(
        UUID refundId,
        UUID paymentAttemptId,
        UUID bookingId,
        String provider,
        String providerRefundId,
        BigDecimal amount,
        String currency,
        String reason,
        RefundStatus status,
        Instant requestedAt,
        Instant processedAt) {
}
