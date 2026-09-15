package in.bluebustickets.bluebus.payments.provider.razorpay;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import in.bluebustickets.bluebus.payments.provider.PaymentProviderUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HTTP boundary for Razorpay Orders and Refunds. Provider DTOs stay here.
 */
class RazorpayApiClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(RazorpayApiClient.class);

    private final RazorpayProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    RazorpayApiClient(RazorpayProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .build();
    }

    OrderCreated createOrder(
            UUID paymentAttemptId,
            String merchantReference,
            BigDecimal amount,
            String currency) {
        long paise = RazorpayMoney.toMinorUnits(amount, currency);
        ObjectNode body = objectMapper.createObjectNode();
        body.put("amount", paise);
        body.put("currency", "INR");
        body.put("receipt", receipt(merchantReference, paymentAttemptId));
        ObjectNode notes = body.putObject("notes");
        notes.put("payment_attempt_id", paymentAttemptId.toString());
        notes.put("merchant_reference", merchantReference);

        JsonNode response = post(
                "/v1/orders",
                body,
                paymentAttemptId.toString());
        String orderId = text(response, "id");
        if (orderId == null) {
            LOGGER.warn("Razorpay order response was missing an order id.");
            throw new PaymentProviderUnavailableException("Payment provider is temporarily unavailable.");
        }
        return new OrderCreated(orderId, text(response, "status"));
    }

    RefundCreated createRefund(
            UUID refundId,
            String providerPaymentId,
            BigDecimal amount,
            String currency,
            String idempotencyKey) {
        long paise = RazorpayMoney.toMinorUnits(amount, currency);
        ObjectNode body = objectMapper.createObjectNode();
        body.put("amount", paise);
        JsonNode response = post(
                "/v1/payments/" + providerPaymentId + "/refunds",
                body,
                idempotencyKey);
        String refundProviderId = text(response, "id");
        if (refundProviderId == null) {
            LOGGER.warn("Razorpay refund response was missing a refund id. refundId={}", refundId);
            throw new PaymentProviderUnavailableException("Payment provider is temporarily unavailable.");
        }
        return new RefundCreated(refundProviderId, text(response, "status"));
    }

    private JsonNode post(String path, ObjectNode body, String idempotencyKey) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(properties.getBaseUrl() + path))
                    .timeout(properties.getReadTimeout())
                    .header("Authorization", basicAuth())
                    .header("Content-Type", "application/json")
                    .header("X-Razorpay-Idempotency-Key", idempotencyKey)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                return parseJson(response.body());
            }
            LOGGER.warn("Razorpay HTTP {} for {}", status, path);
            throw new PaymentProviderUnavailableException("Payment provider is temporarily unavailable.");
        } catch (PaymentProviderUnavailableException exception) {
            throw exception;
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOGGER.warn("Razorpay connection failed for {}", path);
            throw new PaymentProviderUnavailableException("Payment provider is temporarily unavailable.");
        } catch (Exception exception) {
            LOGGER.warn("Razorpay request failed for {}", path);
            throw new PaymentProviderUnavailableException("Payment provider is temporarily unavailable.");
        }
    }

    private JsonNode parseJson(String body) {
        try {
            return objectMapper.readTree(body == null ? "{}" : body);
        } catch (Exception exception) {
            LOGGER.warn("Razorpay returned a malformed JSON body.");
            throw new PaymentProviderUnavailableException("Payment provider is temporarily unavailable.");
        }
    }

    private String basicAuth() {
        String token = properties.getKeyId() + ":" + properties.getKeySecret();
        return "Basic " + Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8));
    }

    private static String receipt(String merchantReference, UUID paymentAttemptId) {
        String candidate = merchantReference == null ? "" : merchantReference.replace("-", "");
        if (candidate.length() > 40 || candidate.isBlank()) {
            return paymentAttemptId.toString().replace("-", "");
        }
        return candidate;
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) {
            return null;
        }
        String value = node.get(field).asText();
        return value == null || value.isBlank() ? null : value.trim();
    }

    record OrderCreated(String orderId, String status) {
    }

    record RefundCreated(String refundId, String status) {
    }
}
