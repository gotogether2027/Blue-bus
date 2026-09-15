package in.bluebustickets.bluebus.payments.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import in.bluebustickets.bluebus.payments.domain.PaymentDisposition;
import in.bluebustickets.bluebus.payments.domain.PaymentStatus;

public record PaymentResponse(
        UUID paymentAttemptId,
        UUID bookingId,
        String provider,
        String merchantReference,
        String providerOrderId,
        BigDecimal requestedAmount,
        BigDecimal capturedAmount,
        String currency,
        PaymentStatus status,
        PaymentDisposition disposition,
        Instant createdAt,
        Instant processedAt) {
}
