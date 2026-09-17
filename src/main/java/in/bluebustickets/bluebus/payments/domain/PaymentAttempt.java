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
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Entity
@Table(name = "payment_attempts")
public class PaymentAttempt extends AuditableEntity {

    @NotNull
    @Column(name = "booking_id", nullable = false, updatable = false)
    private UUID bookingId;

    @NotNull
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @NotBlank
    @Column(nullable = false, length = 50, updatable = false)
    private String provider;

    @NotBlank
    @Column(name = "merchant_reference", nullable = false, length = 100, updatable = false)
    private String merchantReference;

    @Column(name = "provider_order_id", length = 150)
    private String providerOrderId;

    @Column(name = "provider_payment_id", length = 150)
    private String providerPaymentId;

    @Column(name = "checkout_reference", length = 500)
    private String checkoutReference;

    @NotBlank
    @Column(name = "idempotency_key", nullable = false, length = 100, updatable = false)
    private String idempotencyKey;

    @NotBlank
    @Column(name = "request_fingerprint", nullable = false, length = 128, updatable = false)
    private String requestFingerprint;

    @NotNull
    @Column(name = "requested_amount", nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal requestedAmount;

    @Column(name = "captured_amount", precision = 12, scale = 2)
    private BigDecimal capturedAmount;

    @NotBlank
    @Column(nullable = false, length = 3, updatable = false)
    private String currency;

    @NotNull
    @Column(name = "booking_payment_expires_at", nullable = false, updatable = false)
    private Instant bookingPaymentExpiresAt;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PaymentStatus status;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PaymentDisposition disposition;

    @Column(name = "provider_status", length = 100)
    private String providerStatus;

    @Column(name = "failure_code", length = 100)
    private String failureCode;

    @Column(name = "resolution_reason", length = 100)
    private String resolutionReason;

    @Column(name = "provider_occurred_at")
    private Instant providerOccurredAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(nullable = false)
    private int version = 1;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 0;

    @Column(name = "next_retry_at")
    private Instant nextRetryAt;

    protected PaymentAttempt() {
    }

    public PaymentAttempt(
            UUID bookingId,
            UUID userId,
            String provider,
            String merchantReference,
            String idempotencyKey,
            String requestFingerprint,
            BigDecimal requestedAmount,
            String currency,
            Instant bookingPaymentExpiresAt) {
        if (bookingId == null || userId == null || requestedAmount == null) {
            throw new IllegalArgumentException("booking, user, and requested amount are required");
        }
        this.bookingId = bookingId;
        this.userId = userId;
        this.provider = requireText(provider, "provider");
        this.merchantReference = requireText(merchantReference, "merchantReference");
        this.idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
        this.requestFingerprint = requireText(requestFingerprint, "requestFingerprint");
        this.requestedAmount = requestedAmount;
        this.currency = requireText(currency, "currency").toUpperCase();
        if (bookingPaymentExpiresAt == null) {
            throw new IllegalArgumentException("bookingPaymentExpiresAt is required");
        }
        this.bookingPaymentExpiresAt = bookingPaymentExpiresAt;
        this.status = PaymentStatus.INITIATING;
        this.disposition = PaymentDisposition.UNAPPLIED;
    }

    public void markInitiated(String providerOrderId, String providerStatus, String checkoutReference) {
        if (status != PaymentStatus.INITIATING) {
            return;
        }
        this.providerOrderId = requireText(providerOrderId, "providerOrderId");
        this.providerStatus = normalize(providerStatus);
        this.checkoutReference = normalize(checkoutReference);
        this.status = PaymentStatus.PENDING;
        this.version++;
    }

    public void markPending(String providerStatus, Instant occurredAt, Instant processedAt) {
        if (status == PaymentStatus.SUCCEEDED) {
            return;
        }
        if (status != PaymentStatus.INITIATING && status != PaymentStatus.PENDING) {
            return;
        }
        this.providerStatus = normalize(providerStatus);
        this.providerOccurredAt = occurredAt;
        this.processedAt = processedAt;
        this.status = PaymentStatus.PENDING;
        this.version++;
    }

    public void markFailed(String providerStatus, String failureCode, Instant occurredAt, Instant processedAt) {
        if (status == PaymentStatus.SUCCEEDED) {
            return;
        }
        if (status != PaymentStatus.INITIATING
                && status != PaymentStatus.PENDING
                && status != PaymentStatus.FAILED) {
            return;
        }
        this.providerStatus = normalize(providerStatus);
        this.failureCode = normalize(failureCode);
        this.providerOccurredAt = occurredAt;
        this.processedAt = processedAt;
        this.status = PaymentStatus.FAILED;
        this.disposition = PaymentDisposition.UNAPPLIED;
        this.version++;
    }

    public void markExpired(String providerStatus, Instant processedAt) {
        if (status == PaymentStatus.SUCCEEDED) {
            return;
        }
        if (status != PaymentStatus.INITIATING && status != PaymentStatus.PENDING) {
            return;
        }
        this.providerStatus = normalize(providerStatus);
        this.processedAt = processedAt;
        this.status = PaymentStatus.EXPIRED;
        this.disposition = PaymentDisposition.UNAPPLIED;
        this.version++;
    }

    public void markSucceeded(
            String providerOrderId,
            String providerPaymentId,
            String providerStatus,
            BigDecimal capturedAmount,
            Instant occurredAt,
            Instant processedAt,
            PaymentDisposition disposition,
            String resolutionReason) {
        if (status == PaymentStatus.SUCCEEDED) {
            return;
        }
        if (capturedAmount == null || disposition == null || disposition == PaymentDisposition.UNAPPLIED) {
            throw new IllegalArgumentException("Successful payment requires captured amount and disposition");
        }
        this.providerOrderId = requireText(providerOrderId, "providerOrderId");
        this.providerPaymentId = requireText(providerPaymentId, "providerPaymentId");
        this.providerStatus = normalize(providerStatus);
        this.capturedAmount = capturedAmount;
        this.providerOccurredAt = occurredAt;
        this.processedAt = processedAt;
        this.status = PaymentStatus.SUCCEEDED;
        this.disposition = disposition;
        this.resolutionReason = normalize(resolutionReason);
        this.version++;
    }

    /**
     * Worker claim/lease: increments {@code attemptCount} and defers the next due time
     * so another instance skips an in-flight Razorpay order call.
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

    public UUID getBookingId() { return bookingId; }
    public UUID getUserId() { return userId; }
    public String getProvider() { return provider; }
    public String getMerchantReference() { return merchantReference; }
    public String getProviderOrderId() { return providerOrderId; }
    public String getProviderPaymentId() { return providerPaymentId; }
    public String getCheckoutReference() { return checkoutReference; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getRequestFingerprint() { return requestFingerprint; }
    public BigDecimal getRequestedAmount() { return requestedAmount; }
    public BigDecimal getCapturedAmount() { return capturedAmount; }
    public String getCurrency() { return currency; }
    public Instant getBookingPaymentExpiresAt() { return bookingPaymentExpiresAt; }
    public PaymentStatus getStatus() { return status; }
    public PaymentDisposition getDisposition() { return disposition; }
    public String getProviderStatus() { return providerStatus; }
    public String getFailureCode() { return failureCode; }
    public String getResolutionReason() { return resolutionReason; }
    public Instant getProviderOccurredAt() { return providerOccurredAt; }
    public Instant getProcessedAt() { return processedAt; }
    public int getVersion() { return version; }
    public int getAttemptCount() { return attemptCount; }
    public Instant getNextRetryAt() { return nextRetryAt; }
}
