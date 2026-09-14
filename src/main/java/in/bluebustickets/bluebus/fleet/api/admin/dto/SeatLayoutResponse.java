package in.bluebustickets.bluebus.fleet.api.admin.dto;

import java.util.List;
import java.util.UUID;

import in.bluebustickets.bluebus.fleet.domain.Seat;
import in.bluebustickets.bluebus.fleet.domain.SeatLayout;
import in.bluebustickets.bluebus.fleet.domain.SeatLayoutStatus;

public record SeatLayoutResponse(
        UUID id,
        UUID operatorId,
        String name,
        int version,
        int deckCount,
        int rowCount,
        int columnCount,
        SeatLayoutStatus status,
        List<SeatResponse> seats) {

    public static SeatLayoutResponse from(SeatLayout layout, List<Seat> seats) {
        return new SeatLayoutResponse(
                layout.getId(),
                layout.getOperator().getId(),
                layout.getName(),
                layout.getVersion(),
                layout.getDeckCount(),
                layout.getRowCount(),
                layout.getColumnCount(),
                layout.getStatus(),
                seats.stream().map(SeatResponse::from).toList());
    }
}
