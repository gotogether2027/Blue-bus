package in.bluebustickets.bluebus.ticket.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class TicketApplicationService {

    private static final int MAX_TICKET_NUMBER_ATTEMPTS = 8;

    private final BookingRepository bookingRepository;
    private final TicketRepository ticketRepository;
    private final TripRepository tripRepository;
    private final TripStopRepository tripStopRepository;
    private final TicketNumberGenerator ticketNumberGenerator;
    private final TicketIssuanceWorker issuanceWorker;
    private final Clock clock;

    public TicketApplicationService(
            BookingRepository bookingRepository,
            TicketRepository ticketRepository,
            TripRepository tripRepository,
            TripStopRepository tripStopRepository,
            TicketNumberGenerator ticketNumberGenerator,
            TicketIssuanceWorker issuanceWorker,
            Clock clock) {
        this.bookingRepository = bookingRepository;
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

        for (int attempt = 0; attempt < MAX_TICKET_NUMBER_ATTEMPTS; attempt++) {
            String ticketNumber = ticketNumberGenerator.next();
            try {
                UUID ticketId = issuanceWorker.persist(booking.getId(), ticketNumber, issuedAt, journey);
                return toResponse(requireDetailed(ticketId));
            } catch (DataIntegrityViolationException exception) {
                Ticket raced = ticketRepository.findDetailedByBookingId(bookingId).orElse(null);
                if (raced != null) {
                    requireOwner(raced, userId);
                    return toResponse(raced);
                }
                // Likely ticket_number collision; retry with a new number.
            }
        }
        throw new ApplicationConflictException("Ticket number could not be allocated.");
    }

    @Transactional(readOnly = true)
    public TicketResponse getOwned(UUID userId, UUID ticketId) {
        Ticket ticket = ticketRepository.findDetailedByIdAndUserId(ticketId, userId).orElse(null);
        if (ticket == null) {
            throw new ResourceNotFoundException("Ticket was not found.");
        }
        return toResponse(ticket);
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

        TicketIssuanceWorker(BookingRepository bookingRepository, TicketRepository ticketRepository) {
            this.bookingRepository = bookingRepository;
            this.ticketRepository = ticketRepository;
        }

        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public UUID persist(
                UUID bookingId,
                String ticketNumber,
                Instant issuedAt,
                JourneySnapshot journey) {
            Ticket already = ticketRepository.findByBookingId(bookingId).orElse(null);
            if (already != null) {
                return already.getId();
            }

            Booking booking = bookingRepository.findDetailedById(bookingId)
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

            return ticketRepository.saveAndFlush(ticket).getId();
        }
    }
}
