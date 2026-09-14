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
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Entity
@Table(name = "seat_layouts")
public class SeatLayout extends AuditableEntity {
    @NotNull @ManyToOne(optional = false) @JoinColumn(name = "operator_id") private Operator operator;
    @NotBlank @Column(nullable = false, length = 120) private String name;
    @Min(1) @Column(nullable = false) private int version;
    @Min(1) @Column(name = "deck_count", nullable = false) private int deckCount = 1;
    @Min(1) @Column(name = "row_count", nullable = false) private int rowCount;
    @Min(1) @Column(name = "column_count", nullable = false) private int columnCount;
    @NotNull @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30)
    private SeatLayoutStatus status = SeatLayoutStatus.DRAFT;
    protected SeatLayout() { }
    public SeatLayout(Operator operator, String name, int version, int deckCount, int rowCount, int columnCount) {
        this.operator = operator; this.name = name; this.version = version; this.deckCount = deckCount;
        this.rowCount = rowCount; this.columnCount = columnCount;
    }
    public Operator getOperator() { return operator; }
    public int getVersion() { return version; }
}
