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

/**
 * Persistence foundation only. Provider refund execution is deferred.
 */
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

    protected Refund() {
    }
}
