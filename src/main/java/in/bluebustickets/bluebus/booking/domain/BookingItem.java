package in.bluebustickets.bluebus.booking.domain;

import java.math.BigDecimal;
import java.util.UUID;

import in.bluebustickets.bluebus.foundation.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Entity
@Table(name = "booking_items")
public class BookingItem extends AuditableEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "booking_id", nullable = false, updatable = false)
    private Booking booking;

    @NotNull
    @Column(name = "inventory_id", nullable = false, updatable = false)
    private UUID inventoryId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "passenger_id")
    private BookingPassenger passenger;

    @NotBlank
    @Column(name = "seat_number", nullable = false, length = 20, updatable = false)
    private String seatNumber;

    @NotBlank
    @Column(name = "seat_type", nullable = false, length = 30, updatable = false)
    private String seatType;

    @Min(1)
    @Column(name = "origin_sequence", nullable = false, updatable = false)
    private int originSequence;

    @Min(1)
    @Column(name = "destination_sequence", nullable = false, updatable = false)
    private int destinationSequence;

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

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private BookingItemStatus status = BookingItemStatus.ACTIVE;

    protected BookingItem() {
    }

    public BookingItem(
            Booking booking,
            UUID inventoryId,
            BookingPassenger passenger,
            String seatNumber,
            String seatType,
            int originSequence,
            int destinationSequence,
            BigDecimal baseAmount,
            BigDecimal totalAmount) {
        if (booking == null || inventoryId == null || passenger == null) {
            throw new IllegalArgumentException("booking, inventory, and passenger are required");
        }
        if (seatNumber == null || seatNumber.isBlank() || seatType == null || seatType.isBlank()) {
            throw new IllegalArgumentException("seat snapshot is required");
        }
        if (originSequence < 1 || destinationSequence <= originSequence) {
            throw new IllegalArgumentException("destination must be greater than origin");
        }
        this.booking = booking;
        this.inventoryId = inventoryId;
        this.passenger = passenger;
        this.seatNumber = seatNumber.trim();
        this.seatType = seatType.trim();
        this.originSequence = originSequence;
        this.destinationSequence = destinationSequence;
        this.baseAmount = baseAmount;
        this.totalAmount = totalAmount;
        this.status = BookingItemStatus.ACTIVE;
    }

    public void markExpired() {
        if (status != BookingItemStatus.ACTIVE) {
            throw new IllegalArgumentException(
                    "Only ACTIVE booking items can expire; current is " + status);
        }
        this.status = BookingItemStatus.EXPIRED;
    }

    public void markCancelled() {
        if (status == BookingItemStatus.CANCELLED) {
            return;
        }
        if (status != BookingItemStatus.ACTIVE) {
            throw new IllegalArgumentException(
                    "Only ACTIVE booking items can be cancelled; current is " + status);
        }
        this.status = BookingItemStatus.CANCELLED;
    }

    public Booking getBooking() {
        return booking;
    }

    public UUID getInventoryId() {
        return inventoryId;
    }

    public BookingPassenger getPassenger() {
        return passenger;
    }

    public String getSeatNumber() {
        return seatNumber;
    }

    public String getSeatType() {
        return seatType;
    }

    public int getOriginSequence() {
        return originSequence;
    }

    public int getDestinationSequence() {
        return destinationSequence;
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

    public BookingItemStatus getStatus() {
        return status;
    }
}
