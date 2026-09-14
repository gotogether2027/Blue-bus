package in.bluebustickets.bluebus.scheduling.application;

import java.time.Instant;
import java.util.UUID;

import in.bluebustickets.bluebus.foundation.api.error.ApplicationConflictException;
import in.bluebustickets.bluebus.foundation.api.error.ResourceNotFoundException;
import in.bluebustickets.bluebus.scheduling.domain.Trip;
import in.bluebustickets.bluebus.scheduling.domain.TripSeatAllocation;
import in.bluebustickets.bluebus.scheduling.domain.TripSeatAllocationState;
import in.bluebustickets.bluebus.scheduling.domain.TripSeatInventory;
import in.bluebustickets.bluebus.scheduling.domain.TripSeatInventoryStatus;
import in.bluebustickets.bluebus.scheduling.repository.TripRepository;
import in.bluebustickets.bluebus.scheduling.repository.TripSeatAllocationRepository;
import in.bluebustickets.bluebus.scheduling.repository.TripSeatInventoryRepository;
import in.bluebustickets.bluebus.scheduling.repository.TripStopRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Foundation service for segment allocations. Not a customer booking API.
 * PostgreSQL GiST exclusion remains the final concurrency guard.
 */
@Service
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class TripSeatAllocationService {

    private final TripRepository tripRepository;
    private final TripSeatInventoryRepository tripSeatInventoryRepository;
    private final TripStopRepository tripStopRepository;
    private final TripSeatAllocationRepository tripSeatAllocationRepository;

    public TripSeatAllocationService(
            TripRepository tripRepository,
            TripSeatInventoryRepository tripSeatInventoryRepository,
            TripStopRepository tripStopRepository,
            TripSeatAllocationRepository tripSeatAllocationRepository) {
        this.tripRepository = tripRepository;
        this.tripSeatInventoryRepository = tripSeatInventoryRepository;
        this.tripStopRepository = tripStopRepository;
        this.tripSeatAllocationRepository = tripSeatAllocationRepository;
    }

    @Transactional
    public TripSeatAllocation allocate(
            UUID tripId,
            UUID inventoryId,
            int originSequence,
            int destinationSequence,
            TripSeatAllocationState state,
            Instant expiresAt) {
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new ResourceNotFoundException("Trip was not found."));
        TripSeatInventory inventory = tripSeatInventoryRepository.findById(inventoryId)
                .orElseThrow(() -> new ResourceNotFoundException("Trip seat inventory was not found."));

        if (inventory.getTrip() == null || inventory.getTrip().getId() == null
                || !inventory.getTrip().getId().equals(trip.getId())) {
            throw new IllegalArgumentException("Trip seat inventory does not belong to the trip");
        }
        if (originSequence < 1 || destinationSequence <= originSequence) {
            throw new IllegalArgumentException("Allocation destination must be greater than origin");
        }
        if (!tripStopRepository.existsByTripIdAndSequenceNumber(tripId, originSequence)) {
            throw new IllegalArgumentException("Origin sequence does not exist on the trip");
        }
        if (!tripStopRepository.existsByTripIdAndSequenceNumber(tripId, destinationSequence)) {
            throw new IllegalArgumentException("Destination sequence does not exist on the trip");
        }
        if (inventory.getPhysicalStatus() != TripSeatInventoryStatus.AVAILABLE) {
            throw new IllegalArgumentException("Trip seat inventory is not physically AVAILABLE");
        }
        if (state == null) {
            throw new IllegalArgumentException("Allocation state is required");
        }

        TripSeatAllocation allocation = new TripSeatAllocation(
                trip, inventory, originSequence, destinationSequence, state, expiresAt);
        try {
            return tripSeatAllocationRepository.saveAndFlush(allocation);
        } catch (DataIntegrityViolationException exception) {
            throw new ApplicationConflictException(
                    "Seat segment overlaps an active allocation for this inventory.");
        }
    }
}
