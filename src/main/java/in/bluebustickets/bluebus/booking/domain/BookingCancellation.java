package in.bluebustickets.bluebus.booking.domain;

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
 * Immutable record of a completed unpaid customer cancellation.
 */
@Entity
@Table(name = "booking_cancellations")
public class BookingCancellation extends AuditableEntity {

    public static final String UNPAID_POLICY_CODE = "UNPAID_CUSTOMER_CANCELLATION_V1";

    @Column(name = "booking_id", nullable = false, updatable = false)
    private UUID bookingId;

    @Column(name = "requested_by_user_id", nullable = false, updatable = false)
    private UUID requestedByUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_status", nullable = false, length = 30, updatable = false)
    private BookingStatus previousStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30, updatable = false)
    private BookingCancellationStatus status;

    @Column(length = 500, updatable = false)
    private String reason;

    @Column(name = "policy_code", nullable = false, length = 100, updatable = false)
    private String policyCode;

    @Column(name = "refundable_amount", nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal refundableAmount;

    @Column(nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(name = "cancelled_at", nullable = false, updatable = false)
    private Instant cancelledAt;

    protected BookingCancellation() {
    }

    public BookingCancellation(UUID bookingId, UUID requestedByUserId, String reason, String currency, Instant now) {
        if (bookingId == null || requestedByUserId == null || now == null) {
            throw new IllegalArgumentException("booking, requester, and cancellation time are required");
        }
        if (currency == null || currency.isBlank()) {
            throw new IllegalArgumentException("currency is required");
        }
        String normalizedReason = reason == null || reason.isBlank() ? null : reason.trim();
        if (normalizedReason != null && normalizedReason.length() > 500) {
            throw new IllegalArgumentException("Cancellation reason must be at most 500 characters");
        }
        this.bookingId = bookingId;
        this.requestedByUserId = requestedByUserId;
        this.previousStatus = BookingStatus.PENDING_PAYMENT;
        this.status = BookingCancellationStatus.COMPLETED;
        this.reason = normalizedReason;
        this.policyCode = UNPAID_POLICY_CODE;
        this.refundableAmount = BigDecimal.ZERO.setScale(2);
        this.currency = currency;
        this.cancelledAt = now;
    }

    public UUID getBookingId() { return bookingId; }
    public UUID getRequestedByUserId() { return requestedByUserId; }
    public BookingStatus getPreviousStatus() { return previousStatus; }
    public BookingCancellationStatus getStatus() { return status; }
    public String getReason() { return reason; }
    public String getPolicyCode() { return policyCode; }
    public BigDecimal getRefundableAmount() { return refundableAmount; }
    public String getCurrency() { return currency; }
    public Instant getCancelledAt() { return cancelledAt; }
}
