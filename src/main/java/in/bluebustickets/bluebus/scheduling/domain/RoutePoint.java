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
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Selectable boarding or dropping place attached to a reusable route stop. */
@Entity
@Table(name = "route_points")
public class RoutePoint extends AuditableEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "route_stop_id")
    private RouteStop routeStop;

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

    protected RoutePoint() { }

    public RoutePoint(RouteStop routeStop, String name, PointType pointType) {
        if (routeStop == null || name == null || pointType == null) {
            throw new IllegalArgumentException("Route point stop, name, and type are required");
        }
        this.routeStop = routeStop;
        this.name = name;
        this.pointType = pointType;
    }

    public RouteStop getRouteStop() { return routeStop; }
    public String getName() { return name; }
    public PointType getPointType() { return pointType; }
    public boolean isActive() { return active; }
}
