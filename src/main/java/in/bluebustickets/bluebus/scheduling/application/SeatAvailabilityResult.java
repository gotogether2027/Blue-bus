package in.bluebustickets.bluebus.scheduling.application;

import java.util.UUID;

import in.bluebustickets.bluebus.scheduling.domain.TripSeatInventoryStatus;

/**
 * Immutable seat availability projection for one requested journey segment.
 * Safe to reuse as a future customer API DTO source.
 */
public record SeatAvailabilityResult(
        UUID inventoryId,
        String seatNumber,
        String seatType,
        int deckNumber,
        int rowNumber,
        int columnNumber,
        TripSeatInventoryStatus physicalStatus,
        JourneySeatAvailability journeyAvailability) {
}
