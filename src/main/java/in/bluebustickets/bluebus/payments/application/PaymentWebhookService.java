package in.bluebustickets.bluebus.payments.application;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import in.bluebustickets.bluebus.payments.api.dto.WebhookReceiptResponse;
import in.bluebustickets.bluebus.payments.provider.PaymentProvider;
import in.bluebustickets.bluebus.payments.provider.PaymentProviderRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;

/**
 * Provider webhook ingress. Designed for Razorpay's ~5s acknowledgement window:
 * verify raw-body signature → validate provider event id → durable inbox insert →
 * bounded local payment-state-machine DB work → 2xx. This path never performs outbound
 * provider HTTP (Orders/Refunds). Provider network calls stay on initiate/refund/checkout flows.
 */
@Service
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class PaymentWebhookService {

    private final PaymentProviderRegistry providerRegistry;
    private final ProviderEventIngressService ingressService;
    private final VerifiedPaymentEventProcessor eventProcessor;
    private final Clock clock;

    public PaymentWebhookService(
            PaymentProviderRegistry providerRegistry,
            ProviderEventIngressService ingressService,
            VerifiedPaymentEventProcessor eventProcessor,
            Clock clock) {
        this.providerRegistry = providerRegistry;
        this.ingressService = ingressService;
        this.eventProcessor = eventProcessor;
        this.clock = clock;
    }

    public WebhookReceiptResponse receive(
            String providerCode,
            byte[] rawBody,
            Map<String, List<String>> headers) {
        PaymentProvider provider = providerRegistry.require(providerCode);
        // Local only: HMAC over raw bytes + normalize. No provider API calls.
        PaymentProvider.WebhookVerificationResult verification =
                provider.verifyAndNormalize(rawBody, headers);
        if (!verification.verified() || verification.event() == null) {
            throw new BadCredentialsException("Invalid provider signature.");
        }

        String normalizedProvider = provider.providerCode().trim().toUpperCase(Locale.ROOT);
        var ingress = ingressService.record(
                normalizedProvider,
                verification.event(),
                clock.instant(),
                sha256Hex(rawBody));
        // Local DB state machine only. Duplicate deliveries re-enter process so a crash
        // after insert but before process is recovered without a second Razorpay call.
        var result = eventProcessor.process(ingress.eventId());
        return new WebhookReceiptResponse(true, ingress.duplicate(), result.result());
    }

    private static String sha256Hex(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }
}
