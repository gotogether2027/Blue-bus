package in.bluebustickets.bluebus.fleet.domain;

import java.util.ArrayList;
import java.util.List;

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
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "seat_layouts")
public class SeatLayout extends AuditableEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "operator_id")
    private Operator operator;

    @NotBlank
    @Column(nullable = false, length = 120)
    private String name;

    @Min(1)
    @Column(nullable = false)
    private int version;

    @Min(1)
    @Column(name = "deck_count", nullable = false)
    private int deckCount = 1;

    @Min(1)
    @Column(name = "row_count", nullable = false)
    private int rowCount;

    @Min(1)
    @Column(name = "column_count", nullable = false)
    private int columnCount;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private SeatLayoutStatus status = SeatLayoutStatus.DRAFT;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "layout_type", nullable = false, length = 30)
    private SeatLayoutType layoutType = SeatLayoutType.CUSTOM;

    @NotNull
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "markers_json", nullable = false)
    private List<SeatLayoutMarker> markers = new ArrayList<>();

    protected SeatLayout() { }

    public SeatLayout(Operator operator, String name, int version, int deckCount, int rowCount, int columnCount) {
        this.operator = operator;
        this.name = name;
        this.version = version;
        this.deckCount = deckCount;
        this.rowCount = rowCount;
        this.columnCount = columnCount;
        this.layoutType = SeatLayoutType.CUSTOM;
        this.markers = new ArrayList<>();
    }

    public void assignLayoutType(SeatLayoutType layoutType) {
        if (layoutType == null) {
            throw new IllegalArgumentException("Layout type is required");
        }
        this.layoutType = layoutType;
    }

    public void replaceMarkers(List<SeatLayoutMarker> markers) {
        this.markers = markers == null ? new ArrayList<>() : new ArrayList<>(markers);
    }

    public void updateDraftMetadata(String name, int deckCount, int rowCount, int columnCount) {
        if (status != SeatLayoutStatus.DRAFT) {
            throw new IllegalArgumentException("Only DRAFT seat layouts can be updated");
        }
        applyMetadata(name, deckCount, rowCount, columnCount);
    }

    public void updateMetadata(String name, int deckCount, int rowCount, int columnCount) {
        if (status == SeatLayoutStatus.ARCHIVED) {
            throw new IllegalArgumentException("Archived seat layouts cannot be updated");
        }
        applyMetadata(name, deckCount, rowCount, columnCount);
    }

    private void applyMetadata(String name, int deckCount, int rowCount, int columnCount) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Seat layout name is required");
        }
        if (deckCount < 1 || rowCount < 1 || columnCount < 1) {
            throw new IllegalArgumentException("Seat layout dimensions must be positive");
        }
        this.name = name.trim();
        this.deckCount = deckCount;
        this.rowCount = rowCount;
        this.columnCount = columnCount;
    }

    public void publish() {
        if (status == SeatLayoutStatus.ARCHIVED) {
            throw new IllegalArgumentException("Archived seat layouts cannot be published");
        }
        this.status = SeatLayoutStatus.PUBLISHED;
    }

    public void archive() {
        this.status = SeatLayoutStatus.ARCHIVED;
    }

    public boolean acceptsNewSeats() {
        return status != SeatLayoutStatus.ARCHIVED;
    }

    public Operator getOperator() { return operator; }
    public String getName() { return name; }
    public int getVersion() { return version; }
    public int getDeckCount() { return deckCount; }
    public int getRowCount() { return rowCount; }
    public int getColumnCount() { return columnCount; }
    public SeatLayoutStatus getStatus() { return status; }
    public SeatLayoutType getLayoutType() { return layoutType == null ? SeatLayoutType.CUSTOM : layoutType; }

    public List<SeatLayoutMarker> getMarkers() {
        return markers == null ? List.of() : List.copyOf(markers);
    }
}
