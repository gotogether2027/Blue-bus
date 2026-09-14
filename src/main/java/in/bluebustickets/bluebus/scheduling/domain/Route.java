package in.bluebustickets.bluebus.scheduling.domain;

import java.util.Objects;

import in.bluebustickets.bluebus.foundation.persistence.AuditableEntity;
import in.bluebustickets.bluebus.operator.domain.Operator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Entity
@Table(name = "routes")
public class Route extends AuditableEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "operator_id")
    private Operator operator;

    @NotBlank
    @Column(nullable = false, length = 60)
    private String code;

    @NotBlank
    @Column(nullable = false, length = 160)
    private String name;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_location_id")
    private Location sourceLocation;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "destination_location_id")
    private Location destinationLocation;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private RouteStatus status = RouteStatus.ACTIVE;

    protected Route() { }

    public Route(
            Operator operator,
            String code,
            String name,
            Location sourceLocation,
            Location destinationLocation) {
        if (samePersistedLocation(sourceLocation, destinationLocation)) {
            throw new IllegalArgumentException("Route endpoints must be different");
        }
        this.operator = operator;
        this.code = code;
        this.name = name;
        this.sourceLocation = sourceLocation;
        this.destinationLocation = destinationLocation;
    }

    public void updateMetadata(String name, Location sourceLocation, Location destinationLocation) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Route name is required");
        }
        if (samePersistedLocation(sourceLocation, destinationLocation)) {
            throw new IllegalArgumentException("Route endpoints must be different");
        }
        this.name = name;
        this.sourceLocation = sourceLocation;
        this.destinationLocation = destinationLocation;
    }

    public void activate() {
        this.status = RouteStatus.ACTIVE;
    }

    public void deactivate() {
        this.status = RouteStatus.INACTIVE;
    }

    private static boolean samePersistedLocation(Location left, Location right) {
        if (left == right) {
            return true;
        }
        return left != null && right != null && left.getId() != null && right.getId() != null
                && Objects.equals(left.getId(), right.getId());
    }

    public Operator getOperator() { return operator; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public Location getSourceLocation() { return sourceLocation; }
    public Location getDestinationLocation() { return destinationLocation; }
    public RouteStatus getStatus() { return status; }
    public boolean isActive() { return status == RouteStatus.ACTIVE; }
}
