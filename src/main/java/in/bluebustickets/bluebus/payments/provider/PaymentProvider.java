package in.bluebustickets.bluebus.payments.provider;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Provider-neutral boundary. Implementations own provider DTOs, network calls, status mapping,
 * and signature rules. No real provider implementation is included in this phase.
 */
public interface PaymentProvider {

    String providerCode();

    ProviderInitiationResult initiate(ProviderInitiationCommand command);

    WebhookVerificationResult verifyAndNormalize(byte[] rawBody, Map<String, List<String>> headers);

    record ProviderInitiationCommand(
            UUID paymentAttemptId,
            String merchantReference,
            BigDecimal amount,
            String currency,
            Instant paymentExpiresAt) {
    }

    record ProviderInitiationResult(
            String providerOrderId,
            String providerStatus,
            String checkoutReference) {
    }

    record WebhookVerificationResult(boolean verified, VerifiedProviderEvent event) {
        public static WebhookVerificationResult invalid() {
            return new WebhookVerificationResult(false, null);
        }

        public static WebhookVerificationResult verified(VerifiedProviderEvent event) {
            if (event == null) {
                throw new IllegalArgumentException("Verified event is required");
            }
            return new WebhookVerificationResult(true, event);
        }
    }

    record VerifiedProviderEvent(
            String providerEventId,
            ProviderEventType eventType,
            String merchantReference,
            String providerOrderId,
            String providerPaymentId,
            BigDecimal amount,
            String currency,
            String providerStatus,
            String failureCode,
            Instant providerOccurredAt) {
    }

    enum ProviderEventType {
        PAYMENT_PENDING,
        PAYMENT_FAILED,
        PAYMENT_SUCCEEDED,
        UNKNOWN
    }
}
