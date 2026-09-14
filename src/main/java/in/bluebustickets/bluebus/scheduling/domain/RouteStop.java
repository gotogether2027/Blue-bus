package in.bluebustickets.bluebus.scheduling.domain;

import java.math.BigDecimal;

import in.bluebustickets.bluebus.foundation.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

@Entity
@Table(name = "route_stops")
public class RouteStop extends AuditableEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "route_id")
    private Route route;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "location_id")
    private Location location;

    @Min(1)
    @Column(name = "sequence_number", nullable = false)
    private int sequenceNumber;

    @Min(0)
    @Column(name = "arrival_offset_minutes")
    private Integer arrivalOffsetMinutes;

    @Min(0)
    @Column(name = "departure_offset_minutes")
    private Integer departureOffsetMinutes;

    @DecimalMin("0.0")
    @Column(name = "distance_km", precision = 10, scale = 2)
    private BigDecimal distanceKm;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "stop_kind", nullable = false, length = 30)
    private StopKind stopKind = StopKind.INTERMEDIATE;

    protected RouteStop() { }

    public RouteStop(Route route, Location location, int sequenceNumber, StopKind stopKind) {
        this(route, location, sequenceNumber, stopKind, null, null, null);
    }

    public RouteStop(
            Route route,
            Location location,
            int sequenceNumber,
            StopKind stopKind,
            Integer arrivalOffsetMinutes,
            Integer departureOffsetMinutes,
            BigDecimal distanceKm) {
        if (route == null || location == null || stopKind == null) {
            throw new IllegalArgumentException("Route stop requires route, location, and stop kind");
        }
        if (sequenceNumber < 1) {
            throw new IllegalArgumentException("Route stop sequence must be positive");
        }
        validateTiming(arrivalOffsetMinutes, departureOffsetMinutes, distanceKm);
        this.route = route;
        this.location = location;
        this.sequenceNumber = sequenceNumber;
        this.stopKind = stopKind;
        this.arrivalOffsetMinutes = arrivalOffsetMinutes;
        this.departureOffsetMinutes = departureOffsetMinutes;
        this.distanceKm = distanceKm;
    }

    public void updateDetails(
            Location location,
            int sequenceNumber,
            StopKind stopKind,
            Integer arrivalOffsetMinutes,
            Integer departureOffsetMinutes,
            BigDecimal distanceKm) {
        if (location == null || stopKind == null) {
            throw new IllegalArgumentException("Route stop location and stop kind are required");
        }
        if (sequenceNumber < 1) {
            throw new IllegalArgumentException("Route stop sequence must be positive");
        }
        validateTiming(arrivalOffsetMinutes, departureOffsetMinutes, distanceKm);
        this.location = location;
        this.sequenceNumber = sequenceNumber;
        this.stopKind = stopKind;
        this.arrivalOffsetMinutes = arrivalOffsetMinutes;
        this.departureOffsetMinutes = departureOffsetMinutes;
        this.distanceKm = distanceKm;
    }

    private static void validateTiming(
            Integer arrivalOffsetMinutes,
            Integer departureOffsetMinutes,
            BigDecimal distanceKm) {
        if (arrivalOffsetMinutes != null && arrivalOffsetMinutes < 0) {
            throw new IllegalArgumentException("Arrival offset cannot be negative");
        }
        if (departureOffsetMinutes != null && departureOffsetMinutes < 0) {
            throw new IllegalArgumentException("Departure offset cannot be negative");
        }
        if (arrivalOffsetMinutes != null && departureOffsetMinutes != null
                && departureOffsetMinutes < arrivalOffsetMinutes) {
            throw new IllegalArgumentException("Departure offset cannot precede arrival offset");
        }
        if (distanceKm != null && distanceKm.signum() < 0) {
            throw new IllegalArgumentException("Distance cannot be negative");
        }
    }

    public Route getRoute() { return route; }
    public Location getLocation() { return location; }
    public int getSequenceNumber() { return sequenceNumber; }
    public Integer getArrivalOffsetMinutes() { return arrivalOffsetMinutes; }
    public Integer getDepartureOffsetMinutes() { return departureOffsetMinutes; }
    public BigDecimal getDistanceKm() { return distanceKm; }
    public StopKind getStopKind() { return stopKind; }
}
