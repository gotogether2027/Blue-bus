package in.bluebustickets.bluebus.scheduling.application;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import in.bluebustickets.bluebus.foundation.api.error.ApplicationConflictException;
import in.bluebustickets.bluebus.foundation.api.error.ApplicationForbiddenException;
import in.bluebustickets.bluebus.foundation.api.error.ResourceNotFoundException;
import in.bluebustickets.bluebus.identity.domain.RoleCode;
import in.bluebustickets.bluebus.operator.application.OperatorAccess;
import in.bluebustickets.bluebus.operator.application.OperatorAuthorizationService;
import in.bluebustickets.bluebus.operator.domain.Operator;
import in.bluebustickets.bluebus.operator.domain.OperatorStatus;
import in.bluebustickets.bluebus.operator.domain.OperatorUser;
import in.bluebustickets.bluebus.operator.domain.OperatorUserStatus;
import in.bluebustickets.bluebus.operator.repository.OperatorRepository;
import in.bluebustickets.bluebus.operator.repository.OperatorUserRepository;
import in.bluebustickets.bluebus.scheduling.api.admin.dto.RoutePointDefinitionRequest;
import in.bluebustickets.bluebus.scheduling.api.admin.dto.RoutePointResponse;
import in.bluebustickets.bluebus.scheduling.api.admin.dto.RouteResponse;
import in.bluebustickets.bluebus.scheduling.api.admin.dto.RouteStopDefinitionRequest;
import in.bluebustickets.bluebus.scheduling.api.admin.dto.RouteStopResponse;
import in.bluebustickets.bluebus.scheduling.domain.Location;
import in.bluebustickets.bluebus.scheduling.domain.PointType;
import in.bluebustickets.bluebus.scheduling.domain.Route;
import in.bluebustickets.bluebus.scheduling.domain.RoutePoint;
import in.bluebustickets.bluebus.scheduling.domain.RouteStatus;
import in.bluebustickets.bluebus.scheduling.domain.RouteStop;
import in.bluebustickets.bluebus.scheduling.domain.StopKind;
import in.bluebustickets.bluebus.scheduling.repository.LocationRepository;
import in.bluebustickets.bluebus.scheduling.repository.RoutePointRepository;
import in.bluebustickets.bluebus.scheduling.repository.RouteRepository;
import in.bluebustickets.bluebus.scheduling.repository.RouteStopRepository;
import in.bluebustickets.bluebus.scheduling.repository.TripRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Operator-scoped route administration. Existing-route mutations lock the route row
 * ({@code FOR UPDATE}) and revalidate ACTIVE {@code OPERATOR_ADMIN} membership so concurrent
 * demotion cannot complete a privileged write on a stale authorization snapshot.
 * Structural mutations are refused when any trip references the route.
 */
@Service
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class OperatorRouteAdminService {

    private static final String CODE_CONFLICT = "Route code already exists for this operator.";
    private static final String ENDPOINT_TRIP_CONFLICT =
            "Route source or destination cannot be changed while trips exist for this route.";
    private static final String STOP_TRIP_CONFLICT =
            "Route stops cannot be changed while trips exist for this route.";
    private static final String POINT_TRIP_CONFLICT =
            "Route point details cannot be changed while trips exist for this route.";
    private static final String STOP_SEQUENCE_CONFLICT = "Route stop sequence already exists on this route.";
    private static final String POINT_NAME_CONFLICT = "Route point name already exists on this stop.";

    @PersistenceContext
    private EntityManager entityManager;

    private final OperatorAuthorizationService operatorAuthorizationService;
    private final OperatorUserRepository operatorUserRepository;
    private final OperatorRepository operatorRepository;
    private final RouteRepository routeRepository;
    private final RouteStopRepository routeStopRepository;
    private final RoutePointRepository routePointRepository;
    private final LocationRepository locationRepository;
    private final TripRepository tripRepository;

    /**
     * Optional test barrier after early authorize and before the route row lock. Production null.
     */
    private volatile Runnable afterAuthorizeBeforeLockForTests;

    /**
     * Optional test barrier after create validation and before persist. Production null.
     */
    private volatile Runnable afterValidationBeforeSaveForTests;

    public OperatorRouteAdminService(
            OperatorAuthorizationService operatorAuthorizationService,
            OperatorUserRepository operatorUserRepository,
            OperatorRepository operatorRepository,
            RouteRepository routeRepository,
            RouteStopRepository routeStopRepository,
            RoutePointRepository routePointRepository,
            LocationRepository locationRepository,
            TripRepository tripRepository) {
        this.operatorAuthorizationService = operatorAuthorizationService;
        this.operatorUserRepository = operatorUserRepository;
        this.operatorRepository = operatorRepository;
        this.routeRepository = routeRepository;
        this.routeStopRepository = routeStopRepository;
        this.routePointRepository = routePointRepository;
        this.locationRepository = locationRepository;
        this.tripRepository = tripRepository;
    }

    @Transactional(readOnly = true)
    public List<RouteResponse> list(UUID operatorId, RouteStatus status) {
        operatorAuthorizationService.requireMember(
                operatorId, RoleCode.OPERATOR_ADMIN, RoleCode.OPERATOR_STAFF);
        List<Route> routes = status == null
                ? routeRepository.findByOperator_IdOrderByCodeAsc(operatorId)
                : routeRepository.findByOperator_IdAndStatusOrderByCodeAsc(operatorId, status);
        return routes.stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public RouteResponse get(UUID operatorId, UUID routeId) {
        operatorAuthorizationService.requireMember(
                operatorId, RoleCode.OPERATOR_ADMIN, RoleCode.OPERATOR_STAFF);
        Route route = routeRepository.findByIdAndOperator_Id(routeId, operatorId)
                .orElseThrow(OperatorAuthorizationService::hiddenNotFound);
        return toResponse(route);
    }

    @Transactional
    public RouteResponse create(
            UUID operatorId,
            String code,
            String name,
            UUID sourceLocationId,
            UUID destinationLocationId,
            List<RouteStopDefinitionRequest> stops) {
        operatorAuthorizationService.requireMember(operatorId, RoleCode.OPERATOR_ADMIN);

        Operator operator = operatorRepository.findById(operatorId)
                .orElseThrow(OperatorAuthorizationService::hiddenNotFound);
        Location source = requireLocation(sourceLocationId);
        Location destination = requireLocation(destinationLocationId);
        String normalizedCode = requireText(code, "Route code is required");
        String normalizedName = requireText(name, "Route name is required");

        if (routeRepository.existsByOperator_IdAndCodeIgnoreCase(operatorId, normalizedCode)) {
            throw new ApplicationConflictException(CODE_CONFLICT);
        }

        if (stops != null && !stops.isEmpty()) {
            validateStopDefinitions(stops);
        }

        Runnable afterValidation = afterValidationBeforeSaveForTests;
        if (afterValidation != null) {
            afterValidation.run();
        }

        try {
            Route route = routeRepository.saveAndFlush(
                    new Route(operator, normalizedCode, normalizedName, source, destination));
            if (stops != null && !stops.isEmpty()) {
                for (RouteStopDefinitionRequest definition : stops) {
                    persistStop(route, definition);
                }
            }
            entityManager.flush();
            return toResponse(route);
        } catch (DataIntegrityViolationException exception) {
            if (isRouteCodeUniquenessViolation(exception)) {
                throw new ApplicationConflictException(CODE_CONFLICT);
            }
            throw exception;
        }
    }

    @Transactional
    public RouteResponse update(
            UUID operatorId,
            UUID routeId,
            String name,
            boolean namePresent,
            UUID sourceLocationId,
            boolean sourceLocationIdPresent,
            UUID destinationLocationId,
            boolean destinationLocationIdPresent) {
        if (!namePresent && !sourceLocationIdPresent && !destinationLocationIdPresent) {
            throw new IllegalArgumentException(
                    "At least one of name, sourceLocationId, or destinationLocationId is required.");
        }

        Route route = lockOwnedRouteForAdminMutation(operatorId, routeId);

        boolean endpointsChanging = false;
        Location nextSource = route.getSourceLocation();
        Location nextDestination = route.getDestinationLocation();
        String nextName = route.getName();

        if (namePresent) {
            nextName = requireText(name, "Route name is required");
        }
        if (sourceLocationIdPresent) {
            if (sourceLocationId == null) {
                throw new IllegalArgumentException("sourceLocationId is required when provided.");
            }
            nextSource = requireLocation(sourceLocationId);
            endpointsChanging = endpointsChanging
                    || !Objects.equals(route.getSourceLocation().getId(), nextSource.getId());
        }
        if (destinationLocationIdPresent) {
            if (destinationLocationId == null) {
                throw new IllegalArgumentException("destinationLocationId is required when provided.");
            }
            nextDestination = requireLocation(destinationLocationId);
            endpointsChanging = endpointsChanging
                    || !Objects.equals(route.getDestinationLocation().getId(), nextDestination.getId());
        }

        if (endpointsChanging) {
            rejectIfAnyTripExists(routeId, ENDPOINT_TRIP_CONFLICT);
        }

        route.updateMetadata(nextName, nextSource, nextDestination);
        entityManager.flush();
        return toResponse(route);
    }

    @Transactional
    public RouteResponse activate(UUID operatorId, UUID routeId) {
        Route route = lockOwnedRouteForAdminMutation(operatorId, routeId);
        route.activate();
        entityManager.flush();
        return toResponse(route);
    }

    @Transactional
    public RouteResponse deactivate(UUID operatorId, UUID routeId) {
        Route route = lockOwnedRouteForAdminMutation(operatorId, routeId);
        route.deactivate();
        entityManager.flush();
        return toResponse(route);
    }

    @Transactional
    public RouteStopResponse addStop(
            UUID operatorId,
            UUID routeId,
            UUID locationId,
            int sequenceNumber,
            StopKind stopKind,
            Integer arrivalOffsetMinutes,
            Integer departureOffsetMinutes,
            BigDecimal distanceKm,
            List<RoutePointDefinitionRequest> points) {
        Route route = lockOwnedRouteForAdminMutation(operatorId, routeId);
        rejectIfAnyTripExists(routeId, STOP_TRIP_CONFLICT);
        if (routeStopRepository.existsByRoute_IdAndSequenceNumber(routeId, sequenceNumber)) {
            throw new ApplicationConflictException(STOP_SEQUENCE_CONFLICT);
        }
        RouteStopDefinitionRequest definition = new RouteStopDefinitionRequest(
                locationId,
                sequenceNumber,
                stopKind,
                arrivalOffsetMinutes,
                departureOffsetMinutes,
                distanceKm,
                points);
        RouteStop stop = persistStop(route, definition);
        entityManager.flush();
        return RouteStopResponse.from(stop, pointsFor(stop.getId()));
    }

    @Transactional
    public RouteStopResponse updateStop(
            UUID operatorId,
            UUID routeId,
            UUID stopId,
            UUID locationId,
            int sequenceNumber,
            StopKind stopKind,
            Integer arrivalOffsetMinutes,
            Integer departureOffsetMinutes,
            BigDecimal distanceKm) {
        lockOwnedRouteForAdminMutation(operatorId, routeId);
        rejectIfAnyTripExists(routeId, STOP_TRIP_CONFLICT);
        RouteStop stop = requireStopOnOwnedRoute(routeId, stopId);
        if (sequenceNumber != stop.getSequenceNumber()
                && routeStopRepository.existsByRoute_IdAndSequenceNumber(routeId, sequenceNumber)) {
            throw new ApplicationConflictException(STOP_SEQUENCE_CONFLICT);
        }
        stop.updateDetails(
                requireLocation(locationId),
                sequenceNumber,
                stopKind,
                arrivalOffsetMinutes,
                departureOffsetMinutes,
                distanceKm);
        entityManager.flush();
        return RouteStopResponse.from(stop, pointsFor(stop.getId()));
    }

    @Transactional
    public RoutePointResponse addPoint(
            UUID operatorId,
            UUID routeId,
            UUID stopId,
            String name,
            PointType pointType,
            String address,
            BigDecimal latitude,
            BigDecimal longitude) {
        lockOwnedRouteForAdminMutation(operatorId, routeId);
        rejectIfAnyTripExists(routeId, POINT_TRIP_CONFLICT);
        RouteStop stop = requireStopOnOwnedRoute(routeId, stopId);
        String normalizedName = requireText(name, "Route point name is required");
        if (pointType == null) {
            throw new IllegalArgumentException("Route point type is required");
        }
        if (routePointRepository.existsByRouteStop_IdAndNameIgnoreCase(stopId, normalizedName)) {
            throw new ApplicationConflictException(POINT_NAME_CONFLICT);
        }
        RoutePoint point = new RoutePoint(stop, normalizedName, pointType);
        point.updateDetails(normalizedName, pointType, blankToNull(address), latitude, longitude);
        RoutePoint saved = routePointRepository.save(point);
        entityManager.flush();
        return RoutePointResponse.from(saved);
    }

    @Transactional
    public RoutePointResponse updatePoint(
            UUID operatorId,
            UUID routeId,
            UUID stopId,
            UUID pointId,
            String name,
            PointType pointType,
            String address,
            BigDecimal latitude,
            BigDecimal longitude) {
        lockOwnedRouteForAdminMutation(operatorId, routeId);
        rejectIfAnyTripExists(routeId, POINT_TRIP_CONFLICT);
        requireStopOnOwnedRoute(routeId, stopId);
        RoutePoint point = requirePointOnStop(stopId, pointId);
        String normalizedName = requireText(name, "Route point name is required");
        if (!normalizedName.equalsIgnoreCase(point.getName())
                && routePointRepository.existsByRouteStop_IdAndNameIgnoreCase(stopId, normalizedName)) {
            throw new ApplicationConflictException(POINT_NAME_CONFLICT);
        }
        point.updateDetails(normalizedName, pointType, blankToNull(address), latitude, longitude);
        entityManager.flush();
        return RoutePointResponse.from(point);
    }

    @Transactional
    public RoutePointResponse activatePoint(UUID operatorId, UUID routeId, UUID pointId) {
        lockOwnedRouteForAdminMutation(operatorId, routeId);
        RoutePoint point = requirePointOnOwnedRoute(routeId, pointId);
        point.activate();
        entityManager.flush();
        return RoutePointResponse.from(point);
    }

    @Transactional
    public RoutePointResponse deactivatePoint(UUID operatorId, UUID routeId, UUID pointId) {
        lockOwnedRouteForAdminMutation(operatorId, routeId);
        RoutePoint point = requirePointOnOwnedRoute(routeId, pointId);
        point.deactivate();
        entityManager.flush();
        return RoutePointResponse.from(point);
    }

    private Route lockOwnedRouteForAdminMutation(UUID operatorId, UUID routeId) {
        OperatorAccess earlyAccess = operatorAuthorizationService.requireMember(
                operatorId, RoleCode.OPERATOR_ADMIN);
        Runnable barrier = afterAuthorizeBeforeLockForTests;
        if (barrier != null) {
            barrier.run();
        }
        Route route = lockRouteForUpdate(operatorId, routeId);
        revalidateCallerAdminAfterRouteLock(operatorId, earlyAccess.userId());
        return route;
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

    private void revalidateCallerAdminAfterRouteLock(UUID operatorId, UUID callerUserId) {
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

    private void rejectIfAnyTripExists(UUID routeId, String message) {
        if (tripRepository.existsByRoute_Id(routeId)) {
            throw new ApplicationConflictException(message);
        }
    }

    private RouteStop persistStop(Route route, RouteStopDefinitionRequest definition) {
        Location location = requireLocation(definition.locationId());
        RouteStop stop = routeStopRepository.save(new RouteStop(
                route,
                location,
                definition.sequenceNumber(),
                definition.stopKind(),
                definition.arrivalOffsetMinutes(),
                definition.departureOffsetMinutes(),
                definition.distanceKm()));
        if (definition.points() != null) {
            Set<String> names = new HashSet<>();
            for (RoutePointDefinitionRequest pointDefinition : definition.points()) {
                String pointName = requireText(pointDefinition.name(), "Route point name is required");
                if (!names.add(pointName.toLowerCase())) {
                    throw new ApplicationConflictException("Duplicate route point name within stop: " + pointName);
                }
                RoutePoint point = new RoutePoint(stop, pointName, pointDefinition.pointType());
                point.updateDetails(
                        pointName,
                        pointDefinition.pointType(),
                        blankToNull(pointDefinition.address()),
                        pointDefinition.latitude(),
                        pointDefinition.longitude());
                if (pointDefinition.active() != null && !pointDefinition.active()) {
                    point.deactivate();
                }
                routePointRepository.save(point);
            }
        }
        return stop;
    }

    private void validateStopDefinitions(List<RouteStopDefinitionRequest> stops) {
        Set<Integer> sequences = new HashSet<>();
        for (RouteStopDefinitionRequest stop : stops) {
            if (!sequences.add(stop.sequenceNumber())) {
                throw new ApplicationConflictException(
                        "Duplicate route stop sequence within route: " + stop.sequenceNumber());
            }
            if (stop.locationId() == null) {
                throw new IllegalArgumentException("Route stop location is required");
            }
            if (stop.stopKind() == null) {
                throw new IllegalArgumentException("Route stop kind is required");
            }
        }
    }

    private RouteResponse toResponse(Route route) {
        List<RouteStop> stops = routeStopRepository.findByRoute_IdOrderBySequenceNumberAsc(route.getId());
        List<UUID> stopIds = stops.stream().map(RouteStop::getId).toList();
        Map<UUID, List<RoutePoint>> pointsByStop = new LinkedHashMap<>();
        for (UUID stopId : stopIds) {
            pointsByStop.put(stopId, new ArrayList<>());
        }
        if (!stopIds.isEmpty()) {
            for (RoutePoint point : routePointRepository.findByRouteStop_IdInOrderByNameAsc(stopIds)) {
                pointsByStop.get(point.getRouteStop().getId()).add(point);
            }
        }
        List<RouteStopResponse> stopResponses = stops.stream()
                .map(stop -> RouteStopResponse.from(stop, pointsByStop.getOrDefault(stop.getId(), List.of())))
                .toList();
        return RouteResponse.from(route, stopResponses);
    }

    private List<RoutePoint> pointsFor(UUID stopId) {
        return routePointRepository.findByRouteStop_IdOrderByNameAsc(stopId);
    }

    private RouteStop requireStopOnOwnedRoute(UUID routeId, UUID stopId) {
        return routeStopRepository.findByIdAndRoute_Id(stopId, routeId)
                .orElseThrow(OperatorAuthorizationService::hiddenNotFound);
    }

    private RoutePoint requirePointOnStop(UUID stopId, UUID pointId) {
        return routePointRepository.findByIdAndRouteStop_Id(pointId, stopId)
                .orElseThrow(OperatorAuthorizationService::hiddenNotFound);
    }

    private RoutePoint requirePointOnOwnedRoute(UUID routeId, UUID pointId) {
        return routePointRepository.findByIdAndRouteStop_Route_Id(pointId, routeId)
                .orElseThrow(OperatorAuthorizationService::hiddenNotFound);
    }

    private Location requireLocation(UUID id) {
        return locationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Location was not found."));
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    static boolean isRouteCodeUniquenessViolation(DataIntegrityViolationException exception) {
        Throwable cursor = exception;
        while (cursor != null) {
            String message = cursor.getMessage();
            if (message != null && message.contains("ux_routes_operator_code_lower")) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return false;
    }
}
