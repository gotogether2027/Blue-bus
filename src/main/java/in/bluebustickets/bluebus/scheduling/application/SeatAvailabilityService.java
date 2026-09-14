package in.bluebustickets.bluebus.scheduling.application;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import in.bluebustickets.bluebus.foundation.api.error.ResourceNotFoundException;
import in.bluebustickets.bluebus.scheduling.domain.TripSeatInventory;
import in.bluebustickets.bluebus.scheduling.domain.TripSeatInventoryStatus;
import in.bluebustickets.bluebus.scheduling.repository.TripRepository;
import in.bluebustickets.bluebus.scheduling.repository.TripSeatAllocationRepository;
import in.bluebustickets.bluebus.scheduling.repository.TripSeatInventoryRepository;
import in.bluebustickets.bluebus.scheduling.repository.TripStopRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only projection of journey seat availability for one trip OD segment.
 * <p>
 * Availability is derived from physical inventory status plus overlapping active allocations
 * ({@code HELD}/{@code BOOKED}/{@code BLOCKED}). It is never stored on inventory.
 * <p>
 * Trip saleability ({@code ON_SALE}, booking window, etc.) is not filtered yet — any existing
 * trip may be projected. Future search/customer APIs should add that gate.
 * <p>
 * A {@code HELD} allocation whose {@code expires_at} is in the past still blocks availability
 * until an explicit expire/cancel transition; this service does not invent implicit expiry.
 */
@Service
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class SeatAvailabilityService {

    private final TripRepository tripRepository;
    private final TripStopRepository tripStopRepository;
    private final TripSeatInventoryRepository tripSeatInventoryRepository;
    private final TripSeatAllocationRepository tripSeatAllocationRepository;

    public SeatAvailabilityService(
            TripRepository tripRepository,
            TripStopRepository tripStopRepository,
            TripSeatInventoryRepository tripSeatInventoryRepository,
            TripSeatAllocationRepository tripSeatAllocationRepository) {
        this.tripRepository = tripRepository;
        this.tripStopRepository = tripStopRepository;
        this.tripSeatInventoryRepository = tripSeatInventoryRepository;
        this.tripSeatAllocationRepository = tripSeatAllocationRepository;
    }

    @Transactional(readOnly = true)
    public List<SeatAvailabilityResult> getSeatAvailability(
            UUID tripId,
            int originSequence,
            int destinationSequence) {
        if (!tripRepository.existsById(tripId)) {
            throw new ResourceNotFoundException("Trip was not found.");
        }
        if (originSequence < 1 || destinationSequence <= originSequence) {
            throw new IllegalArgumentException("Availability destination must be greater than origin");
        }
        if (!tripStopRepository.existsByTripIdAndSequenceNumber(tripId, originSequence)) {
            throw new IllegalArgumentException("Origin sequence does not exist on the trip");
        }
        if (!tripStopRepository.existsByTripIdAndSequenceNumber(tripId, destinationSequence)) {
            throw new IllegalArgumentException("Destination sequence does not exist on the trip");
        }

        List<TripSeatInventory> inventory =
                tripSeatInventoryRepository.findByTrip_IdOrderBySeatNumberAsc(tripId);
        Set<UUID> occupiedInventoryIds = new HashSet<>(
                tripSeatAllocationRepository.findInventoryIdsWithActiveOverlap(
                        tripId, originSequence, destinationSequence));

        return inventory.stream()
                .map(seat -> toResult(seat, occupiedInventoryIds.contains(seat.getId())))
                .toList();
    }

    private static SeatAvailabilityResult toResult(TripSeatInventory seat, boolean hasActiveOverlap) {
        JourneySeatAvailability journey = (seat.getPhysicalStatus() == TripSeatInventoryStatus.AVAILABLE
                && !hasActiveOverlap)
                ? JourneySeatAvailability.AVAILABLE
                : JourneySeatAvailability.UNAVAILABLE;
        return new SeatAvailabilityResult(
                seat.getId(),
                seat.getSeatNumber(),
                seat.getSeatType(),
                seat.getDeckNumber(),
                seat.getRowNumber(),
                seat.getColumnNumber(),
                seat.getPhysicalStatus(),
                journey);
    }
}
