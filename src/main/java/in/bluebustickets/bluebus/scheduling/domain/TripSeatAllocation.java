package in.bluebustickets.bluebus.scheduling.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import in.bluebustickets.bluebus.foundation.persistence.AuditableEntity;
import in.bluebustickets.bluebus.scheduling.persistence.Int4RangeUserType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.hibernate.annotations.Type;

/**
 * Segment occupancy for one physical trip seat. Does not change {@link TripSeatInventory} status.
 */
@Entity
@Table(name = "trip_seat_allocations")
public class TripSeatAllocation extends AuditableEntity {

    @NotNull
    @Column(name = "trip_id", nullable = false, updatable = false)
    private UUID tripId;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trip_id", insertable = false, updatable = false, nullable = false)
    private Trip trip;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "inventory_id", nullable = false, updatable = false)
    private TripSeatInventory inventory;

    @Min(1)
    @Column(name = "origin_sequence", nullable = false, updatable = false)
    private int originSequence;

    @Min(1)
    @Column(name = "destination_sequence", nullable = false, updatable = false)
    private int destinationSequence;

    @NotNull
    @Type(Int4RangeUserType.class)
    @Column(name = "segment_range", nullable = false, updatable = false, columnDefinition = "int4range")
    private Int4Range segmentRange;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private TripSeatAllocationState state;

    @Column(name = "hold_id")
    private UUID holdId;

    @Column(name = "booking_item_id")
    private UUID bookingItemId;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Min(1)
    @Column(nullable = false)
    private int version = 1;

    protected TripSeatAllocation() { }

    public TripSeatAllocation(
            Trip trip,
            TripSeatInventory inventory,
            int originSequence,
            int destinationSequence,
            TripSeatAllocationState state,
            Instant expiresAt) {
        if (trip == null || trip.getId() == null) {
            throw new IllegalArgumentException("Allocation requires a persisted trip");
        }
        if (inventory == null || inventory.getId() == null) {
            throw new IllegalArgumentException("Allocation requires a persisted trip seat inventory");
        }
        if (inventory.getTrip() == null || inventory.getTrip().getId() == null
                || !Objects.equals(trip.getId(), inventory.getTrip().getId())) {
            throw new IllegalArgumentException("Allocation inventory must belong to the same trip");
        }
        if (state == null) {
            throw new IllegalArgumentException("Allocation state is required");
        }
        if (originSequence < 1 || destinationSequence <= originSequence) {
            throw new IllegalArgumentException("Allocation destination must be greater than origin");
        }
        if (state == TripSeatAllocationState.HELD && expiresAt == null) {
            throw new IllegalArgumentException("HELD allocations require expires_at");
        }
        this.trip = trip;
        this.tripId = trip.getId();
        this.inventory = inventory;
        this.originSequence = originSequence;
        this.destinationSequence = destinationSequence;
        this.segmentRange = Int4Range.halfOpen(originSequence, destinationSequence);
        this.state = state;
        this.expiresAt = expiresAt;
    }

    public void markBooked(UUID bookingItemId) {
        if (state != TripSeatAllocationState.HELD) {
            throw new IllegalArgumentException("Only HELD allocations can become BOOKED");
        }
        if (bookingItemId == null) {
            throw new IllegalArgumentException("BOOKED allocations require a booking item id");
        }
        this.state = TripSeatAllocationState.BOOKED;
        this.bookingItemId = bookingItemId;
        this.expiresAt = null;
        this.version++;
    }

    public void expire() {
        if (state != TripSeatAllocationState.HELD) {
            throw new IllegalArgumentException("Only HELD allocations can expire");
        }
        this.state = TripSeatAllocationState.EXPIRED;
        this.version++;
    }

    public void cancel() {
        if (state != TripSeatAllocationState.HELD && state != TripSeatAllocationState.BOOKED) {
            throw new IllegalArgumentException("Only HELD or BOOKED allocations can be cancelled");
        }
        this.state = TripSeatAllocationState.CANCELLED;
        this.expiresAt = null;
        this.version++;
    }

    public void release() {
        if (state != TripSeatAllocationState.HELD) {
            throw new IllegalArgumentException("Only HELD allocations can be released");
        }
        this.state = TripSeatAllocationState.RELEASED;
        this.expiresAt = null;
        this.version++;
    }

    public void assignHoldId(UUID holdId) {
        this.holdId = holdId;
    }

    public UUID getTripId() { return tripId; }
    public Trip getTrip() { return trip; }
    public TripSeatInventory getInventory() { return inventory; }
    public int getOriginSequence() { return originSequence; }
    public int getDestinationSequence() { return destinationSequence; }
    public Int4Range getSegmentRange() { return segmentRange; }
    public TripSeatAllocationState getState() { return state; }
    public UUID getHoldId() { return holdId; }
    public UUID getBookingItemId() { return bookingItemId; }
    public Instant getExpiresAt() { return expiresAt; }
    public int getVersion() { return version; }
}
