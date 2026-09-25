package in.bluebustickets.bluebus.fleet.api.admin.dto;

import java.util.UUID;

import in.bluebustickets.bluebus.fleet.domain.Seat;
import in.bluebustickets.bluebus.fleet.domain.SeatPlacement;

public record SeatResponse(
        UUID id,
        String seatNumber,
        int deckNumber,
        int rowNumber,
        int columnNumber,
        String seatType,
        boolean sellable,
        String orientation,
        int spanRows,
        int spanColumns) {

    public static SeatResponse from(Seat seat) {
        SeatPlacement placement = seat.placement();
        return new SeatResponse(
                seat.getId(),
                seat.getSeatNumber(),
                seat.getDeckNumber(),
                seat.getRowNumber(),
                seat.getColumnNumber(),
                seat.getSeatType(),
                seat.isSellable(),
                placement.getOrientation(),
                placement.getSpanRows(),
                placement.getSpanColumns());
    }
}
