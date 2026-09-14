package in.bluebustickets.bluebus.scheduling.domain;

import in.bluebustickets.bluebus.foundation.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

@Entity
@Table(name = "route_stops")
public class RouteStop extends AuditableEntity {
    @NotNull @ManyToOne(optional = false) @JoinColumn(name = "route_id") private Route route;
    @NotNull @ManyToOne(optional = false) @JoinColumn(name = "location_id") private Location location;
    @Min(1) @Column(name = "sequence_number", nullable = false) private int sequenceNumber;
    @Min(0) @Column(name = "arrival_offset_minutes") private Integer arrivalOffsetMinutes;
    @Min(0) @Column(name = "departure_offset_minutes") private Integer departureOffsetMinutes;
    @DecimalMin("0.0") @Column(name = "distance_km", precision = 10, scale = 2) private BigDecimal distanceKm;
    @NotNull @Enumerated(EnumType.STRING) @Column(name = "stop_kind", nullable = false, length = 30)
    private StopKind stopKind = StopKind.INTERMEDIATE;
    protected RouteStop() { }
    public RouteStop(Route route, Location location, int sequenceNumber, StopKind stopKind) {
        this.route = route; this.location = location; this.sequenceNumber = sequenceNumber; this.stopKind = stopKind;
    }

    public Route getRoute() { return route; }
    public Location getLocation() { return location; }
    public int getSequenceNumber() { return sequenceNumber; }
    public StopKind getStopKind() { return stopKind; }
}
