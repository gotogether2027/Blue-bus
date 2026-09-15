package in.bluebustickets.bluebus.payments.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import in.bluebustickets.bluebus.foundation.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

@Entity
@Table(name = "payment_provider_events")
public class PaymentProviderEvent extends AuditableEntity {

    @Column(nullable = false, length = 50, updatable = false)
    private String provider;

    @Column(name = "provider_event_id", nullable = false, length = 150, updatable = false)
    private String providerEventId;

    @Column(name = "event_type", nullable = false, length = 100, updatable = false)
    private String eventType;

    @Column(name = "payment_attempt_id")
    private UUID paymentAttemptId;

    @Column(name = "merchant_reference", length = 100, updatable = false)
    private String merchantReference;

    @Column(name = "provider_order_id", length = 150, updatable = false)
    private String providerOrderId;

    @Column(name = "provider_payment_id", length = 150, updatable = false)
    private String providerPaymentId;

    @Column(precision = 12, scale = 2, updatable = false)
    private BigDecimal amount;

    @Column(length = 3, updatable = false)
    private String currency;

    @Column(name = "provider_status", length = 100, updatable = false)
    private String providerStatus;

    @Column(name = "failure_code", length = 100, updatable = false)
    private String failureCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "processing_status", nullable = false, length = 30)
    private ProviderEventProcessingStatus processingStatus;

    @Column(name = "signature_verified", nullable = false, updatable = false)
    private boolean signatureVerified;

    @Column(name = "provider_occurred_at", updatable = false)
    private Instant providerOccurredAt;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(name = "payload_hash", nullable = false, length = 64, updatable = false)
    private String payloadHash;

    @Column(name = "processing_result", length = 100)
    private String processingResult;

    @Column(name = "last_error_code", length = 100)
    private String lastErrorCode;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    protected PaymentProviderEvent() {
    }

    public PaymentProviderEvent(
            String provider,
            String providerEventId,
            String eventType,
            String merchantReference,
            String providerOrderId,
            String providerPaymentId,
            BigDecimal amount,
            String currency,
            String providerStatus,
            String failureCode,
            Instant providerOccurredAt,
            Instant receivedAt,
            String payloadHash) {
        this.provider = requireText(provider, "provider");
        this.providerEventId = requireText(providerEventId, "providerEventId");
        this.eventType = requireText(eventType, "eventType");
        this.merchantReference = normalize(merchantReference);
        this.providerOrderId = normalize(providerOrderId);
        this.providerPaymentId = normalize(providerPaymentId);
        this.amount = amount;
        this.currency = normalize(currency);
        this.providerStatus = normalize(providerStatus);
        this.failureCode = normalize(failureCode);
        this.providerOccurredAt = providerOccurredAt;
        this.receivedAt = receivedAt;
        this.payloadHash = requireText(payloadHash, "payloadHash");
        this.signatureVerified = true;
        this.processingStatus = ProviderEventProcessingStatus.RECEIVED;
    }

    public void startProcessing(UUID attemptId) {
        this.paymentAttemptId = attemptId;
        this.processingStatus = ProviderEventProcessingStatus.PROCESSING;
        this.attemptCount++;
    }

    public void complete(String result, Instant now) {
        this.processingStatus = ProviderEventProcessingStatus.PROCESSED;
        this.processingResult = normalize(result);
        this.processedAt = now;
        this.lastErrorCode = null;
    }

    public void requireReview(String result, Instant now) {
        this.processingStatus = ProviderEventProcessingStatus.REQUIRES_REVIEW;
        this.processingResult = normalize(result);
        this.processedAt = now;
    }

    public void ignore(String result, Instant now) {
        this.processingStatus = ProviderEventProcessingStatus.IGNORED;
        this.processingResult = normalize(result);
        this.processedAt = now;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public String getProvider() { return provider; }
    public String getProviderEventId() { return providerEventId; }
    public String getEventType() { return eventType; }
    public UUID getPaymentAttemptId() { return paymentAttemptId; }
    public String getMerchantReference() { return merchantReference; }
    public String getProviderOrderId() { return providerOrderId; }
    public String getProviderPaymentId() { return providerPaymentId; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getProviderStatus() { return providerStatus; }
    public String getFailureCode() { return failureCode; }
    public ProviderEventProcessingStatus getProcessingStatus() { return processingStatus; }
    public boolean isSignatureVerified() { return signatureVerified; }
    public Instant getProviderOccurredAt() { return providerOccurredAt; }
    public Instant getReceivedAt() { return receivedAt; }
    public Instant getProcessedAt() { return processedAt; }
    public String getPayloadHash() { return payloadHash; }
    public String getProcessingResult() { return processingResult; }
    public String getLastErrorCode() { return lastErrorCode; }
    public int getAttemptCount() { return attemptCount; }
}
