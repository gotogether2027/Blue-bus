package in.bluebustickets.bluebus.ticket.application;

import java.time.Clock;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import in.bluebustickets.bluebus.booking.application.BookingApplicationService;
import in.bluebustickets.bluebus.booking.domain.Booking;
import in.bluebustickets.bluebus.booking.domain.BookingItem;
import in.bluebustickets.bluebus.booking.domain.BookingPassenger;
import in.bluebustickets.bluebus.booking.domain.BookingStatus;
import in.bluebustickets.bluebus.booking.repository.BookingRepository;
import in.bluebustickets.bluebus.foundation.api.error.ApplicationConflictException;
import in.bluebustickets.bluebus.foundation.api.error.ResourceNotFoundException;
import in.bluebustickets.bluebus.scheduling.domain.Location;
import in.bluebustickets.bluebus.scheduling.domain.Trip;
import in.bluebustickets.bluebus.scheduling.domain.TripStop;
import in.bluebustickets.bluebus.scheduling.repository.TripRepository;
import in.bluebustickets.bluebus.scheduling.repository.TripStopRepository;
import in.bluebustickets.bluebus.ticket.api.dto.TicketJourneyResponse;
import in.bluebustickets.bluebus.ticket.api.dto.TicketOperatorResponse;
import in.bluebustickets.bluebus.ticket.api.dto.TicketPassengerResponse;
import in.bluebustickets.bluebus.ticket.api.dto.TicketResponse;
import in.bluebustickets.bluebus.ticket.domain.Ticket;
import in.bluebustickets.bluebus.ticket.domain.TicketPassenger;
import in.bluebustickets.bluebus.ticket.repository.TicketRepository;
import jakarta.persistence.EntityManager;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class TicketApplicationService {

    static final String TICKET_ISSUED = "TICKET_ISSUED";
    private static final int MAX_TICKET_NUMBER_ATTEMPTS = 8;

    private final BookingRepository bookingRepository;
    private final BookingApplicationService bookingApplicationService;
    private final TicketRepository ticketRepository;
    private final TripRepository tripRepository;
    private final TripStopRepository tripStopRepository;
    private final TicketNumberGenerator ticketNumberGenerator;
    private final TicketIssuanceWorker issuanceWorker;
    private final Clock clock;

    public TicketApplicationService(
            BookingRepository bookingRepository,
            BookingApplicationService bookingApplicationService,
            TicketRepository ticketRepository,
            TripRepository tripRepository,
            TripStopRepository tripStopRepository,
            TicketNumberGenerator ticketNumberGenerator,
            TicketIssuanceWorker issuanceWorker,
            Clock clock) {
        this.bookingRepository = bookingRepository;
        this.bookingApplicationService = bookingApplicationService;
        this.ticketRepository = ticketRepository;
        this.tripRepository = tripRepository;
        this.tripStopRepository = tripStopRepository;
        this.ticketNumberGenerator = ticketNumberGenerator;
        this.issuanceWorker = issuanceWorker;
        this.clock = clock;
    }

    /**
     * Idempotent issuance for a confirmed booking owned by {@code userId}.
     * Concurrent races resolve via {@code uq_tickets_booking}.
     */
    public TicketResponse issueForBooking(UUID userId, UUID bookingId) {
        Ticket existing = ticketRepository.findDetailedByBookingId(bookingId).orElse(null);
        if (existing != null) {
            requireOwner(existing, userId);
            return toResponse(existing);
        }

        Booking booking = bookingRepository.findDetailedByIdAndUserId(bookingId, userId).orElse(null);
        if (booking == null) {
            throw new ResourceNotFoundException("Booking was not found.");
        }
        if (booking.getStatus() != BookingStatus.CONFIRMED) {
            throw new ApplicationConflictException("Only confirmed bookings can issue a ticket.");
        }

        JourneySnapshot journey = loadJourneySnapshot(booking);
        Instant issuedAt = clock.instant();
        UUID ticketId = persistWithRetries(booking.getId(), issuedAt, journey);
        return toResponse(requireDetailed(ticketId));
    }

    /**
     * System/outbox path: issue for a confirmed booking without customer ownership checks.
     * Joins the caller's transaction so ticket + {@code TICKET_ISSUED} stay atomic with the
     * outbox mark-published step. If the booking is no longer {@code CONFIRMED} because trip
     * cancellation already cascaded it, returns {@code null} without creating a ticket so the
     * outbox event can be marked published. Premature events for {@code PENDING_PAYMENT} still
     * conflict so they can retry until confirmation.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Ticket issueForConfirmedBookingInCurrentTransaction(UUID bookingId) {
        Booking locked = bookingRepository.findByIdForUpdate(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking was not found."));

        Ticket existing = ticketRepository.findDetailedByBookingId(bookingId).orElse(null);
        if (existing != null) {
            ensureTicketIssuedEvent(existing, clock.instant());
            return existing;
        }

        if (locked.getStatus() != BookingStatus.CONFIRMED) {
            if (locked.getStatus() == BookingStatus.PENDING_PAYMENT
                    || locked.getStatus() == BookingStatus.INITIATED) {
                throw new ApplicationConflictException("Only confirmed bookings can issue a ticket.");
            }
            // Trip cancellation (or other terminal transition) already moved the booking off CONFIRMED.
            // Consume the outbox event without creating a ticket or retrying forever.
            return null;
        }

        Booking booking = bookingRepository.findDetailedById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking was not found."));
        JourneySnapshot journey = loadJourneySnapshot(booking);
        Instant issuedAt = clock.instant();
        UUID ticketId = issuanceWorker.persistInCurrentTransaction(
                booking.getId(), ticketNumberGenerator.next(), issuedAt, journey);
        return requireDetailed(ticketId);
    }

    @Transactional(readOnly = true)
    public TicketResponse getOwned(UUID userId, UUID ticketId) {
        Ticket ticket = ticketRepository.findDetailedByIdAndUserId(ticketId, userId).orElse(null);
        if (ticket == null) {
            throw new ResourceNotFoundException("Ticket was not found.");
        }
        return toResponse(ticket);
    }

    @Transactional(readOnly = true)
    public TicketResponse getOwnedByBooking(UUID userId, UUID bookingId) {
        bookingApplicationService.requireOwnedBooking(userId, bookingId);
        Ticket ticket = ticketRepository.findDetailedByBookingId(bookingId).orElse(null);
        if (ticket == null || !ticket.getUserId().equals(userId)) {
            throw new ResourceNotFoundException("Ticket was not found.");
        }
        return toResponse(ticket);
    }

    private UUID persistWithRetries(UUID bookingId, Instant issuedAt, JourneySnapshot journey) {
        for (int attempt = 0; attempt < MAX_TICKET_NUMBER_ATTEMPTS; attempt++) {
            String ticketNumber = ticketNumberGenerator.next();
            try {
                return issuanceWorker.persist(bookingId, ticketNumber, issuedAt, journey);
            } catch (DataIntegrityViolationException exception) {
                Ticket raced = ticketRepository.findDetailedByBookingId(bookingId).orElse(null);
                if (raced != null) {
                    return raced.getId();
                }
            }
        }
        throw new ApplicationConflictException("Ticket number could not be allocated.");
    }

    private void ensureTicketIssuedEvent(Ticket ticket, Instant now) {
        issuanceWorker.ensureTicketIssued(ticket, now);
    }

    private JourneySnapshot loadJourneySnapshot(Booking booking) {
        Trip trip = tripRepository.findGraphByIdIn(Set.of(booking.getTripId())).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Booking trip was not found."));
        List<TripStop> stops = tripStopRepository.findWithLocationByIdIn(Set.of(
                booking.getOriginTripStopId(), booking.getDestinationTripStopId()));
        TripStop origin = stops.stream()
                .filter(stop -> stop.getId().equals(booking.getOriginTripStopId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Origin stop was not found."));
        TripStop destination = stops.stream()
                .filter(stop -> stop.getId().equals(booking.getDestinationTripStopId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Destination stop was not found."));

        String originName = stopDisplayName(origin);
        String destinationName = stopDisplayName(destination);
        Instant departure = origin.getScheduledDepartureAt() != null
                ? origin.getScheduledDepartureAt()
                : trip.getScheduledDepartureAt();
        Instant arrival = destination.getScheduledArrivalAt() != null
                ? destination.getScheduledArrivalAt()
                : trip.getScheduledArrivalAt();
        if (departure == null || arrival == null) {
            throw new IllegalStateException("Trip schedule snapshot is incomplete.");
        }
        if (booking.getItems().isEmpty()) {
            throw new ApplicationConflictException("Confirmed booking has no seats to ticket.");
        }
        return new JourneySnapshot(
                trip.getOperator().getDisplayName(),
                originName,
                destinationName,
                departure,
                arrival);
    }

    private Ticket requireDetailed(UUID ticketId) {
        return ticketRepository.findDetailedById(ticketId)
                .orElseThrow(() -> new ResourceNotFoundException("Ticket was not found."));
    }

    private static void requireOwner(Ticket ticket, UUID userId) {
        if (!ticket.getUserId().equals(userId)) {
            throw new ResourceNotFoundException("Ticket was not found.");
        }
    }

    private static String stopDisplayName(TripStop stop) {
        Location location = stop.getLocation();
        if (location == null || location.getCity() == null || location.getCity().isBlank()) {
            throw new IllegalStateException("Trip stop location snapshot is incomplete.");
        }
        String city = location.getCity().trim();
        if (location.getState() != null && !location.getState().isBlank()) {
            return city + ", " + location.getState().trim();
        }
        return city;
    }

    static TicketResponse toResponse(Ticket ticket) {
        List<TicketPassengerResponse> passengers = ticket.getPassengers().stream()
                .map(passenger -> new TicketPassengerResponse(
                        passenger.getPassengerName(),
                        passenger.getAge(),
                        passenger.getGender(),
                        passenger.getSeatLabel(),
                        passenger.getFareAmount(),
                        passenger.getCurrency()))
                .toList();
        return new TicketResponse(
                ticket.getId(),
                ticket.getTicketNumber(),
                ticket.getStatus(),
                ticket.getIssuedAt(),
                ticket.getBookingReference(),
                ticket.getBookingId(),
                new TicketOperatorResponse(ticket.getOperatorName()),
                new TicketJourneyResponse(
                        ticket.getOriginStopName(),
                        ticket.getDestinationStopName(),
                        ticket.getScheduledDepartureAt(),
                        ticket.getScheduledArrivalAt()),
                passengers,
                ticket.getTotalAmount(),
                ticket.getCurrency());
    }

    record JourneySnapshot(
            String operatorName,
            String originStopName,
            String destinationStopName,
            Instant scheduledDepartureAt,
            Instant scheduledArrivalAt) {
    }

    @Service
    @ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
    static class TicketIssuanceWorker {

        private final BookingRepository bookingRepository;
        private final TicketRepository ticketRepository;
        private final EntityManager entityManager;

        TicketIssuanceWorker(
                BookingRepository bookingRepository,
                TicketRepository ticketRepository,
                EntityManager entityManager) {
            this.bookingRepository = bookingRepository;
            this.ticketRepository = ticketRepository;
            this.entityManager = entityManager;
        }

        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public UUID persist(
                UUID bookingId,
                String ticketNumber,
                Instant issuedAt,
                JourneySnapshot journey) {
            return createOrReturn(bookingId, ticketNumber, issuedAt, journey);
        }

        /**
         * Joins the caller's transaction. Unique collisions must roll the caller back so the
         * outbox event stays unpublished and retries idempotently.
         */
        @Transactional(propagation = Propagation.MANDATORY)
        public UUID persistInCurrentTransaction(
                UUID bookingId,
                String ticketNumber,
                Instant issuedAt,
                JourneySnapshot journey) {
            return createOrReturn(bookingId, ticketNumber, issuedAt, journey);
        }

        private UUID createOrReturn(
                UUID bookingId,
                String ticketNumber,
                Instant issuedAt,
                JourneySnapshot journey) {
            Ticket already = ticketRepository.findByBookingId(bookingId).orElse(null);
            if (already != null) {
                ensureTicketIssued(already, issuedAt);
                return already.getId();
            }

            Booking booking = bookingRepository.findByIdForUpdate(bookingId)
                    .orElseThrow(() -> new ResourceNotFoundException("Booking was not found."));
            if (booking.getStatus() != BookingStatus.CONFIRMED) {
                throw new ApplicationConflictException("Only confirmed bookings can issue a ticket.");
            }
            booking = bookingRepository.findDetailedById(bookingId)
                    .orElseThrow(() -> new ResourceNotFoundException("Booking was not found."));
            if (booking.getStatus() != BookingStatus.CONFIRMED) {
                throw new ApplicationConflictException("Only confirmed bookings can issue a ticket.");
            }

            Ticket ticket = new Ticket(
                    booking.getId(),
                    ticketNumber,
                    issuedAt,
                    booking.getUserId(),
                    booking.getBookingReference(),
                    booking.getTripId(),
                    booking.getOperatorId(),
                    journey.operatorName(),
                    booking.getOriginTripStopId(),
                    booking.getDestinationTripStopId(),
                    journey.originStopName(),
                    journey.destinationStopName(),
                    journey.scheduledDepartureAt(),
                    journey.scheduledArrivalAt(),
                    booking.getCurrency(),
                    booking.getTotalAmount());

            for (BookingItem item : booking.getItems()) {
                BookingPassenger passenger = item.getPassenger();
                if (passenger == null) {
                    throw new IllegalStateException("Booking item is missing its passenger.");
                }
                ticket.addPassenger(new TicketPassenger(
                        ticket,
                        passenger.getId(),
                        passenger.getFullName(),
                        passenger.getAge(),
                        passenger.getGender(),
                        item.getSeatNumber(),
                        journey.originStopName(),
                        journey.destinationStopName(),
                        item.getTotalAmount(),
                        booking.getCurrency()));
            }

            Ticket saved = ticketRepository.saveAndFlush(ticket);
            ensureTicketIssued(saved, issuedAt);
            return saved.getId();
        }

        /**
         * Inserts {@code TICKET_ISSUED} in the caller's transaction using PostgreSQL
         * {@code ON CONFLICT DO NOTHING} against {@code ux_outbox_ticket_issued_aggregate}.
         * Concurrent duplicates are no-ops without poisoning the transaction.
         */
        void ensureTicketIssued(Ticket ticket, Instant now) {
            String payload = "{\"ticketId\":\"" + ticket.getId()
                    + "\",\"bookingId\":\"" + ticket.getBookingId()
                    + "\",\"ticketNumber\":\"" + ticket.getTicketNumber() + "\"}";
            entityManager.createNativeQuery("""
                    INSERT INTO outbox_events (
                        id, event_type, aggregate_type, aggregate_id, schema_version,
                        correlation_id, causation_id, payload_json, occurred_at, published_at,
                        attempt_count, created_at
                    ) VALUES (
                        :id, :eventType, :aggregateType, :aggregateId, 1,
                        :correlationId, NULL, :payloadJson, :occurredAt, NULL,
                        0, :createdAt
                    )
                    ON CONFLICT (aggregate_id) WHERE (event_type = 'TICKET_ISSUED') DO NOTHING
                    """)
                    .setParameter("id", UUID.randomUUID())
                    .setParameter("eventType", TICKET_ISSUED)
                    .setParameter("aggregateType", "TICKET")
                    .setParameter("aggregateId", ticket.getId())
                    .setParameter("correlationId", ticket.getBookingId().toString())
                    .setParameter("payloadJson", payload)
                    .setParameter("occurredAt", Timestamp.from(now))
                    .setParameter("createdAt", Timestamp.from(now))
                    .executeUpdate();
        }
    }
}
