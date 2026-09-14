package in.bluebustickets.bluebus.scheduling.domain;

import in.bluebustickets.bluebus.foundation.persistence.AuditableEntity;
import in.bluebustickets.bluebus.operator.domain.Operator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Objects;

@Entity
@Table(name = "routes")
public class Route extends AuditableEntity {
    @NotNull @ManyToOne(optional = false) @JoinColumn(name = "operator_id") private Operator operator;
    @NotBlank @Column(nullable = false, length = 60) private String code;
    @NotBlank @Column(nullable = false, length = 160) private String name;
    @NotNull @ManyToOne(optional = false) @JoinColumn(name = "source_location_id") private Location sourceLocation;
    @NotNull @ManyToOne(optional = false) @JoinColumn(name = "destination_location_id") private Location destinationLocation;
    @NotNull @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) private RouteStatus status = RouteStatus.ACTIVE;
    protected Route() { }
    public Route(Operator operator, String code, String name, Location sourceLocation, Location destinationLocation) {
        if (samePersistedLocation(sourceLocation, destinationLocation)) {
            throw new IllegalArgumentException("Route endpoints must be different");
        }
        this.operator = operator; this.code = code; this.name = name; this.sourceLocation = sourceLocation;
        this.destinationLocation = destinationLocation;
    }

    private static boolean samePersistedLocation(Location left, Location right) {
        if (left == right) return true;
        return left != null && right != null && left.getId() != null && right.getId() != null
                && Objects.equals(left.getId(), right.getId());
    }

    public Operator getOperator() { return operator; }
    public boolean isActive() { return status == RouteStatus.ACTIVE; }
}
