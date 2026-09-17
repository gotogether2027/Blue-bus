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
@Table(name = "refunds")
public class Refund extends AuditableEntity {

    @Column(name = "payment_attempt_id", nullable = false, updatable = false)
    private UUID paymentAttemptId;

    @Column(name = "booking_id", nullable = false, updatable = false)
    private UUID bookingId;

    @Column(nullable = false, length = 50, updatable = false)
    private String provider;

    @Column(name = "provider_refund_id", length = 150)
    private String providerRefundId;

    @Column(name = "idempotency_key", nullable = false, length = 100, updatable = false)
    private String idempotencyKey;

    @Column(name = "request_fingerprint", nullable = false, length = 128, updatable = false)
    private String requestFingerprint;

    @Column(nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal amount;

    @Column(nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(nullable = false, length = 100, updatable = false)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private RefundStatus status;

    @Column(name = "provider_status", length = 100)
    private String providerStatus;

    @Column(name = "failure_code", length = 100)
    private String failureCode;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(nullable = false)
    private int version = 1;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 0;

    @Column(name = "next_retry_at")
    private Instant nextRetryAt;

    protected Refund() {
    }

    public Refund(
            UUID paymentAttemptId,
            UUID bookingId,
            String provider,
            String idempotencyKey,
            String requestFingerprint,
            BigDecimal amount,
            String currency,
            String reason,
            Instant requestedAt) {
        if (paymentAttemptId == null || bookingId == null || amount == null || requestedAt == null) {
            throw new IllegalArgumentException("payment, booking, amount, and requestedAt are required");
        }
        this.paymentAttemptId = paymentAttemptId;
        this.bookingId = bookingId;
        this.provider = requireText(provider, "provider");
        this.idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
        this.requestFingerprint = requireText(requestFingerprint, "requestFingerprint");
        this.amount = amount;
        this.currency = requireText(currency, "currency").toUpperCase();
        this.reason = requireText(reason, "reason");
        this.requestedAt = requestedAt;
        this.status = RefundStatus.REQUESTED;
    }

    public void markProcessing(String providerRefundId, String providerStatus) {
        if (status == RefundStatus.SUCCEEDED) {
            return;
        }
        if (status != RefundStatus.REQUESTED
                && status != RefundStatus.PROCESSING
                && status != RefundStatus.FAILED) {
            return;
        }
        if (providerRefundId != null && !providerRefundId.isBlank()) {
            this.providerRefundId = providerRefundId.trim();
        }
        this.providerStatus = normalize(providerStatus);
        this.status = RefundStatus.PROCESSING;
        this.version++;
    }

    public void markSucceeded(String providerRefundId, String providerStatus, Instant processedAt) {
        if (status == RefundStatus.SUCCEEDED) {
            if (this.providerRefundId == null && providerRefundId != null) {
                this.providerRefundId = providerRefundId.trim();
            }
            return;
        }
        if (status != RefundStatus.REQUESTED
                && status != RefundStatus.PROCESSING
                && status != RefundStatus.FAILED) {
            return;
        }
        this.providerRefundId = requireText(providerRefundId, "providerRefundId");
        this.providerStatus = normalize(providerStatus);
        this.processedAt = processedAt;
        this.failureCode = null;
        this.status = RefundStatus.SUCCEEDED;
        this.version++;
    }

    public void markFailed(String providerStatus, String failureCode, Instant processedAt) {
        if (status == RefundStatus.SUCCEEDED) {
            return;
        }
        if (status != RefundStatus.REQUESTED && status != RefundStatus.PROCESSING && status != RefundStatus.FAILED) {
            return;
        }
        this.providerStatus = normalize(providerStatus);
        this.failureCode = normalize(failureCode);
        this.processedAt = processedAt;
        this.status = RefundStatus.FAILED;
        this.version++;
    }

    /**
     * Worker claim/lease: increments {@code attemptCount} and defers the next due time.
     * FAILED remains retryable while {@code providerRefundId} is null.
     */
    public void claimForRetry(Instant nextRetryAt) {
        if (nextRetryAt == null) {
            throw new IllegalArgumentException("nextRetryAt is required");
        }
        this.attemptCount++;
        this.nextRetryAt = nextRetryAt;
        this.version++;
    }

    public void scheduleRetry(Instant nextRetryAt) {
        if (nextRetryAt == null) {
            throw new IllegalArgumentException("nextRetryAt is required");
        }
        this.nextRetryAt = nextRetryAt;
        this.version++;
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

    public UUID getPaymentAttemptId() { return paymentAttemptId; }
    public UUID getBookingId() { return bookingId; }
    public String getProvider() { return provider; }
    public String getProviderRefundId() { return providerRefundId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getRequestFingerprint() { return requestFingerprint; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getReason() { return reason; }
    public RefundStatus getStatus() { return status; }
    public String getProviderStatus() { return providerStatus; }
    public String getFailureCode() { return failureCode; }
    public Instant getRequestedAt() { return requestedAt; }
    public Instant getProcessedAt() { return processedAt; }
    public int getVersion() { return version; }
    public int getAttemptCount() { return attemptCount; }
    public Instant getNextRetryAt() { return nextRetryAt; }
}
