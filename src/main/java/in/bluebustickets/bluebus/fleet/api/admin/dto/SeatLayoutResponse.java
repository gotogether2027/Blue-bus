package in.bluebustickets.bluebus.fleet.api.admin.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import in.bluebustickets.bluebus.fleet.domain.Seat;
import in.bluebustickets.bluebus.fleet.domain.SeatLayout;
import in.bluebustickets.bluebus.fleet.domain.SeatLayoutMarker;
import in.bluebustickets.bluebus.fleet.domain.SeatLayoutStatus;
import in.bluebustickets.bluebus.fleet.domain.SeatLayoutType;

public record SeatLayoutResponse(
        UUID id,
        UUID operatorId,
        String name,
        int version,
        SeatLayoutType layoutType,
        int deckCount,
        int rowCount,
        int columnCount,
        SeatLayoutStatus status,
        List<SeatResponse> seats,
        List<SeatLayoutMarker> markers,
        Instant updatedAt) {

    public static SeatLayoutResponse from(SeatLayout layout, List<Seat> seats) {
        return new SeatLayoutResponse(
                layout.getId(),
                layout.getOperator().getId(),
                layout.getName(),
                layout.getVersion(),
                layout.getLayoutType(),
                layout.getDeckCount(),
                layout.getRowCount(),
                layout.getColumnCount(),
                layout.getStatus(),
                seats.stream().map(SeatResponse::from).toList(),
                layout.getMarkers(),
                layout.getUpdatedAt());
    }
}
