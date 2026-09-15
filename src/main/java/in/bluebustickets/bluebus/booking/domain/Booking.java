package in.bluebustickets.bluebus.booking.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import in.bluebustickets.bluebus.foundation.persistence.AuditableEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Entity
@Table(name = "bookings")
public class Booking extends AuditableEntity {

    @NotBlank
    @Column(name = "booking_reference", nullable = false, length = 32, updatable = false)
    private String bookingReference;

    @NotNull
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @NotNull
    @Column(name = "trip_id", nullable = false, updatable = false)
    private UUID tripId;

    @NotNull
    @Column(name = "operator_id", nullable = false, updatable = false)
    private UUID operatorId;

    @NotNull
    @Column(name = "hold_id", nullable = false, updatable = false)
    private UUID holdId;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private BookingStatus status = BookingStatus.PENDING_PAYMENT;

    @Min(1)
    @Column(name = "origin_sequence", nullable = false, updatable = false)
    private int originSequence;

    @Min(1)
    @Column(name = "destination_sequence", nullable = false, updatable = false)
    private int destinationSequence;

    @Column(name = "origin_trip_stop_id", updatable = false)
    private UUID originTripStopId;

    @Column(name = "destination_trip_stop_id", updatable = false)
    private UUID destinationTripStopId;

    @NotBlank
    @Column(nullable = false, length = 3, updatable = false)
    private String currency = "INR";

    @NotNull
    @Column(name = "base_amount", nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal baseAmount;

    @NotNull
    @Column(name = "tax_amount", nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal taxAmount = BigDecimal.ZERO;

    @NotNull
    @Column(name = "fee_amount", nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal feeAmount = BigDecimal.ZERO;

    @NotNull
    @Column(name = "discount_amount", nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal discountAmount = BigDecimal.ZERO;

    @NotNull
    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal totalAmount;

    @Column(name = "idempotency_key", length = 100, updatable = false)
    private String idempotencyKey;

    @Column(name = "request_fingerprint", length = 128, updatable = false)
    private String requestFingerprint;

    @NotNull
    @Column(name = "payment_expires_at", nullable = false, updatable = false)
    private Instant paymentExpiresAt;

    @Min(1)
    @Column(nullable = false)
    private int version = 1;

    @OneToMany(mappedBy = "booking", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("createdAt ASC")
    private Set<BookingPassenger> passengers = new LinkedHashSet<>();

    @OneToMany(mappedBy = "booking", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("createdAt ASC")
    private Set<BookingItem> items = new LinkedHashSet<>();

    protected Booking() {
    }

    public Booking(
            String bookingReference,
            UUID userId,
            UUID tripId,
            UUID operatorId,
            UUID holdId,
            int originSequence,
            int destinationSequence,
            UUID originTripStopId,
            UUID destinationTripStopId,
            BigDecimal baseAmount,
            BigDecimal totalAmount,
            String idempotencyKey,
            String requestFingerprint,
            Instant paymentExpiresAt) {
        if (bookingReference == null || bookingReference.isBlank()) {
            throw new IllegalArgumentException("bookingReference is required");
        }
        if (userId == null || tripId == null || operatorId == null || holdId == null) {
            throw new IllegalArgumentException("user, trip, operator, and hold are required");
        }
        if (originSequence < 1 || destinationSequence <= originSequence) {
            throw new IllegalArgumentException("destination must be greater than origin");
        }
        if (baseAmount == null || totalAmount == null) {
            throw new IllegalArgumentException("amounts are required");
        }
        if (paymentExpiresAt == null) {
            throw new IllegalArgumentException("paymentExpiresAt is required");
        }
        this.bookingReference = bookingReference.trim();
        this.userId = userId;
        this.tripId = tripId;
        this.operatorId = operatorId;
        this.holdId = holdId;
        this.originSequence = originSequence;
        this.destinationSequence = destinationSequence;
        this.originTripStopId = originTripStopId;
        this.destinationTripStopId = destinationTripStopId;
        this.baseAmount = baseAmount;
        this.totalAmount = totalAmount;
        this.idempotencyKey = blankToNull(idempotencyKey);
        this.requestFingerprint = blankToNull(requestFingerprint);
        this.paymentExpiresAt = paymentExpiresAt;
        this.status = BookingStatus.PENDING_PAYMENT;
    }

    public void markConfirmed() {
        requireStatus(BookingStatus.PENDING_PAYMENT, "CONFIRMED");
        this.status = BookingStatus.CONFIRMED;
        this.version++;
    }

    public void markCancelled() {
        if (status == BookingStatus.CANCELLED) {
            return;
        }
        if (status != BookingStatus.PENDING_PAYMENT && status != BookingStatus.CONFIRMED) {
            throw new IllegalArgumentException("Booking cannot be cancelled from status " + status);
        }
        this.status = BookingStatus.CANCELLED;
        this.version++;
    }

    public void markExpired() {
        requireStatus(BookingStatus.PENDING_PAYMENT, "EXPIRED");
        this.status = BookingStatus.EXPIRED;
        this.version++;
    }

    public void addPassenger(BookingPassenger passenger) {
        passengers.add(passenger);
    }

    public void addItem(BookingItem item) {
        items.add(item);
    }

    private void requireStatus(BookingStatus expected, String target) {
        if (status != expected) {
            throw new IllegalArgumentException(
                    "Only " + expected + " bookings can transition to " + target + "; current is " + status);
        }
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    public String getBookingReference() {
        return bookingReference;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getTripId() {
        return tripId;
    }

    public UUID getOperatorId() {
        return operatorId;
    }

    public UUID getHoldId() {
        return holdId;
    }

    public BookingStatus getStatus() {
        return status;
    }

    public int getOriginSequence() {
        return originSequence;
    }

    public int getDestinationSequence() {
        return destinationSequence;
    }

    public UUID getOriginTripStopId() {
        return originTripStopId;
    }

    public UUID getDestinationTripStopId() {
        return destinationTripStopId;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getBaseAmount() {
        return baseAmount;
    }

    public BigDecimal getTaxAmount() {
        return taxAmount;
    }

    public BigDecimal getFeeAmount() {
        return feeAmount;
    }

    public BigDecimal getDiscountAmount() {
        return discountAmount;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public Instant getPaymentExpiresAt() {
        return paymentExpiresAt;
    }

    public int getVersion() {
        return version;
    }

    public List<BookingPassenger> getPassengers() {
        return List.copyOf(passengers);
    }

    public List<BookingItem> getItems() {
        return List.copyOf(items);
    }
}
