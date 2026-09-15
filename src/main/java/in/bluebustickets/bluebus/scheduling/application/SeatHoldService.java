package in.bluebustickets.bluebus.scheduling.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import in.bluebustickets.bluebus.foundation.api.error.ApplicationConflictException;
import in.bluebustickets.bluebus.foundation.api.error.ResourceNotFoundException;
import in.bluebustickets.bluebus.scheduling.domain.SeatHold;
import in.bluebustickets.bluebus.scheduling.domain.SeatHoldStatus;
import in.bluebustickets.bluebus.scheduling.domain.Trip;
import in.bluebustickets.bluebus.scheduling.domain.TripSeatAllocation;
import in.bluebustickets.bluebus.scheduling.domain.TripSeatAllocationState;
import in.bluebustickets.bluebus.scheduling.domain.TripSeatInventory;
import in.bluebustickets.bluebus.scheduling.domain.TripSeatInventoryStatus;
import in.bluebustickets.bluebus.scheduling.repository.SeatHoldRepository;
import in.bluebustickets.bluebus.scheduling.repository.TripRepository;
import in.bluebustickets.bluebus.scheduling.repository.TripSeatAllocationRepository;
import in.bluebustickets.bluebus.scheduling.repository.TripSeatInventoryRepository;
import in.bluebustickets.bluebus.scheduling.repository.TripStopRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Foundation service for temporary multi-seat segment holds.
 * Not a customer booking API. PostgreSQL exclusion remains the concurrency guard.
 * Consume/cancel/expire take a pessimistic hold row lock so they serialize with the expiry reaper.
 */
@Service
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class SeatHoldService {

    private final TripRepository tripRepository;
    private final TripStopRepository tripStopRepository;
    private final TripSeatInventoryRepository tripSeatInventoryRepository;
    private final SeatHoldRepository seatHoldRepository;
    private final TripSeatAllocationRepository tripSeatAllocationRepository;
    private final Clock clock;

    public SeatHoldService(
            TripRepository tripRepository,
            TripStopRepository tripStopRepository,
            TripSeatInventoryRepository tripSeatInventoryRepository,
            SeatHoldRepository seatHoldRepository,
            TripSeatAllocationRepository tripSeatAllocationRepository,
            Clock clock) {
        this.tripRepository = tripRepository;
        this.tripStopRepository = tripStopRepository;
        this.tripSeatInventoryRepository = tripSeatInventoryRepository;
        this.seatHoldRepository = seatHoldRepository;
        this.tripSeatAllocationRepository = tripSeatAllocationRepository;
        this.clock = clock;
    }

    @Transactional
    public SeatHoldResult createHold(
            UUID tripId,
            int originSequence,
            int destinationSequence,
            Instant expiresAt,
            List<UUID> requestedInventoryIds) {
        return createHold(
                tripId,
                originSequence,
                destinationSequence,
                expiresAt,
                requestedInventoryIds,
                null,
                null,
                null);
    }

    @Transactional
    public SeatHoldResult createHold(
            UUID tripId,
            int originSequence,
            int destinationSequence,
            Instant expiresAt,
            List<UUID> requestedInventoryIds,
            UUID userId,
            String idempotencyKey,
            String requestFingerprint) {
        if (requestedInventoryIds == null || requestedInventoryIds.isEmpty()) {
            throw new IllegalArgumentException("Seat hold requires at least one inventory id");
        }
        if (expiresAt == null || !expiresAt.isAfter(clock.instant())) {
            throw new IllegalArgumentException("Seat hold expires_at must be in the future");
        }
        if (originSequence < 1 || destinationSequence <= originSequence) {
            throw new IllegalArgumentException("Seat hold destination must be greater than origin");
        }

        String normalizedKey = normalize(idempotencyKey);
        String normalizedFingerprint = normalize(requestFingerprint);
        if (userId != null && normalizedKey != null) {
            var existing = seatHoldRepository.findByUserIdAndIdempotencyKey(userId, normalizedKey);
            if (existing.isPresent()) {
                SeatHold hold = existing.get();
                if (!Objects.equals(hold.getRequestFingerprint(), normalizedFingerprint)) {
                    throw new ApplicationConflictException(
                            "Idempotency key was reused with a different request fingerprint.");
                }
                return toResult(hold);
            }
        }

        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new ResourceNotFoundException("Trip was not found."));
        if (!tripStopRepository.existsByTripIdAndSequenceNumber(tripId, originSequence)) {
            throw new IllegalArgumentException("Origin sequence does not exist on the trip");
        }
        if (!tripStopRepository.existsByTripIdAndSequenceNumber(tripId, destinationSequence)) {
            throw new IllegalArgumentException("Destination sequence does not exist on the trip");
        }

        LinkedHashSet<UUID> uniqueInventoryIds = new LinkedHashSet<>();
        for (UUID inventoryId : requestedInventoryIds) {
            if (inventoryId == null) {
                throw new IllegalArgumentException("Seat hold inventory id cannot be null");
            }
            if (!uniqueInventoryIds.add(inventoryId)) {
                throw new IllegalArgumentException("Seat hold inventory ids must be unique");
            }
        }

        List<TripSeatInventory> inventories = new ArrayList<>(uniqueInventoryIds.size());
        for (UUID inventoryId : uniqueInventoryIds) {
            TripSeatInventory inventory = tripSeatInventoryRepository.findById(inventoryId)
                    .orElseThrow(() -> new ResourceNotFoundException("Trip seat inventory was not found."));
            if (inventory.getTrip() == null || inventory.getTrip().getId() == null
                    || !inventory.getTrip().getId().equals(trip.getId())) {
                throw new IllegalArgumentException("Trip seat inventory does not belong to the trip");
            }
            if (inventory.getPhysicalStatus() != TripSeatInventoryStatus.AVAILABLE) {
                throw new IllegalArgumentException("Trip seat inventory is not physically AVAILABLE");
            }
            inventories.add(inventory);
        }

        SeatHold hold = new SeatHold(
                trip,
                originSequence,
                destinationSequence,
                expiresAt,
                userId,
                normalizedKey,
                normalizedFingerprint);
        try {
            hold = seatHoldRepository.saveAndFlush(hold);

            List<TripSeatAllocation> allocations = new ArrayList<>(inventories.size());
            for (TripSeatInventory inventory : inventories) {
                TripSeatAllocation allocation = new TripSeatAllocation(
                        trip,
                        inventory,
                        originSequence,
                        destinationSequence,
                        TripSeatAllocationState.HELD,
                        expiresAt);
                allocation.assignHoldId(hold.getId());
                allocations.add(allocation);
            }
            tripSeatAllocationRepository.saveAllAndFlush(allocations);
            return new SeatHoldResult(hold, List.copyOf(allocations));
        } catch (DataIntegrityViolationException exception) {
            throw new ApplicationConflictException(
                    "Seat hold conflicts with an active allocation for one or more seats.");
        }
    }

    @Transactional
    public SeatHoldResult consume(UUID holdId) {
        SeatHold hold = requireHoldForUpdate(holdId);
        hold.consume();
        seatHoldRepository.saveAndFlush(hold);
        return toResult(hold);
    }

    @Transactional
    public SeatHoldResult expire(UUID holdId) {
        SeatHold hold = requireHoldForUpdate(holdId);
        hold.expire();
        List<TripSeatAllocation> allocations = tripSeatAllocationRepository.findByHoldIdOrderByCreatedAtAsc(holdId);
        for (TripSeatAllocation allocation : allocations) {
            if (allocation.getState() == TripSeatAllocationState.HELD) {
                allocation.expire();
            }
        }
        seatHoldRepository.saveAndFlush(hold);
        tripSeatAllocationRepository.saveAllAndFlush(allocations);
        return new SeatHoldResult(hold, List.copyOf(allocations));
    }

    @Transactional
    public SeatHoldResult cancel(UUID holdId) {
        SeatHold hold = requireHoldForUpdate(holdId);
        hold.cancel();
        List<TripSeatAllocation> allocations = tripSeatAllocationRepository.findByHoldIdOrderByCreatedAtAsc(holdId);
        for (TripSeatAllocation allocation : allocations) {
            if (allocation.getState() == TripSeatAllocationState.HELD) {
                allocation.cancel();
            }
        }
        seatHoldRepository.saveAndFlush(hold);
        tripSeatAllocationRepository.saveAllAndFlush(allocations);
        return new SeatHoldResult(hold, List.copyOf(allocations));
    }

    @Transactional(readOnly = true)
    public SeatHoldResult getHold(UUID holdId) {
        return toResult(requireHold(holdId));
    }

    private SeatHold requireHold(UUID holdId) {
        return seatHoldRepository.findById(holdId)
                .orElseThrow(() -> new ResourceNotFoundException("Seat hold was not found."));
    }

    private SeatHold requireHoldForUpdate(UUID holdId) {
        return seatHoldRepository.findByIdForUpdate(holdId)
                .orElseThrow(() -> new ResourceNotFoundException("Seat hold was not found."));
    }

    private SeatHoldResult toResult(SeatHold hold) {
        List<TripSeatAllocation> allocations =
                tripSeatAllocationRepository.findByHoldIdOrderByCreatedAtAsc(hold.getId());
        return new SeatHoldResult(hold, List.copyOf(allocations));
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    public record SeatHoldResult(SeatHold hold, List<TripSeatAllocation> allocations) {
        public SeatHoldResult {
            Objects.requireNonNull(hold, "hold");
            allocations = allocations == null ? List.of() : List.copyOf(allocations);
        }

        public SeatHoldStatus status() {
            return hold.getStatus();
        }
    }
}
