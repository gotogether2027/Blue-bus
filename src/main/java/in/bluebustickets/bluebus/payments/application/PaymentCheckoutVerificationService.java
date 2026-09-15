package in.bluebustickets.bluebus.payments.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.UUID;

import in.bluebustickets.bluebus.foundation.api.error.ApplicationConflictException;
import in.bluebustickets.bluebus.foundation.api.error.ResourceNotFoundException;
import in.bluebustickets.bluebus.payments.api.dto.PaymentResponse;
import in.bluebustickets.bluebus.payments.domain.PaymentAttempt;
import in.bluebustickets.bluebus.payments.provider.PaymentProvider;
import in.bluebustickets.bluebus.payments.provider.PaymentProviderRegistry;
import in.bluebustickets.bluebus.payments.repository.PaymentAttemptRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class PaymentCheckoutVerificationService {

    private final PaymentAttemptRepository paymentAttemptRepository;
    private final PaymentProviderRegistry providerRegistry;
    private final ProviderEventIngressService ingressService;
    private final VerifiedPaymentEventProcessor eventProcessor;
    private final Clock clock;

    public PaymentCheckoutVerificationService(
            PaymentAttemptRepository paymentAttemptRepository,
            PaymentProviderRegistry providerRegistry,
            ProviderEventIngressService ingressService,
            VerifiedPaymentEventProcessor eventProcessor,
            Clock clock) {
        this.paymentAttemptRepository = paymentAttemptRepository;
        this.providerRegistry = providerRegistry;
        this.ingressService = ingressService;
        this.eventProcessor = eventProcessor;
        this.clock = clock;
    }

    public PaymentResponse verify(
            UUID userId,
            UUID paymentAttemptId,
            String presentedOrderId,
            String providerPaymentId,
            String signature) {
        PaymentAttempt attempt = paymentAttemptRepository.findById(paymentAttemptId).orElse(null);
        if (attempt == null || !attempt.getUserId().equals(userId)) {
            throw new ResourceNotFoundException("Payment attempt was not found.");
        }
        if (attempt.getProviderOrderId() == null || attempt.getProviderOrderId().isBlank()) {
            throw new ApplicationConflictException("Payment has not been initiated with the provider.");
        }
        PaymentProvider provider = providerRegistry.require(attempt.getProvider());
        PaymentProvider.WebhookVerificationResult verification = provider.verifyCheckout(
                new PaymentProvider.CheckoutVerificationCommand(
                        attempt.getProviderOrderId(),
                        presentedOrderId,
                        providerPaymentId,
                        signature,
                        attempt.getMerchantReference(),
                        attempt.getRequestedAmount(),
                        attempt.getCurrency()));
        if (!verification.verified() || verification.event() == null) {
            throw new BadCredentialsException("Invalid provider signature.");
        }
        var ingress = ingressService.record(
                provider.providerCode(),
                verification.event(),
                clock.instant(),
                sha256Hex(rawFingerprint(attempt, providerPaymentId, signature)));
        eventProcessor.process(ingress.eventId());
        return PaymentInitiationService.toPaymentResponse(
                paymentAttemptRepository.findById(paymentAttemptId)
                        .orElseThrow(() -> new ResourceNotFoundException("Payment attempt was not found.")));
    }

    private static byte[] rawFingerprint(PaymentAttempt attempt, String paymentId, String signature) {
        return (attempt.getId() + "|" + attempt.getProviderOrderId() + "|" + paymentId + "|" + signature)
                .getBytes(StandardCharsets.UTF_8);
    }

    private static String sha256Hex(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }
}
