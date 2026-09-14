package in.bluebustickets.bluebus.fleet.api.admin.dto;

import java.util.UUID;

import in.bluebustickets.bluebus.fleet.domain.Seat;

public record SeatResponse(
        UUID id,
        String seatNumber,
        int deckNumber,
        int rowNumber,
        int columnNumber,
        String seatType,
        boolean sellable) {

    public static SeatResponse from(Seat seat) {
        return new SeatResponse(
                seat.getId(),
                seat.getSeatNumber(),
                seat.getDeckNumber(),
                seat.getRowNumber(),
                seat.getColumnNumber(),
                seat.getSeatType(),
                seat.isSellable());
    }
}
