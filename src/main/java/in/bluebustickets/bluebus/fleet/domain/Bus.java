package in.bluebustickets.bluebus.fleet.domain;

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
import jakarta.validation.constraints.Pattern;
import java.util.Objects;

@Entity
@Table(name = "buses")
public class Bus extends AuditableEntity {
    @NotNull @ManyToOne(optional = false) @JoinColumn(name = "operator_id") private Operator operator;
    @NotNull @ManyToOne(optional = false) @JoinColumn(name = "bus_type_id") private BusType busType;
    @NotNull @ManyToOne(optional = false) @JoinColumn(name = "seat_layout_id") private SeatLayout seatLayout;
    @NotBlank @Pattern(regexp = "^[A-Za-z0-9 -]{4,30}$", message = "must be a valid registration number")
    @Column(name = "registration_number", nullable = false, unique = true, length = 30) private String registrationNumber;
    @Column(name = "display_name", length = 120) private String displayName;
    @NotNull @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) private BusStatus status = BusStatus.ACTIVE;
    protected Bus() { }
    public Bus(Operator operator, BusType busType, SeatLayout seatLayout, String registrationNumber) {
        if (!samePersistedOperator(operator, seatLayout.getOperator())) {
            throw new IllegalArgumentException("Bus layout must belong to the same operator");
        }
        this.operator = operator; this.busType = busType; this.seatLayout = seatLayout; this.registrationNumber = registrationNumber;
    }

    private static boolean samePersistedOperator(Operator left, Operator right) {
        if (left == right) return true;
        return left != null && right != null && left.getId() != null && right.getId() != null
                && Objects.equals(left.getId(), right.getId());
    }

    public Operator getOperator() { return operator; }
    public SeatLayout getSeatLayout() { return seatLayout; }
    public boolean isActive() { return status == BusStatus.ACTIVE; }
}
