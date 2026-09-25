package in.bluebustickets.bluebus.fleet.api.operator.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import in.bluebustickets.bluebus.fleet.api.admin.dto.SeatDefinitionRequest;
import in.bluebustickets.bluebus.fleet.api.admin.dto.SeatLayoutMarkerRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * DRAFT metadata PATCH. Seat, marker, and layout-type changes are optional and
 * remain draft-only. Version and status are not writable here.
 */
public class UpdateOperatorSeatLayoutRequest {

    @Size(max = 120)
    private String name;

    @Min(1)
    private Integer deckCount;

    @Min(1)
    private Integer rowCount;

    @Min(1)
    private Integer columnCount;

    private String layoutType;

    @Valid
    private List<SeatDefinitionRequest> seats;

    @Valid
    private List<SeatLayoutMarkerRequest> markers;

    private boolean namePresent;
    private boolean deckCountPresent;
    private boolean rowCountPresent;
    private boolean columnCountPresent;
    private boolean layoutTypePresent;
    private boolean seatsPresent;
    private boolean markersPresent;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
        this.namePresent = true;
    }

    public Integer getDeckCount() {
        return deckCount;
    }

    public void setDeckCount(Integer deckCount) {
        this.deckCount = deckCount;
        this.deckCountPresent = true;
    }

    public Integer getRowCount() {
        return rowCount;
    }

    public void setRowCount(Integer rowCount) {
        this.rowCount = rowCount;
        this.rowCountPresent = true;
    }

    public Integer getColumnCount() {
        return columnCount;
    }

    public void setColumnCount(Integer columnCount) {
        this.columnCount = columnCount;
        this.columnCountPresent = true;
    }

    public String getLayoutType() {
        return layoutType;
    }

    public void setLayoutType(String layoutType) {
        this.layoutType = layoutType;
        this.layoutTypePresent = true;
    }

    public List<SeatDefinitionRequest> getSeats() {
        return seats;
    }

    public void setSeats(List<SeatDefinitionRequest> seats) {
        this.seats = seats;
        this.seatsPresent = true;
    }

    public List<SeatLayoutMarkerRequest> getMarkers() {
        return markers;
    }

    public void setMarkers(List<SeatLayoutMarkerRequest> markers) {
        this.markers = markers;
        this.markersPresent = true;
    }

    public boolean hasName() {
        return namePresent;
    }

    public boolean hasDeckCount() {
        return deckCountPresent;
    }

    public boolean hasRowCount() {
        return rowCountPresent;
    }

    public boolean hasColumnCount() {
        return columnCountPresent;
    }

    public boolean hasLayoutType() {
        return layoutTypePresent;
    }

    public boolean hasSeats() {
        return seatsPresent;
    }

    public boolean hasMarkers() {
        return markersPresent;
    }

    public boolean hasSupportedField() {
        return namePresent || deckCountPresent || rowCountPresent || columnCountPresent
                || layoutTypePresent || seatsPresent || markersPresent;
    }

    @JsonAnySetter
    public void rejectUnknown(String name, Object value) {
        throw new IllegalArgumentException("Unknown field '" + name + "'.");
    }
}
