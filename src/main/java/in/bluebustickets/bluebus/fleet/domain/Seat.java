package in.bluebustickets.bluebus.fleet.domain;

import in.bluebustickets.bluebus.foundation.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** A reusable physical-seat definition belonging to a seat layout, not trip inventory. */
@Entity
@Table(name = "seats")
public class Seat extends AuditableEntity {
    @NotNull @ManyToOne(optional = false) @JoinColumn(name = "seat_layout_id") private SeatLayout seatLayout;
    @NotBlank @Column(name = "seat_number", nullable = false, length = 20) private String seatNumber;
    @Min(1) @Column(name = "deck_number", nullable = false) private int deckNumber = 1;
    @Min(1) @Column(name = "row_number", nullable = false) private int rowNumber;
    @Min(1) @Column(name = "column_number", nullable = false) private int columnNumber;
    @NotBlank @Column(name = "seat_type", nullable = false, length = 30) private String seatType;
    @Column(nullable = false) private boolean sellable = true;
    protected Seat() { }
    public Seat(SeatLayout seatLayout, String seatNumber, int deckNumber, int rowNumber, int columnNumber, String seatType) {
        this.seatLayout = seatLayout; this.seatNumber = seatNumber; this.deckNumber = deckNumber;
        this.rowNumber = rowNumber; this.columnNumber = columnNumber; this.seatType = seatType;
    }
    public SeatLayout getSeatLayout() { return seatLayout; }
    public String getSeatNumber() { return seatNumber; }
    public String getSeatType() { return seatType; }
    public int getDeckNumber() { return deckNumber; }
    public int getRowNumber() { return rowNumber; }
    public int getColumnNumber() { return columnNumber; }
}
