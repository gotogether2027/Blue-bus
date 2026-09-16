package in.bluebustickets.bluebus.fleet.api.operator.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import in.bluebustickets.bluebus.fleet.api.admin.dto.SeatDefinitionRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/**
 * Create an operator-owned DRAFT seat layout. Path {@code operatorId} is authoritative.
 */
public class CreateOperatorSeatLayoutRequest {

    @NotBlank
    @Size(max = 120)
    private String name;

    @Min(1)
    private int version;

    @Min(1)
    private int deckCount;

    @Min(1)
    private int rowCount;

    @Min(1)
    private int columnCount;

    @NotEmpty
    @Valid
    private List<SeatDefinitionRequest> seats;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public int getDeckCount() {
        return deckCount;
    }

    public void setDeckCount(int deckCount) {
        this.deckCount = deckCount;
    }

    public int getRowCount() {
        return rowCount;
    }

    public void setRowCount(int rowCount) {
        this.rowCount = rowCount;
    }

    public int getColumnCount() {
        return columnCount;
    }

    public void setColumnCount(int columnCount) {
        this.columnCount = columnCount;
    }

    public List<SeatDefinitionRequest> getSeats() {
        return seats;
    }

    public void setSeats(List<SeatDefinitionRequest> seats) {
        this.seats = seats;
    }

    @JsonAnySetter
    public void rejectUnknown(String name, Object value) {
        throw new IllegalArgumentException("Unknown field '" + name + "'.");
    }
}
