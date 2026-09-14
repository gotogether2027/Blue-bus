package in.bluebustickets.bluebus.scheduling.domain;

import java.util.Objects;
import java.util.UUID;

import in.bluebustickets.bluebus.fleet.domain.Seat;
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

/**
 * Physical seat snapshot for one trip. It is not a hold or booking.
 * Future occupancy is a segment allocation on this inventory row, not a BOOKED flag.
 */
@Entity
@Table(name = "trip_seat_inventory")
public class TripSeatInventory extends AuditableEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trip_id", nullable = false)
    private Trip trip;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "layout_seat_id", nullable = false, updatable = false)
    private Seat layoutSeat;

    @NotNull
    @Column(name = "seat_layout_id", nullable = false, updatable = false)
    private UUID seatLayoutId;

    @Min(1)
    @Column(name = "seat_layout_version", nullable = false, updatable = false)
    private int seatLayoutVersion;

    @NotBlank
    @Column(name = "seat_number", nullable = false, length = 20)
    private String seatNumber;

    @NotBlank
    @Column(name = "seat_type", nullable = false, length = 30)
    private String seatType;

    @Min(1)
    @Column(name = "deck_number", nullable = false)
    private int deckNumber;

    @Min(1)
    @Column(name = "row_number", nullable = false)
    private int rowNumber;

    @Min(1)
    @Column(name = "column_number", nullable = false)
    private int columnNumber;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "physical_status", nullable = false, length = 30)
    private TripSeatInventoryStatus physicalStatus = TripSeatInventoryStatus.AVAILABLE;

    @Column(name = "block_reason", length = 255)
    private String blockReason;

    @Min(1)
    @Column(name = "version", nullable = false)
    private int version = 1;

    protected TripSeatInventory() { }

    public TripSeatInventory(Trip trip, Seat layoutSeat) {
        this(trip, layoutSeat, TripSeatInventoryStatus.AVAILABLE, null);
    }

    public TripSeatInventory(
            Trip trip,
            Seat layoutSeat,
            TripSeatInventoryStatus physicalStatus,
            String blockReason) {
        if (trip == null || layoutSeat == null || physicalStatus == null) {
            throw new IllegalArgumentException("Trip seat inventory requires trip, layout seat, and status");
        }
        if (trip.getSeatLayout() == null || layoutSeat.getSeatLayout() == null) {
            throw new IllegalArgumentException("Trip seat inventory requires a trip layout and a layout seat");
        }
        if (!samePersistedLayout(trip.getSeatLayout().getId(), layoutSeat.getSeatLayout().getId())) {
            throw new IllegalArgumentException("Trip seat inventory must belong to the trip seat layout");
        }
        if (physicalStatus == TripSeatInventoryStatus.BLOCKED && (blockReason == null || blockReason.isBlank())) {
            throw new IllegalArgumentException("Blocked inventory requires a block reason");
        }
        if (physicalStatus == TripSeatInventoryStatus.AVAILABLE) {
            blockReason = null;
        }
        this.trip = trip;
        this.layoutSeat = layoutSeat;
        this.seatLayoutId = layoutSeat.getSeatLayout().getId();
        this.seatLayoutVersion = layoutSeat.getSeatLayout().getVersion();
        this.seatNumber = layoutSeat.getSeatNumber();
        this.seatType = layoutSeat.getSeatType();
        this.deckNumber = layoutSeat.getDeckNumber();
        this.rowNumber = layoutSeat.getRowNumber();
        this.columnNumber = layoutSeat.getColumnNumber();
        this.physicalStatus = physicalStatus;
        this.blockReason = blockReason;
    }

    private static boolean samePersistedLayout(UUID left, UUID right) {
        return left != null && Objects.equals(left, right);
    }

    public Trip getTrip() { return trip; }
    public Seat getLayoutSeat() { return layoutSeat; }
    public UUID getSeatLayoutId() { return seatLayoutId; }
    public int getSeatLayoutVersion() { return seatLayoutVersion; }
    public String getSeatNumber() { return seatNumber; }
    public String getSeatType() { return seatType; }
    public int getDeckNumber() { return deckNumber; }
    public int getRowNumber() { return rowNumber; }
    public int getColumnNumber() { return columnNumber; }
    public TripSeatInventoryStatus getPhysicalStatus() { return physicalStatus; }
    public String getBlockReason() { return blockReason; }
}
