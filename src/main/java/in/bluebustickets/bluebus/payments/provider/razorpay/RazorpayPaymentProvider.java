package in.bluebustickets.bluebus.payments.provider.razorpay;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.bluebustickets.bluebus.payments.provider.PaymentProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Razorpay adapter. Translates Razorpay HTTP/signatures into the provider-neutral payment model.
 */
public class RazorpayPaymentProvider implements PaymentProvider {

    static final String PROVIDER_CODE = "RAZORPAY";

    private static final Logger LOGGER = LoggerFactory.getLogger(RazorpayPaymentProvider.class);

    private final RazorpayProperties properties;
    private final RazorpayApiClient apiClient;
    private final ObjectMapper objectMapper;

    public RazorpayPaymentProvider(
            RazorpayProperties properties,
            RazorpayApiClient apiClient,
            ObjectMapper objectMapper) {
        this.properties = properties;
        this.apiClient = apiClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public String providerCode() {
        return PROVIDER_CODE;
    }

    @Override
    public ProviderInitiationResult initiate(ProviderInitiationCommand command) {
        RazorpayApiClient.OrderCreated order = apiClient.createOrder(
                command.paymentAttemptId(),
                command.merchantReference(),
                command.amount(),
                command.currency());
        return new ProviderInitiationResult(
                order.orderId(),
                order.status() == null ? "created" : order.status(),
                properties.getKeyId());
    }

    @Override
    public WebhookVerificationResult verifyCheckout(CheckoutVerificationCommand command) {
        if (command == null
                || isBlank(command.storedProviderOrderId())
                || isBlank(command.presentedProviderOrderId())
                || isBlank(command.providerPaymentId())
                || isBlank(command.signature())) {
            return WebhookVerificationResult.invalid();
        }
        if (!command.storedProviderOrderId().equals(command.presentedProviderOrderId())) {
            return WebhookVerificationResult.invalid();
        }
        String payload = command.storedProviderOrderId() + "|" + command.providerPaymentId();
        if (!RazorpaySignatures.matches(payload, properties.getKeySecret(), command.signature())) {
            return WebhookVerificationResult.invalid();
        }
        return WebhookVerificationResult.verified(new VerifiedProviderEvent(
                "checkout:" + command.providerPaymentId(),
                ProviderEventType.PAYMENT_SUCCEEDED,
                command.merchantReference(),
                command.storedProviderOrderId(),
                command.providerPaymentId(),
                command.amount(),
                command.currency() == null ? "INR" : command.currency().toUpperCase(Locale.ROOT),
                "captured",
                null,
                Instant.now()));
    }

    @Override
    public ProviderRefundResult refund(ProviderRefundCommand command) {
        RazorpayApiClient.RefundCreated refund = apiClient.createRefund(
                command.refundId(),
                command.providerPaymentId(),
                command.amount(),
                command.currency(),
                command.idempotencyKey());
        return new ProviderRefundResult(
                refund.refundId(),
                refund.status() == null ? "processed" : refund.status());
    }

    /**
     * Local-only webhook verification. HMAC over the raw body, require event id, normalize.
     * Must never call {@link RazorpayApiClient} — Razorpay expects a prompt 2xx acknowledgement.
     */
    @Override
    public WebhookVerificationResult verifyAndNormalize(byte[] rawBody, Map<String, List<String>> headers) {
        if (rawBody == null || rawBody.length == 0) {
            return WebhookVerificationResult.invalid();
        }
        String signature = firstHeader(headers, "X-Razorpay-Signature");
        if (!RazorpaySignatures.matches(rawBody, properties.getWebhookSecret(), signature)) {
            return WebhookVerificationResult.invalid();
        }
        try {
            JsonNode body = objectMapper.readTree(rawBody);
            String eventId = firstHeader(headers, "X-Razorpay-Event-Id");
            if (isBlank(eventId)) {
                eventId = text(body, "id");
            }
            if (isBlank(eventId)) {
                LOGGER.warn("Verified Razorpay webhook was missing an event id.");
                return WebhookVerificationResult.invalid();
            }
            return WebhookVerificationResult.verified(normalize(eventId, body));
        } catch (Exception exception) {
            LOGGER.warn("Verified Razorpay webhook body could not be parsed.");
            return WebhookVerificationResult.invalid();
        }
    }

    private VerifiedProviderEvent normalize(String eventId, JsonNode body) {
        String eventName = text(body, "event");
        ProviderEventType type = mapEvent(eventName);
        JsonNode payment = entity(body, "payment");
        JsonNode order = entity(body, "order");
        JsonNode refund = entity(body, "refund");
        JsonNode source = payment != null ? payment : (refund != null ? refund : order);

        String orderId = firstNonBlank(text(payment, "order_id"), text(order, "id"), text(refund, "order_id"));
        String paymentId = firstNonBlank(text(payment, "id"), text(refund, "payment_id"));
        String merchantReference = firstNonBlank(
                note(payment, "merchant_reference"),
                note(order, "merchant_reference"),
                note(refund, "merchant_reference"));
        BigDecimal amount = amountFrom(source != null ? source : payment, eventName);
        String currency = firstNonBlank(text(source, "currency"), text(payment, "currency"), "INR");
        String providerStatus = firstNonBlank(text(source, "status"), text(payment, "status"));
        String failureCode = firstNonBlank(text(payment, "error_code"), text(refund, "error_code"));
        Instant occurredAt = occurredAt(body);

        if (type == ProviderEventType.REFUND_SUCCEEDED || type == ProviderEventType.REFUND_FAILED) {
            paymentId = firstNonBlank(text(refund, "payment_id"), paymentId);
            orderId = firstNonBlank(text(refund, "id"), orderId);
        }

        return new VerifiedProviderEvent(
                eventId,
                type,
                merchantReference,
                orderId,
                paymentId,
                amount,
                currency == null ? null : currency.toUpperCase(Locale.ROOT),
                providerStatus,
                failureCode,
                occurredAt);
    }

    private static ProviderEventType mapEvent(String eventName) {
        if (eventName == null) {
            return ProviderEventType.UNKNOWN;
        }
        return switch (eventName) {
            // Captured money / paid order only — never treat authorization alone as success.
            case "payment.captured", "order.paid" -> ProviderEventType.PAYMENT_SUCCEEDED;
            case "payment.failed" -> ProviderEventType.PAYMENT_FAILED;
            case "payment.authorized" -> ProviderEventType.PAYMENT_PENDING;
            case "refund.processed" -> ProviderEventType.REFUND_SUCCEEDED;
            case "refund.failed" -> ProviderEventType.REFUND_FAILED;
            default -> ProviderEventType.UNKNOWN;
        };
    }

    private static JsonNode entity(JsonNode body, String name) {
        JsonNode payload = body.path("payload").path(name).path("entity");
        return payload.isMissingNode() || payload.isNull() ? null : payload;
    }

    private static String note(JsonNode entity, String key) {
        if (entity == null) {
            return null;
        }
        return text(entity.path("notes"), key);
    }

    private static BigDecimal amountFrom(JsonNode entity, String eventName) {
        if (entity == null || !entity.hasNonNull("amount") || !entity.get("amount").canConvertToLong()) {
            return null;
        }
        try {
            String currency = firstNonBlank(text(entity, "currency"), "INR");
            return RazorpayMoney.fromMinorUnits(entity.get("amount").asLong(), currency);
        } catch (IllegalArgumentException exception) {
            LOGGER.warn("Razorpay event {} had an amount that could not be converted.", eventName);
            return null;
        }
    }

    private static Instant occurredAt(JsonNode body) {
        if (body.hasNonNull("created_at") && body.get("created_at").canConvertToLong()) {
            return Instant.ofEpochSecond(body.get("created_at").asLong());
        }
        JsonNode payment = entity(body, "payment");
        if (payment != null && payment.hasNonNull("created_at") && payment.get("created_at").canConvertToLong()) {
            return Instant.ofEpochSecond(payment.get("created_at").asLong());
        }
        return null;
    }

    private static String firstHeader(Map<String, List<String>> headers, String name) {
        if (headers == null || name == null) {
            return null;
        }
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name) && entry.getValue() != null) {
                for (String value : entry.getValue()) {
                    if (value != null && !value.isBlank()) {
                        return value.trim();
                    }
                }
            }
        }
        return null;
    }

    private static String text(JsonNode node, String field) {
        if (node == null || field == null || !node.hasNonNull(field)) {
            return null;
        }
        String value = node.get(field).asText();
        return isBlank(value) ? null : value.trim();
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (!isBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
