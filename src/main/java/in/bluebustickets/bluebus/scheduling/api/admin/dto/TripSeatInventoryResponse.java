package in.bluebustickets.bluebus.scheduling.api.admin.dto;

import java.util.UUID;

import in.bluebustickets.bluebus.scheduling.domain.TripSeatInventory;
import in.bluebustickets.bluebus.scheduling.domain.TripSeatInventoryStatus;

public record TripSeatInventoryResponse(
        UUID id,
        UUID tripId,
        UUID layoutSeatId,
        UUID seatLayoutId,
        int seatLayoutVersion,
        String seatNumber,
        String seatType,
        int deckNumber,
        int rowNumber,
        int columnNumber,
        TripSeatInventoryStatus physicalStatus,
        String blockReason) {

    public static TripSeatInventoryResponse from(TripSeatInventory inventory) {
        return new TripSeatInventoryResponse(
                inventory.getId(),
                inventory.getTrip().getId(),
                inventory.getLayoutSeat().getId(),
                inventory.getSeatLayoutId(),
                inventory.getSeatLayoutVersion(),
                inventory.getSeatNumber(),
                inventory.getSeatType(),
                inventory.getDeckNumber(),
                inventory.getRowNumber(),
                inventory.getColumnNumber(),
                inventory.getPhysicalStatus(),
                inventory.getBlockReason());
    }
}
