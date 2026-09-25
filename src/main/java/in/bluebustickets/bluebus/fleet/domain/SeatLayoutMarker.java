package in.bluebustickets.bluebus.fleet.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** A non-seat cell. Markers are not copied into trip inventory. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SeatLayoutMarker {

    private String type;
    private int deckNumber;
    private int rowNumber;
    private int columnNumber;

    public SeatLayoutMarker() {
    }

    public SeatLayoutMarker(String type, int deckNumber, int rowNumber, int columnNumber) {
        this.type = type;
        this.deckNumber = deckNumber;
        this.rowNumber = rowNumber;
        this.columnNumber = columnNumber;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public int getDeckNumber() {
        return deckNumber;
    }

    public void setDeckNumber(int deckNumber) {
        this.deckNumber = deckNumber;
    }

    public int getRowNumber() {
        return rowNumber;
    }

    public void setRowNumber(int rowNumber) {
        this.rowNumber = rowNumber;
    }

    public int getColumnNumber() {
        return columnNumber;
    }

    public void setColumnNumber(int columnNumber) {
        this.columnNumber = columnNumber;
    }
}
