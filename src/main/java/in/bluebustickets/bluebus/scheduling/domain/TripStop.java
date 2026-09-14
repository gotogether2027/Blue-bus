package in.bluebustickets.bluebus.scheduling.domain;

import java.math.BigDecimal;
import java.time.Instant;
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
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Immutable ordered-stop snapshot for one trip. Sequence numbers are the coordinate system
 * for future half-open segment allocations: [origin_sequence, destination_sequence).
 *
 * {@code trip_id} is mapped as a basic column so other entities (e.g. {@link TripPoint}) can
 * reference the composite unique key {@code (id, trip_id)} from V5. The {@link Trip} association
 * shares that column and is read-only for writes.
 */
@Entity
@Table(
        name = "trip_stops",
        uniqueConstraints = @UniqueConstraint(name = "uq_trip_stops_id_trip", columnNames = {"id", "trip_id"}))
public class TripStop extends AuditableEntity {

    /**
     * Basic column required so Hibernate can resolve composite FK references to
     * {@code trip_stops(id, trip_id)}. Owning side for persistence of {@code trip_id}.
     */
    @NotNull
    @Column(name = "trip_id", nullable = false, updatable = false)
    private UUID tripId;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trip_id", insertable = false, updatable = false, nullable = false)
    private Trip trip;

    @Column(name = "route_stop_id")
    private UUID routeStopId;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "location_id")
    private Location location;

    @Min(1)
    @Column(name = "sequence_number", nullable = false)
    private int sequenceNumber;

    @Column(name = "scheduled_arrival_at")
    private Instant scheduledArrivalAt;

    @Column(name = "scheduled_departure_at")
    private Instant scheduledDepartureAt;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "stop_kind", nullable = false, length = 30)
    private StopKind stopKind;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "stop_status", nullable = false, length = 30)
    private TripStopStatus stopStatus = TripStopStatus.ACTIVE;

    @DecimalMin("0.0")
    @Column(name = "distance_km", precision = 10, scale = 2)
    private BigDecimal distanceKm;

    protected TripStop() { }

    public TripStop(Trip trip, Location location, int sequenceNumber, StopKind stopKind) {
        this(trip, null, location, sequenceNumber, stopKind, TripStopStatus.ACTIVE, null, null, null);
    }

    public TripStop(
            Trip trip,
            UUID routeStopId,
            Location location,
            int sequenceNumber,
            StopKind stopKind,
            TripStopStatus stopStatus,
            Instant scheduledArrivalAt,
            Instant scheduledDepartureAt,
            BigDecimal distanceKm) {
        if (trip == null || location == null || stopKind == null || stopStatus == null) {
            throw new IllegalArgumentException("Trip stop requires trip, location, kind, and status");
        }
        if (trip.getId() == null) {
            throw new IllegalArgumentException("Trip must be persisted before creating a trip stop");
        }
        if (sequenceNumber < 1) {
            throw new IllegalArgumentException("Trip stop sequence must be positive");
        }
        if (scheduledArrivalAt != null && scheduledDepartureAt != null
                && scheduledDepartureAt.isBefore(scheduledArrivalAt)) {
            throw new IllegalArgumentException("Trip stop departure cannot precede arrival");
        }
        this.trip = trip;
        this.tripId = trip.getId();
        this.routeStopId = routeStopId;
        this.location = location;
        this.sequenceNumber = sequenceNumber;
        this.stopKind = stopKind;
        this.stopStatus = stopStatus;
        this.scheduledArrivalAt = scheduledArrivalAt;
        this.scheduledDepartureAt = scheduledDepartureAt;
        this.distanceKm = distanceKm;
    }

    public UUID getTripId() { return tripId; }
    public Trip getTrip() { return trip; }
    public UUID getRouteStopId() { return routeStopId; }
    public Location getLocation() { return location; }
    public int getSequenceNumber() { return sequenceNumber; }
    public StopKind getStopKind() { return stopKind; }
    public TripStopStatus getStopStatus() { return stopStatus; }
}
