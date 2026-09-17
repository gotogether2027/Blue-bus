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
 * Immutable record of a completed cancellation decision (customer or trip cascade).
 */
@Entity
@Table(name = "booking_cancellations")
public class BookingCancellation extends AuditableEntity {

    public static final String UNPAID_POLICY_CODE = "UNPAID_CUSTOMER_CANCELLATION_V1";
    public static final String CONFIRMED_FULL_REFUND_POLICY_CODE =
            "CONFIRMED_FULL_REFUND_CUSTOMER_CANCELLATION_V1";
    public static final String TRIP_CANCELLED_FULL_REFUND_POLICY_CODE = "TRIP_CANCELLED_FULL_REFUND_V1";
    public static final String TRIP_CANCELLED_UNPAID_POLICY_CODE = "TRIP_CANCELLED_UNPAID_V1";

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
        this(
                bookingId,
                requestedByUserId,
                BookingStatus.PENDING_PAYMENT,
                reason,
                UNPAID_POLICY_CODE,
                BigDecimal.ZERO.setScale(2),
                currency,
                now);
    }

    public static BookingCancellation confirmedFullRefund(
            UUID bookingId,
            UUID requestedByUserId,
            String reason,
            BigDecimal refundableAmount,
            String currency,
            Instant now) {
        return confirmedRefund(
                bookingId,
                requestedByUserId,
                reason,
                refundableAmount,
                currency,
                now,
                CONFIRMED_FULL_REFUND_POLICY_CODE);
    }

    public static BookingCancellation tripCancelledFullRefund(
            UUID bookingId,
            UUID requestedByUserId,
            String reason,
            BigDecimal refundableAmount,
            String currency,
            Instant now) {
        return confirmedRefund(
                bookingId,
                requestedByUserId,
                reason,
                refundableAmount,
                currency,
                now,
                TRIP_CANCELLED_FULL_REFUND_POLICY_CODE);
    }

    public static BookingCancellation tripCancelledUnpaid(
            UUID bookingId,
            UUID requestedByUserId,
            String reason,
            String currency,
            Instant now) {
        return new BookingCancellation(
                bookingId,
                requestedByUserId,
                BookingStatus.PENDING_PAYMENT,
                reason,
                TRIP_CANCELLED_UNPAID_POLICY_CODE,
                BigDecimal.ZERO.setScale(2),
                currency,
                now);
    }

    private static BookingCancellation confirmedRefund(
            UUID bookingId,
            UUID requestedByUserId,
            String reason,
            BigDecimal refundableAmount,
            String currency,
            Instant now,
            String policyCode) {
        if (refundableAmount == null || refundableAmount.signum() <= 0) {
            throw new IllegalArgumentException("Confirmed cancellation requires a positive refundable amount");
        }
        return new BookingCancellation(
                bookingId,
                requestedByUserId,
                BookingStatus.CONFIRMED,
                reason,
                policyCode,
                refundableAmount,
                currency,
                now);
    }

    private BookingCancellation(
            UUID bookingId,
            UUID requestedByUserId,
            BookingStatus previousStatus,
            String reason,
            String policyCode,
            BigDecimal refundableAmount,
            String currency,
            Instant now) {
        if (bookingId == null || requestedByUserId == null || now == null) {
            throw new IllegalArgumentException("booking, requester, and cancellation time are required");
        }
        if (previousStatus == null || policyCode == null || policyCode.isBlank()) {
            throw new IllegalArgumentException("previous status and policy code are required");
        }
        if (currency == null || currency.isBlank()) {
            throw new IllegalArgumentException("currency is required");
        }
        if (refundableAmount == null || refundableAmount.signum() < 0) {
            throw new IllegalArgumentException("refundableAmount cannot be negative");
        }
        String normalizedReason = reason == null || reason.isBlank() ? null : reason.trim();
        if (normalizedReason != null && normalizedReason.length() > 500) {
            throw new IllegalArgumentException("Cancellation reason must be at most 500 characters");
        }
        this.bookingId = bookingId;
        this.requestedByUserId = requestedByUserId;
        this.previousStatus = previousStatus;
        this.status = BookingCancellationStatus.COMPLETED;
        this.reason = normalizedReason;
        this.policyCode = policyCode;
        this.refundableAmount = refundableAmount;
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
