package in.bluebustickets.bluebus.scheduling.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import in.bluebustickets.bluebus.fleet.domain.Bus;
import in.bluebustickets.bluebus.fleet.domain.Seat;
import in.bluebustickets.bluebus.fleet.repository.SeatRepository;
import in.bluebustickets.bluebus.foundation.api.error.ApplicationConflictException;
import in.bluebustickets.bluebus.foundation.api.error.ApplicationForbiddenException;
import in.bluebustickets.bluebus.identity.domain.RoleCode;
import in.bluebustickets.bluebus.operator.application.OperatorAccess;
import in.bluebustickets.bluebus.operator.application.OperatorAuthorizationService;
import in.bluebustickets.bluebus.operator.domain.Operator;
import in.bluebustickets.bluebus.operator.domain.OperatorStatus;
import in.bluebustickets.bluebus.operator.domain.OperatorUser;
import in.bluebustickets.bluebus.operator.domain.OperatorUserStatus;
import in.bluebustickets.bluebus.operator.repository.OperatorRepository;
import in.bluebustickets.bluebus.operator.repository.OperatorUserRepository;
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
import in.bluebustickets.bluebus.scheduling.repository.RouteStopRepository;
import in.bluebustickets.bluebus.scheduling.repository.TripPointRepository;
import in.bluebustickets.bluebus.scheduling.repository.TripRepository;
import in.bluebustickets.bluebus.scheduling.repository.TripSeatInventoryRepository;
import in.bluebustickets.bluebus.scheduling.repository.TripStopRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Operator-scoped trip administration. Create locks bus then route ({@code FOR UPDATE});
 * existing-trip mutations lock the trip row and revalidate ACTIVE {@code OPERATOR_ADMIN}.
 * Same-bus interval overlap among non-{@code CANCELLED} trips is rejected under the bus lock.
 */
@Service
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class OperatorTripAdminService {

    private static final String DUPLICATE_DEPARTURE =
            "A trip already exists for this bus, service date, and scheduled departure.";
    private static final String OVERLAP_CONFLICT = "Bus already has an overlapping trip.";

    @PersistenceContext
    private EntityManager entityManager;

    private final OperatorAuthorizationService operatorAuthorizationService;
    private final OperatorUserRepository operatorUserRepository;
    private final OperatorRepository operatorRepository;
    private final TripRepository tripRepository;
    private final TripStopRepository tripStopRepository;
    private final TripPointRepository tripPointRepository;
    private final TripSeatInventoryRepository tripSeatInventoryRepository;
    private final RouteStopRepository routeStopRepository;
    private final RoutePointRepository routePointRepository;
    private final SeatRepository seatRepository;

    /**
     * Optional test barrier after early authorize and before resource locks. Production null.
     */
    private volatile Runnable afterAuthorizeBeforeLockForTests;

    public OperatorTripAdminService(
            OperatorAuthorizationService operatorAuthorizationService,
            OperatorUserRepository operatorUserRepository,
            OperatorRepository operatorRepository,
            TripRepository tripRepository,
            TripStopRepository tripStopRepository,
            TripPointRepository tripPointRepository,
            TripSeatInventoryRepository tripSeatInventoryRepository,
            RouteStopRepository routeStopRepository,
            RoutePointRepository routePointRepository,
            SeatRepository seatRepository) {
        this.operatorAuthorizationService = operatorAuthorizationService;
        this.operatorUserRepository = operatorUserRepository;
        this.operatorRepository = operatorRepository;
        this.tripRepository = tripRepository;
        this.tripStopRepository = tripStopRepository;
        this.tripPointRepository = tripPointRepository;
        this.tripSeatInventoryRepository = tripSeatInventoryRepository;
        this.routeStopRepository = routeStopRepository;
        this.routePointRepository = routePointRepository;
        this.seatRepository = seatRepository;
    }

    @Transactional
    public TripResponse create(
            UUID operatorId,
            UUID busId,
            UUID routeId,
            Instant scheduledDepartureAt,
            Instant scheduledArrivalAt,
            BigDecimal baseFare,
            Instant bookingOpensAt,
            Instant bookingClosesAt,
            String timeZone) {
        OperatorAccess earlyAccess = operatorAuthorizationService.requireMember(
                operatorId, RoleCode.OPERATOR_ADMIN);
        Runnable barrier = afterAuthorizeBeforeLockForTests;
        if (barrier != null) {
            barrier.run();
        }

        Bus bus = lockBusForUpdate(operatorId, busId);
        Route route = lockRouteForUpdate(operatorId, routeId);
        revalidateCallerAdminAfterLock(operatorId, earlyAccess.userId());

        entityManager.refresh(bus);
        entityManager.refresh(route);
        if (!bus.isActive()) {
            throw new IllegalArgumentException("Trip bus must be active");
        }
        if (!route.isActive()) {
            throw new IllegalArgumentException("Trip route must be active");
        }

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
                bus.getId(), draftTrip.getServiceDate(), draftTrip.getScheduledDepartureAt())) {
            throw new ApplicationConflictException(DUPLICATE_DEPARTURE);
        }
        if (tripRepository.existsOverlappingNonCancelledByBus(
                bus.getId(),
                draftTrip.getScheduledDepartureAt(),
                draftTrip.getScheduledArrivalAt(),
                TripStatus.CANCELLED)) {
            throw new ApplicationConflictException(OVERLAP_CONFLICT);
        }

        try {
            Trip trip = tripRepository.saveAndFlush(draftTrip);
            snapshotRoute(trip, route);
            snapshotSeatInventory(trip);
            entityManager.flush();
            return toResponse(trip);
        } catch (DataIntegrityViolationException exception) {
            if (isExactDepartureUniquenessViolation(exception)) {
                throw new ApplicationConflictException(DUPLICATE_DEPARTURE);
            }
            throw exception;
        }
    }

    @Transactional
    public TripResponse update(
            UUID operatorId,
            UUID tripId,
            BigDecimal baseFare,
            boolean baseFarePresent,
            Instant bookingOpensAt,
            boolean bookingOpensAtPresent,
            Instant bookingClosesAt,
            boolean bookingClosesAtPresent) {
        if (!baseFarePresent && !bookingOpensAtPresent && !bookingClosesAtPresent) {
            throw new IllegalArgumentException(
                    "At least one of baseFare, bookingOpensAt, or bookingClosesAt is required.");
        }

        Trip trip = lockOwnedTripForAdminMutation(operatorId, tripId);

        BigDecimal nextFare = baseFarePresent ? baseFare : trip.getBaseFare();
        Instant nextOpens = bookingOpensAtPresent ? bookingOpensAt : trip.getBookingOpensAt();
        Instant nextCloses = bookingClosesAtPresent ? bookingClosesAt : trip.getBookingClosesAt();
        if (baseFarePresent && baseFare == null) {
            throw new IllegalArgumentException("baseFare is required when provided.");
        }
        if (bookingOpensAtPresent && bookingOpensAt == null) {
            throw new IllegalArgumentException("bookingOpensAt is required when provided.");
        }
        if (bookingClosesAtPresent && bookingClosesAt == null) {
            throw new IllegalArgumentException("bookingClosesAt is required when provided.");
        }

        trip.updateCommercialTerms(nextFare, nextOpens, nextCloses);
        entityManager.flush();
        return toResponse(trip);
    }

    @Transactional
    public TripResponse schedule(UUID operatorId, UUID tripId) {
        Trip trip = lockOwnedTripForAdminMutation(operatorId, tripId);
        trip.schedule();
        entityManager.flush();
        return toResponse(trip);
    }

    @Transactional
    public TripResponse cancel(UUID operatorId, UUID tripId) {
        Trip trip = lockOwnedTripForAdminMutation(operatorId, tripId);
        trip.cancel();
        entityManager.flush();
        return toResponse(trip);
    }

    private Trip lockOwnedTripForAdminMutation(UUID operatorId, UUID tripId) {
        OperatorAccess earlyAccess = operatorAuthorizationService.requireMember(
                operatorId, RoleCode.OPERATOR_ADMIN);
        Runnable barrier = afterAuthorizeBeforeLockForTests;
        if (barrier != null) {
            barrier.run();
        }
        Trip trip = lockTripForUpdate(operatorId, tripId);
        revalidateCallerAdminAfterLock(operatorId, earlyAccess.userId());
        return trip;
    }

    @SuppressWarnings("unchecked")
    private Bus lockBusForUpdate(UUID operatorId, UUID busId) {
        List<Bus> rows = entityManager.createNativeQuery("""
                SELECT *
                FROM buses
                WHERE id = :id
                  AND operator_id = :operatorId
                FOR UPDATE
                """, Bus.class)
                .setParameter("id", busId)
                .setParameter("operatorId", operatorId)
                .getResultList();
        if (rows.isEmpty()) {
            throw OperatorAuthorizationService.hiddenNotFound();
        }
        return rows.get(0);
    }

    @SuppressWarnings("unchecked")
    private Route lockRouteForUpdate(UUID operatorId, UUID routeId) {
        List<Route> rows = entityManager.createNativeQuery("""
                SELECT *
                FROM routes
                WHERE id = :id
                  AND operator_id = :operatorId
                FOR UPDATE
                """, Route.class)
                .setParameter("id", routeId)
                .setParameter("operatorId", operatorId)
                .getResultList();
        if (rows.isEmpty()) {
            throw OperatorAuthorizationService.hiddenNotFound();
        }
        return rows.get(0);
    }

    @SuppressWarnings("unchecked")
    private Trip lockTripForUpdate(UUID operatorId, UUID tripId) {
        List<Trip> rows = entityManager.createNativeQuery("""
                SELECT *
                FROM trips
                WHERE id = :id
                  AND operator_id = :operatorId
                FOR UPDATE
                """, Trip.class)
                .setParameter("id", tripId)
                .setParameter("operatorId", operatorId)
                .getResultList();
        if (rows.isEmpty()) {
            throw OperatorAuthorizationService.hiddenNotFound();
        }
        return rows.get(0);
    }

    private void revalidateCallerAdminAfterLock(UUID operatorId, UUID callerUserId) {
        Operator operator = operatorRepository.findById(operatorId)
                .orElseThrow(OperatorAuthorizationService::hiddenNotFound);
        entityManager.refresh(operator);
        if (operator.getStatus() != OperatorStatus.ACTIVE) {
            throw new ApplicationForbiddenException();
        }

        OperatorUser callerMembership = operatorUserRepository
                .findByOperatorIdAndUserId(operatorId, callerUserId)
                .orElseThrow(OperatorAuthorizationService::hiddenNotFound);
        entityManager.refresh(callerMembership);
        if (callerMembership.getStatus() != OperatorUserStatus.ACTIVE) {
            throw OperatorAuthorizationService.hiddenNotFound();
        }
        if (callerMembership.getRole().getCode() != RoleCode.OPERATOR_ADMIN) {
            throw new ApplicationForbiddenException();
        }
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

    private static Instant offsetFrom(Instant base, Integer offsetMinutes) {
        if (offsetMinutes == null) {
            return null;
        }
        return base.plusSeconds(offsetMinutes.longValue() * 60L);
    }

    static boolean isExactDepartureUniquenessViolation(DataIntegrityViolationException exception) {
        Throwable cursor = exception;
        while (cursor != null) {
            String message = cursor.getMessage();
            if (message != null && message.contains("uq_trips_bus_service_date_departure")) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return false;
    }
}
