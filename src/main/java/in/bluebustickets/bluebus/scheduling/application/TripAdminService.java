package in.bluebustickets.bluebus.scheduling.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import in.bluebustickets.bluebus.fleet.domain.Bus;
import in.bluebustickets.bluebus.fleet.domain.Seat;
import in.bluebustickets.bluebus.fleet.repository.BusRepository;
import in.bluebustickets.bluebus.fleet.repository.SeatRepository;
import in.bluebustickets.bluebus.foundation.api.error.ApplicationConflictException;
import in.bluebustickets.bluebus.foundation.api.error.ResourceNotFoundException;
import in.bluebustickets.bluebus.identity.application.AuthorizationService;
import in.bluebustickets.bluebus.identity.domain.User;
import in.bluebustickets.bluebus.scheduling.api.admin.dto.TripResponse;
import in.bluebustickets.bluebus.scheduling.api.admin.dto.TripSeatInventoryResponse;
import in.bluebustickets.bluebus.scheduling.api.admin.dto.TripStopResponse;
import in.bluebustickets.bluebus.scheduling.domain.Route;
import in.bluebustickets.bluebus.scheduling.domain.RoutePoint;
import in.bluebustickets.bluebus.scheduling.domain.RouteStop;
import in.bluebustickets.bluebus.scheduling.domain.Trip;
import in.bluebustickets.bluebus.scheduling.domain.TripPoint;
import in.bluebustickets.bluebus.scheduling.domain.TripSeatInventory;
import in.bluebustickets.bluebus.scheduling.domain.TripSeatInventoryStatus;
import in.bluebustickets.bluebus.scheduling.domain.TripStatus;
import in.bluebustickets.bluebus.scheduling.domain.TripStop;
import in.bluebustickets.bluebus.scheduling.domain.TripStopStatus;
import in.bluebustickets.bluebus.scheduling.repository.RoutePointRepository;
import in.bluebustickets.bluebus.scheduling.repository.RouteRepository;
import in.bluebustickets.bluebus.scheduling.repository.RouteStopRepository;
import in.bluebustickets.bluebus.scheduling.repository.TripPointRepository;
import in.bluebustickets.bluebus.scheduling.repository.TripRepository;
import in.bluebustickets.bluebus.scheduling.repository.TripSeatInventoryRepository;
import in.bluebustickets.bluebus.scheduling.repository.TripStopRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class TripAdminService {

    private final TripRepository tripRepository;
    private final TripStopRepository tripStopRepository;
    private final TripPointRepository tripPointRepository;
    private final TripSeatInventoryRepository tripSeatInventoryRepository;
    private final BusRepository busRepository;
    private final RouteRepository routeRepository;
    private final RouteStopRepository routeStopRepository;
    private final RoutePointRepository routePointRepository;
    private final SeatRepository seatRepository;
    private final AuthorizationService authorizationService;
    private final TripCancellationBookingPort tripCancellationBookingPort;

    public TripAdminService(
            TripRepository tripRepository,
            TripStopRepository tripStopRepository,
            TripPointRepository tripPointRepository,
            TripSeatInventoryRepository tripSeatInventoryRepository,
            BusRepository busRepository,
            RouteRepository routeRepository,
            RouteStopRepository routeStopRepository,
            RoutePointRepository routePointRepository,
            SeatRepository seatRepository,
            AuthorizationService authorizationService,
            TripCancellationBookingPort tripCancellationBookingPort) {
        this.tripRepository = tripRepository;
        this.tripStopRepository = tripStopRepository;
        this.tripPointRepository = tripPointRepository;
        this.tripSeatInventoryRepository = tripSeatInventoryRepository;
        this.busRepository = busRepository;
        this.routeRepository = routeRepository;
        this.routeStopRepository = routeStopRepository;
        this.routePointRepository = routePointRepository;
        this.seatRepository = seatRepository;
        this.authorizationService = authorizationService;
        this.tripCancellationBookingPort = tripCancellationBookingPort;
    }

    @Transactional
    public TripResponse create(
            UUID busId,
            UUID routeId,
            Instant scheduledDepartureAt,
            Instant scheduledArrivalAt,
            BigDecimal baseFare,
            Instant bookingOpensAt,
            Instant bookingClosesAt,
            String timeZone) {
        authorizationService.requirePlatformAdmin();
        Bus bus = busRepository.findById(busId)
                .orElseThrow(() -> new ResourceNotFoundException("Bus was not found."));
        Route route = routeRepository.findById(routeId)
                .orElseThrow(() -> new ResourceNotFoundException("Route was not found."));

        Trip draftTrip = new Trip(
                bus,
                route,
                scheduledDepartureAt,
                scheduledArrivalAt,
                baseFare,
                bookingOpensAt,
                bookingClosesAt,
                timeZone);
        if (tripRepository.existsByBus_IdAndServiceDateAndScheduledDepartureAt(
                draftTrip.getBus().getId(),
                draftTrip.getServiceDate(),
                draftTrip.getScheduledDepartureAt())) {
            throw new ApplicationConflictException(
                    "A trip already exists for this bus, service date, and scheduled departure.");
        }

        Trip trip = tripRepository.save(draftTrip);
        snapshotRoute(trip, route);
        snapshotSeatInventory(trip);

        return toResponse(trip);
    }

    @Transactional(readOnly = true)
    public TripResponse get(UUID id) {
        authorizationService.requirePlatformAdmin();
        return toResponse(requireTrip(id));
    }

    @Transactional(readOnly = true)
    public List<TripResponse> list(UUID busId, UUID routeId, LocalDate serviceDate, TripStatus status) {
        authorizationService.requirePlatformAdmin();
        List<Trip> trips = findTrips(busId, routeId, serviceDate, status);
        return trips.stream().map(this::toResponse).toList();
    }

    @Transactional
    public TripResponse update(UUID id, BigDecimal baseFare, Instant bookingOpensAt, Instant bookingClosesAt) {
        authorizationService.requirePlatformAdmin();
        Trip trip = requireTrip(id);
        trip.updateCommercialTerms(baseFare, bookingOpensAt, bookingClosesAt);
        return toResponse(trip);
    }

    @Transactional
    public TripResponse activate(UUID id) {
        authorizationService.requirePlatformAdmin();
        Trip trip = requireTrip(id);
        trip.schedule();
        return toResponse(trip);
    }

    @Transactional
    public TripResponse deactivate(UUID id) {
        User admin = authorizationService.requirePlatformAdmin();
        Trip trip = tripRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Trip was not found."));
        if (trip.getStatus() != TripStatus.CANCELLED) {
            tripCancellationBookingPort.cascadePassengersForLockedTrip(trip.getId(), admin.getId());
            trip.cancel();
        }
        return toResponse(trip);
    }

    private void snapshotRoute(Trip trip, Route route) {
        List<RouteStop> routeStops = routeStopRepository.findByRoute_IdOrderBySequenceNumberAsc(route.getId());
        if (routeStops.size() < 2) {
            throw new IllegalArgumentException("Trip route must have at least two ordered stops to snapshot");
        }

        List<UUID> routeStopIds = routeStops.stream().map(RouteStop::getId).toList();
        Map<UUID, List<RoutePoint>> pointsByStop = new LinkedHashMap<>();
        for (UUID routeStopId : routeStopIds) {
            pointsByStop.put(routeStopId, new ArrayList<>());
        }
        for (RoutePoint point : routePointRepository.findByRouteStop_IdInOrderByNameAsc(routeStopIds)) {
            pointsByStop.get(point.getRouteStop().getId()).add(point);
        }

        Instant tripDeparture = trip.getScheduledDepartureAt();
        for (RouteStop routeStop : routeStops) {
            Instant arrival = offsetFrom(tripDeparture, routeStop.getArrivalOffsetMinutes());
            Instant departure = offsetFrom(tripDeparture, routeStop.getDepartureOffsetMinutes());
            TripStop tripStop = tripStopRepository.save(new TripStop(
                    trip,
                    routeStop.getId(),
                    routeStop.getLocation(),
                    routeStop.getSequenceNumber(),
                    routeStop.getStopKind(),
                    TripStopStatus.ACTIVE,
                    arrival,
                    departure,
                    routeStop.getDistanceKm()));

            for (RoutePoint routePoint : pointsByStop.getOrDefault(routeStop.getId(), List.of())) {
                tripPointRepository.save(new TripPoint(
                        tripStop,
                        routePoint.getId(),
                        routePoint.getName(),
                        routePoint.getPointType(),
                        routePoint.getAddress(),
                        routePoint.getLatitude(),
                        routePoint.getLongitude(),
                        routePoint.isActive()));
            }
        }
    }

    private void snapshotSeatInventory(Trip trip) {
        List<Seat> seats = seatRepository.findBySeatLayoutIdOrderByDeckNumberAscRowNumberAscColumnNumberAsc(
                trip.getSeatLayout().getId());
        if (seats.isEmpty()) {
            throw new IllegalArgumentException("Trip bus seat layout must contain at least one seat to snapshot");
        }
        for (Seat seat : seats) {
            if (seat.isSellable()) {
                tripSeatInventoryRepository.save(new TripSeatInventory(trip, seat));
            } else {
                tripSeatInventoryRepository.save(new TripSeatInventory(
                        trip, seat, TripSeatInventoryStatus.BLOCKED, "Not sellable"));
            }
        }
    }

    private List<Trip> findTrips(UUID busId, UUID routeId, LocalDate serviceDate, TripStatus status) {
        if (busId != null && routeId != null && serviceDate != null && status != null) {
            return tripRepository.findByBus_IdAndRoute_IdAndServiceDateAndStatusOrderByScheduledDepartureAtAsc(
                    busId, routeId, serviceDate, status);
        }
        if (busId != null && serviceDate != null) {
            return tripRepository.findByBus_IdAndServiceDateOrderByScheduledDepartureAtAsc(busId, serviceDate);
        }
        if (routeId != null && serviceDate != null) {
            return tripRepository.findByRoute_IdAndServiceDateOrderByScheduledDepartureAtAsc(routeId, serviceDate);
        }
        if (busId != null && status != null) {
            return tripRepository.findByBus_IdAndStatusOrderByScheduledDepartureAtAsc(busId, status);
        }
        if (routeId != null && status != null) {
            return tripRepository.findByRoute_IdAndStatusOrderByScheduledDepartureAtAsc(routeId, status);
        }
        if (serviceDate != null && status != null) {
            return tripRepository.findByServiceDateAndStatusOrderByScheduledDepartureAtAsc(serviceDate, status);
        }
        if (busId != null) {
            return tripRepository.findByBus_IdOrderByScheduledDepartureAtAsc(busId);
        }
        if (routeId != null) {
            return tripRepository.findByRoute_IdOrderByScheduledDepartureAtAsc(routeId);
        }
        if (serviceDate != null) {
            return tripRepository.findByServiceDateOrderByScheduledDepartureAtAsc(serviceDate);
        }
        if (status != null) {
            return tripRepository.findByStatusOrderByScheduledDepartureAtAsc(status);
        }
        return tripRepository.findAllByOrderByScheduledDepartureAtAsc();
    }

    private TripResponse toResponse(Trip trip) {
        List<TripStop> stops = tripStopRepository.findByTripIdOrderBySequenceNumberAsc(trip.getId());
        List<UUID> stopIds = stops.stream().map(TripStop::getId).toList();
        Map<UUID, List<TripPoint>> pointsByStop = new LinkedHashMap<>();
        for (UUID stopId : stopIds) {
            pointsByStop.put(stopId, new ArrayList<>());
        }
        if (!stopIds.isEmpty()) {
            for (TripPoint point : tripPointRepository.findByTripStop_IdInOrderByNameAsc(stopIds)) {
                pointsByStop.get(point.getTripStop().getId()).add(point);
            }
        }
        List<TripStopResponse> stopResponses = stops.stream()
                .map(stop -> TripStopResponse.from(stop, pointsByStop.getOrDefault(stop.getId(), List.of())))
                .toList();
        List<TripSeatInventoryResponse> inventoryResponses = tripSeatInventoryRepository
                .findByTrip_IdOrderByDeckNumberAscRowNumberAscColumnNumberAsc(trip.getId())
                .stream()
                .map(TripSeatInventoryResponse::from)
                .toList();
        return TripResponse.from(trip, stopResponses, inventoryResponses);
    }

    private Trip requireTrip(UUID id) {
        return tripRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Trip was not found."));
    }

    private static Instant offsetFrom(Instant base, Integer offsetMinutes) {
        if (offsetMinutes == null) {
            return null;
        }
        return base.plusSeconds(offsetMinutes.longValue() * 60L);
    }
}
