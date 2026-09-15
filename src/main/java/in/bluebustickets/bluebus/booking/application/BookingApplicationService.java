package in.bluebustickets.bluebus.booking.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import in.bluebustickets.bluebus.booking.api.dto.BookingPassengerRequest;
import in.bluebustickets.bluebus.booking.api.dto.BookingResponse;
import in.bluebustickets.bluebus.booking.api.dto.CreateBookingRequest;
import in.bluebustickets.bluebus.booking.domain.Booking;
import in.bluebustickets.bluebus.booking.domain.BookingItem;
import in.bluebustickets.bluebus.booking.domain.BookingPassenger;
import in.bluebustickets.bluebus.booking.repository.BookingRepository;
import in.bluebustickets.bluebus.foundation.api.error.ApplicationConflictException;
import in.bluebustickets.bluebus.foundation.api.error.ResourceNotFoundException;
import in.bluebustickets.bluebus.scheduling.domain.SeatHold;
import in.bluebustickets.bluebus.scheduling.domain.SeatHoldStatus;
import in.bluebustickets.bluebus.scheduling.domain.Trip;
import in.bluebustickets.bluebus.scheduling.domain.TripSeatAllocation;
import in.bluebustickets.bluebus.scheduling.domain.TripSeatAllocationState;
import in.bluebustickets.bluebus.scheduling.domain.TripSeatInventory;
import in.bluebustickets.bluebus.scheduling.domain.TripStop;
import in.bluebustickets.bluebus.scheduling.repository.SeatHoldRepository;
import in.bluebustickets.bluebus.scheduling.repository.TripSeatAllocationRepository;
import in.bluebustickets.bluebus.scheduling.repository.TripStopRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hold-to-booking conversion. Consumes an ACTIVE owned hold and marks its allocations BOOKED under a
 * {@code PENDING_PAYMENT} booking with a persisted {@code paymentExpiresAt}. Payment confirmation
 * remains deferred; unpaid bookings are expired by {@link BookingExpiryService}.
 *
 * <p><strong>Ownership:</strong> the hold must already belong to the authenticated customer
 * ({@code seat_holds.user_id}). Anonymous holds ({@code user_id IS NULL}) cannot be booked — UUID
 * knowledge is not ownership. Create the hold while authenticated first.
 *
 * <p><strong>Idempotency:</strong> same user + key + fingerprint returns the existing booking.
 * Concurrent same-hold requests are serialized by {@code SELECT … FOR UPDATE} on the hold. If a
 * unique-constraint race still occurs, the insert transaction rolls back and a separate read
 * returns the winner's booking (never continue the rollback-only session).
 */
@Service
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class BookingApplicationService {

    private final BookingRepository bookingRepository;
    private final BookingCreateWorker createWorker;
    private final BookingViewMapper bookingViewMapper;
    private final Clock clock;

    public BookingApplicationService(
            BookingRepository bookingRepository,
            BookingCreateWorker createWorker,
            BookingViewMapper bookingViewMapper,
            Clock clock) {
        this.bookingRepository = bookingRepository;
        this.createWorker = createWorker;
        this.bookingViewMapper = bookingViewMapper;
        this.clock = clock;
    }

    public BookingResponse createFromHold(UUID userId, CreateBookingRequest request) {
        if (userId == null) {
            throw new IllegalArgumentException("Authenticated user is required");
        }
        if (request == null || request.holdId() == null) {
            throw new IllegalArgumentException("holdId is required");
        }

        String idempotencyKey = normalizeRequired(request.idempotencyKey(), "idempotencyKey");
        String fingerprint = fingerprint(request);

        BookingResponse existing = findIdempotent(userId, idempotencyKey, fingerprint);
        if (existing != null) {
            return existing;
        }

        try {
            return createWorker.create(userId, request, idempotencyKey, fingerprint, clock.instant());
        } catch (IdempotentBookingCollisionException exception) {
            // Insert TX rolled back. Resolve the winner in a fresh read-only transaction.
            BookingResponse raced = findIdempotent(userId, idempotencyKey, fingerprint);
            if (raced != null) {
                return raced;
            }
            throw new ApplicationConflictException("Booking conflicts with an existing booking.");
        }
    }

    @Transactional(readOnly = true)
    public BookingResponse getOwnedBooking(UUID userId, UUID bookingId) {
        return bookingViewMapper.toResponse(bookingRepository.findDetailedByIdAndUserId(bookingId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking was not found.")));
    }

    @Transactional(readOnly = true)
    public List<BookingResponse> listOwnedBookings(UUID userId) {
        return bookingViewMapper.toResponses(
                bookingRepository.findDetailedByUserIdOrderByCreatedAtDesc(userId));
    }

    private BookingResponse findIdempotent(UUID userId, String idempotencyKey, String fingerprint) {
        return bookingRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey)
                .map(existing -> {
                    if (!Objects.equals(existing.getRequestFingerprint(), fingerprint)) {
                        throw new ApplicationConflictException(
                                "Idempotency key was reused with a different booking request.");
                    }
                    return bookingViewMapper.toResponse(requireDetailed(existing.getId()));
                })
                .orElse(null);
    }

    private Booking requireDetailed(UUID bookingId) {
        return bookingRepository.findDetailedById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking was not found."));
    }

    /**
     * Transactional create unit. On unique-constraint collision it rethrows a marker exception so
     * Spring rolls back this transaction; the facade then reads the winner outside this TX.
     */
    @Service
    @ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
    static class BookingCreateWorker {

        private final BookingRepository bookingRepository;
        private final SeatHoldRepository seatHoldRepository;
        private final TripSeatAllocationRepository tripSeatAllocationRepository;
        private final TripStopRepository tripStopRepository;
        private final BookingUnpaidProperties unpaidProperties;
        private final BookingViewMapper bookingViewMapper;

        BookingCreateWorker(
                BookingRepository bookingRepository,
                SeatHoldRepository seatHoldRepository,
                TripSeatAllocationRepository tripSeatAllocationRepository,
                TripStopRepository tripStopRepository,
                BookingUnpaidProperties unpaidProperties,
                BookingViewMapper bookingViewMapper) {
            this.bookingRepository = bookingRepository;
            this.seatHoldRepository = seatHoldRepository;
            this.tripSeatAllocationRepository = tripSeatAllocationRepository;
            this.tripStopRepository = tripStopRepository;
            this.unpaidProperties = unpaidProperties;
            this.bookingViewMapper = bookingViewMapper;
        }

        @Transactional
        public BookingResponse create(
                UUID userId,
                CreateBookingRequest request,
                String idempotencyKey,
                String fingerprint,
                java.time.Instant now) {
            // Re-check under the write transaction after the hold lock path may serialize callers.
            var existingByKey = bookingRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey);
            if (existingByKey.isPresent()) {
                Booking existing = existingByKey.get();
                if (!Objects.equals(existing.getRequestFingerprint(), fingerprint)) {
                    throw new ApplicationConflictException(
                            "Idempotency key was reused with a different booking request.");
                }
                return bookingViewMapper.toResponse(requireDetailed(existing.getId()));
            }

            SeatHold hold = seatHoldRepository.findByIdForUpdate(request.holdId())
                    .orElseThrow(() -> new ResourceNotFoundException("Seat hold was not found."));

            // Anonymous holds are not bookable: UUID knowledge is not ownership.
            if (hold.getUserId() == null) {
                throw new ApplicationConflictException(
                        "Seat hold must be created while authenticated before booking.");
            }
            if (!hold.getUserId().equals(userId)) {
                throw new ResourceNotFoundException("Seat hold was not found.");
            }

            if (hold.getStatus() == SeatHoldStatus.CONSUMED) {
                Booking byHold = bookingRepository.findByHoldId(hold.getId()).orElse(null);
                if (byHold != null
                        && byHold.getUserId().equals(userId)
                        && Objects.equals(byHold.getIdempotencyKey(), idempotencyKey)
                        && Objects.equals(byHold.getRequestFingerprint(), fingerprint)) {
                    return bookingViewMapper.toResponse(requireDetailed(byHold.getId()));
                }
                throw new ApplicationConflictException("Seat hold has already been consumed.");
            }
            if (hold.getStatus() == SeatHoldStatus.EXPIRED) {
                throw new ApplicationConflictException("Seat hold has expired.");
            }
            if (hold.getStatus() == SeatHoldStatus.CANCELLED) {
                throw new ApplicationConflictException("Seat hold has been cancelled.");
            }
            if (hold.getStatus() != SeatHoldStatus.ACTIVE) {
                throw new ApplicationConflictException("Seat hold is not active.");
            }
            if (!hold.getExpiresAt().isAfter(now)) {
                throw new ApplicationConflictException("Seat hold has expired.");
            }

            TripStop originStop = tripStopRepository.findById(request.originStopId())
                    .orElseThrow(() -> new IllegalArgumentException("Origin stop was not found."));
            TripStop destinationStop = tripStopRepository.findById(request.destinationStopId())
                    .orElseThrow(() -> new IllegalArgumentException("Destination stop was not found."));
            if (!Objects.equals(originStop.getTripId(), hold.getTripId())
                    || !Objects.equals(destinationStop.getTripId(), hold.getTripId())) {
                throw new IllegalArgumentException("Stops must belong to the hold trip");
            }
            if (originStop.getSequenceNumber() != hold.getOriginSequence()
                    || destinationStop.getSequenceNumber() != hold.getDestinationSequence()) {
                throw new IllegalArgumentException("Requested origin/destination must match the hold segment");
            }

            List<TripSeatAllocation> allocations =
                    tripSeatAllocationRepository.findByHoldIdForUpdate(hold.getId());
            if (allocations.isEmpty()) {
                throw new ApplicationConflictException("Seat hold has no seat allocations.");
            }
            for (TripSeatAllocation allocation : allocations) {
                if (!Objects.equals(allocation.getTripId(), hold.getTripId())) {
                    throw new ApplicationConflictException("Seat hold allocations must belong to one trip.");
                }
                if (allocation.getState() != TripSeatAllocationState.HELD) {
                    throw new ApplicationConflictException("Seat hold allocations are not in HELD state.");
                }
                if (allocation.getOriginSequence() != hold.getOriginSequence()
                        || allocation.getDestinationSequence() != hold.getDestinationSequence()) {
                    throw new ApplicationConflictException(
                            "Seat hold allocations do not match the hold segment.");
                }
            }

            Map<UUID, BookingPassengerRequest> passengersBySeat = mapPassengers(request.passengers());
            if (passengersBySeat.size() != allocations.size()) {
                throw new IllegalArgumentException("Passenger count must match held seat count");
            }
            for (TripSeatAllocation allocation : allocations) {
                if (!passengersBySeat.containsKey(allocation.getInventory().getId())) {
                    throw new IllegalArgumentException(
                            "Each held seat requires a matching passenger seatInventoryId");
                }
            }

            Trip trip = hold.getTrip();
            if (trip == null
                    || trip.getId() == null
                    || trip.getOperator() == null
                    || trip.getOperator().getId() == null) {
                throw new IllegalStateException("Hold trip/operator must be persisted");
            }

            BigDecimal unitFare = trip.getBaseFare().setScale(2, RoundingMode.HALF_UP);
            BigDecimal baseTotal = unitFare.multiply(BigDecimal.valueOf(allocations.size()));
            Instant paymentExpiresAt = now.plusSeconds(unpaidProperties.getTtlSeconds());

            Booking booking = new Booking(
                    newBookingReference(),
                    userId,
                    hold.getTripId(),
                    trip.getOperator().getId(),
                    hold.getId(),
                    hold.getOriginSequence(),
                    hold.getDestinationSequence(),
                    originStop.getId(),
                    destinationStop.getId(),
                    baseTotal,
                    baseTotal,
                    idempotencyKey,
                    fingerprint,
                    paymentExpiresAt);

            for (TripSeatAllocation allocation : allocations) {
                TripSeatInventory inventory = allocation.getInventory();
                BookingPassengerRequest passengerRequest = passengersBySeat.get(inventory.getId());
                BookingPassenger passenger = new BookingPassenger(
                        booking,
                        passengerRequest.fullName(),
                        passengerRequest.age(),
                        passengerRequest.gender());
                booking.addPassenger(passenger);
                booking.addItem(new BookingItem(
                        booking,
                        inventory.getId(),
                        passenger,
                        inventory.getSeatNumber(),
                        inventory.getSeatType(),
                        hold.getOriginSequence(),
                        hold.getDestinationSequence(),
                        unitFare,
                        unitFare));
            }

            try {
                booking = bookingRepository.saveAndFlush(booking);
            } catch (DataIntegrityViolationException exception) {
                // Do not read/return inside this TX — it is rollback-only after DIV.
                throw new IdempotentBookingCollisionException(exception);
            }

            booking = requireDetailed(booking.getId());
            Map<UUID, BookingItem> itemsByInventory = new LinkedHashMap<>();
            for (BookingItem item : booking.getItems()) {
                itemsByInventory.put(item.getInventoryId(), item);
            }

            for (TripSeatAllocation allocation : allocations) {
                BookingItem item = itemsByInventory.get(allocation.getInventory().getId());
                if (item == null || item.getId() == null) {
                    throw new IllegalStateException("Booking item missing for allocation inventory");
                }
                allocation.markBooked(item.getId());
            }
            tripSeatAllocationRepository.saveAllAndFlush(allocations);

            hold.consume();
            seatHoldRepository.saveAndFlush(hold);

            return bookingViewMapper.toResponse(booking);
        }

        private Booking requireDetailed(UUID bookingId) {
            return bookingRepository.findDetailedById(bookingId)
                    .orElseThrow(() -> new ResourceNotFoundException("Booking was not found."));
        }
    }

    static final class IdempotentBookingCollisionException extends RuntimeException {
        IdempotentBookingCollisionException(Throwable cause) {
            super(cause);
        }
    }

    private static Map<UUID, BookingPassengerRequest> mapPassengers(List<BookingPassengerRequest> passengers) {
        Map<UUID, BookingPassengerRequest> mapped = new LinkedHashMap<>();
        for (BookingPassengerRequest passenger : passengers) {
            if (passenger == null || passenger.seatInventoryId() == null) {
                throw new IllegalArgumentException("Passenger seatInventoryId is required");
            }
            if (mapped.put(passenger.seatInventoryId(), passenger) != null) {
                throw new IllegalArgumentException("Passenger seatInventoryId values must be unique");
            }
        }
        return mapped;
    }

    private static String fingerprint(CreateBookingRequest request) {
        StringBuilder builder = new StringBuilder();
        builder.append(request.holdId()).append('|')
                .append(request.originStopId()).append('|')
                .append(request.destinationStopId()).append('|');
        request.passengers().stream()
                .sorted(Comparator.comparing(p -> p.seatInventoryId().toString()))
                .forEach(p -> builder
                        .append(p.seatInventoryId()).append(':')
                        .append(normalizeName(p.fullName())).append(':')
                        .append(p.age() == null ? "" : p.age()).append(':')
                        .append(p.gender() == null ? "" : p.gender().trim().toLowerCase())
                        .append(';'));
        return sha256Hex(builder.toString());
    }

    private static String normalizeName(String fullName) {
        return fullName == null ? "" : fullName.trim().toLowerCase().replaceAll("\\s+", " ");
    }

    private static String normalizeRequired(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private static String newBookingReference() {
        String raw = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        return "BB" + raw;
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

}
