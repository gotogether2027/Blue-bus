package in.bluebustickets.bluebus.fleet.api.operator.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * DRAFT-only metadata PATCH. Seats, version, and status are immutable after create.
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

    private boolean namePresent;
    private boolean deckCountPresent;
    private boolean rowCountPresent;
    private boolean columnCountPresent;

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

    public boolean hasSupportedField() {
        return namePresent || deckCountPresent || rowCountPresent || columnCountPresent;
    }

    @JsonAnySetter
    public void rejectUnknown(String name, Object value) {
        throw new IllegalArgumentException("Unknown field '" + name + "'.");
    }
}
