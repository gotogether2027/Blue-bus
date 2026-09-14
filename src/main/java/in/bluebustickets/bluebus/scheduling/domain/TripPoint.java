package in.bluebustickets.bluebus.scheduling.domain;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

import in.bluebustickets.bluebus.foundation.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinColumns;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Snapshot of a selectable boarding/dropping point on one trip stop. */
@Entity
@Table(name = "trip_points")
public class TripPoint extends AuditableEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumns({
            @JoinColumn(name = "trip_stop_id", referencedColumnName = "id", nullable = false),
            @JoinColumn(name = "trip_id", referencedColumnName = "trip_id", nullable = false)
    })
    private TripStop tripStop;

    @Column(name = "source_route_point_id")
    private UUID sourceRoutePointId;

    @NotBlank
    @Column(nullable = false, length = 160)
    private String name;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "point_type", nullable = false, length = 30)
    private PointType pointType;

    @Column(length = 255)
    private String address;

    @DecimalMin("-90.0")
    @DecimalMax("90.0")
    @Column(precision = 9, scale = 6)
    private BigDecimal latitude;

    @DecimalMin("-180.0")
    @DecimalMax("180.0")
    @Column(precision = 9, scale = 6)
    private BigDecimal longitude;

    @Column(nullable = false)
    private boolean active = true;

    protected TripPoint() { }

    public TripPoint(TripStop tripStop, String name, PointType pointType) {
        this(tripStop, null, name, pointType);
    }

    public TripPoint(TripStop tripStop, UUID sourceRoutePointId, String name, PointType pointType) {
        if (tripStop == null || tripStop.getTrip() == null || name == null || pointType == null) {
            throw new IllegalArgumentException("Trip point requires a trip stop, name, and type");
        }
        this.tripStop = tripStop;
        this.sourceRoutePointId = sourceRoutePointId;
        this.name = name;
        this.pointType = pointType;
    }

    public Trip getTrip() { return tripStop == null ? null : tripStop.getTrip(); }
    public TripStop getTripStop() { return tripStop; }
    public UUID getSourceRoutePointId() { return sourceRoutePointId; }
    public String getName() { return name; }
    public PointType getPointType() { return pointType; }

    public boolean belongsToSameTrip(TripStop stop) {
        return stop != null
                && getTrip() != null
                && stop.getTrip() != null
                && Objects.equals(getTrip().getId(), stop.getTrip().getId());
    }
}
