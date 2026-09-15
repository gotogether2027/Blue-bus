package in.bluebustickets.bluebus.scheduling.application;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import in.bluebustickets.bluebus.foundation.api.error.ApplicationConflictException;
import in.bluebustickets.bluebus.foundation.api.error.ResourceNotFoundException;
import in.bluebustickets.bluebus.identity.application.AuthorizationService;
import in.bluebustickets.bluebus.operator.domain.Operator;
import in.bluebustickets.bluebus.operator.repository.OperatorRepository;
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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class RouteAdminService {

    private final RouteRepository routeRepository;
    private final RouteStopRepository routeStopRepository;
    private final RoutePointRepository routePointRepository;
    private final OperatorRepository operatorRepository;
    private final LocationRepository locationRepository;
    private final AuthorizationService authorizationService;

    public RouteAdminService(
            RouteRepository routeRepository,
            RouteStopRepository routeStopRepository,
            RoutePointRepository routePointRepository,
            OperatorRepository operatorRepository,
            LocationRepository locationRepository,
            AuthorizationService authorizationService) {
        this.routeRepository = routeRepository;
        this.routeStopRepository = routeStopRepository;
        this.routePointRepository = routePointRepository;
        this.operatorRepository = operatorRepository;
        this.locationRepository = locationRepository;
        this.authorizationService = authorizationService;
    }

    @Transactional
    public RouteResponse create(
            UUID operatorId,
            String code,
            String name,
            UUID sourceLocationId,
            UUID destinationLocationId,
            List<RouteStopDefinitionRequest> stops) {
        authorizationService.requirePlatformAdmin();
        Operator operator = requireOperator(operatorId);
        Location source = requireLocation(sourceLocationId);
        Location destination = requireLocation(destinationLocationId);
        String normalizedCode = requireText(code, "Route code is required");
        String normalizedName = requireText(name, "Route name is required");

        if (routeRepository.existsByOperator_IdAndCodeIgnoreCase(operatorId, normalizedCode)) {
            throw new ApplicationConflictException("Route code already exists for this operator.");
        }

        Route route = routeRepository.save(new Route(operator, normalizedCode, normalizedName, source, destination));
        if (stops != null && !stops.isEmpty()) {
            validateStopDefinitions(stops);
            for (RouteStopDefinitionRequest definition : stops) {
                persistStop(route, definition);
            }
        }
        return toResponse(route);
    }

    @Transactional(readOnly = true)
    public RouteResponse get(UUID id) {
        authorizationService.requirePlatformAdmin();
        return toResponse(requireRoute(id));
    }

    @Transactional(readOnly = true)
    public List<RouteResponse> list(UUID operatorId, RouteStatus status) {
        authorizationService.requirePlatformAdmin();
        List<Route> routes;
        if (operatorId != null && status != null) {
            routes = routeRepository.findByOperator_IdAndStatusOrderByCodeAsc(operatorId, status);
        } else if (operatorId != null) {
            routes = routeRepository.findByOperator_IdOrderByCodeAsc(operatorId);
        } else if (status != null) {
            routes = routeRepository.findByStatusOrderByCodeAsc(status);
        } else {
            routes = routeRepository.findAllByOrderByCodeAsc();
        }
        return routes.stream().map(this::toResponse).toList();
    }

    @Transactional
    public RouteResponse update(UUID id, String name, UUID sourceLocationId, UUID destinationLocationId) {
        authorizationService.requirePlatformAdmin();
        Route route = requireRoute(id);
        route.updateMetadata(
                requireText(name, "Route name is required"),
                requireLocation(sourceLocationId),
                requireLocation(destinationLocationId));
        return toResponse(route);
    }

    @Transactional
    public RouteResponse activate(UUID id) {
        authorizationService.requirePlatformAdmin();
        Route route = requireRoute(id);
        route.activate();
        return toResponse(route);
    }

    @Transactional
    public RouteResponse deactivate(UUID id) {
        authorizationService.requirePlatformAdmin();
        Route route = requireRoute(id);
        route.deactivate();
        return toResponse(route);
    }

    @Transactional
    public RouteStopResponse addStop(
            UUID routeId,
            UUID locationId,
            int sequenceNumber,
            StopKind stopKind,
            Integer arrivalOffsetMinutes,
            Integer departureOffsetMinutes,
            BigDecimal distanceKm,
            List<RoutePointDefinitionRequest> points) {
        authorizationService.requirePlatformAdmin();
        Route route = requireRoute(routeId);
        if (routeStopRepository.existsByRoute_IdAndSequenceNumber(routeId, sequenceNumber)) {
            throw new ApplicationConflictException("Route stop sequence already exists on this route.");
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
        return RouteStopResponse.from(stop, pointsFor(stop.getId()));
    }

    @Transactional
    public RouteStopResponse updateStop(
            UUID routeId,
            UUID stopId,
            UUID locationId,
            int sequenceNumber,
            StopKind stopKind,
            Integer arrivalOffsetMinutes,
            Integer departureOffsetMinutes,
            BigDecimal distanceKm) {
        authorizationService.requirePlatformAdmin();
        RouteStop stop = requireStopOnRoute(routeId, stopId);
        if (sequenceNumber != stop.getSequenceNumber()
                && routeStopRepository.existsByRoute_IdAndSequenceNumber(routeId, sequenceNumber)) {
            throw new ApplicationConflictException("Route stop sequence already exists on this route.");
        }
        stop.updateDetails(
                requireLocation(locationId),
                sequenceNumber,
                stopKind,
                arrivalOffsetMinutes,
                departureOffsetMinutes,
                distanceKm);
        return RouteStopResponse.from(stop, pointsFor(stop.getId()));
    }

    @Transactional(readOnly = true)
    public RouteStopResponse getStop(UUID routeId, UUID stopId) {
        authorizationService.requirePlatformAdmin();
        RouteStop stop = requireStopOnRoute(routeId, stopId);
        return RouteStopResponse.from(stop, pointsFor(stop.getId()));
    }

    @Transactional
    public RoutePointResponse addPoint(
            UUID routeId,
            UUID stopId,
            String name,
            PointType pointType,
            String address,
            BigDecimal latitude,
            BigDecimal longitude) {
        authorizationService.requirePlatformAdmin();
        RouteStop stop = requireStopOnRoute(routeId, stopId);
        String normalizedName = requireText(name, "Route point name is required");
        if (pointType == null) {
            throw new IllegalArgumentException("Route point type is required");
        }
        if (routePointRepository.existsByRouteStop_IdAndNameIgnoreCase(stopId, normalizedName)) {
            throw new ApplicationConflictException("Route point name already exists on this stop.");
        }
        RoutePoint point = new RoutePoint(stop, normalizedName, pointType);
        point.updateDetails(normalizedName, pointType, blankToNull(address), latitude, longitude);
        return RoutePointResponse.from(routePointRepository.save(point));
    }

    @Transactional
    public RoutePointResponse updatePoint(
            UUID routeId,
            UUID stopId,
            UUID pointId,
            String name,
            PointType pointType,
            String address,
            BigDecimal latitude,
            BigDecimal longitude) {
        authorizationService.requirePlatformAdmin();
        requireStopOnRoute(routeId, stopId);
        RoutePoint point = requirePointOnStop(stopId, pointId);
        String normalizedName = requireText(name, "Route point name is required");
        if (!normalizedName.equalsIgnoreCase(point.getName())
                && routePointRepository.existsByRouteStop_IdAndNameIgnoreCase(stopId, normalizedName)) {
            throw new ApplicationConflictException("Route point name already exists on this stop.");
        }
        point.updateDetails(normalizedName, pointType, blankToNull(address), latitude, longitude);
        return RoutePointResponse.from(point);
    }

    @Transactional
    public RoutePointResponse activatePoint(UUID routeId, UUID stopId, UUID pointId) {
        authorizationService.requirePlatformAdmin();
        requireStopOnRoute(routeId, stopId);
        RoutePoint point = requirePointOnStop(stopId, pointId);
        point.activate();
        return RoutePointResponse.from(point);
    }

    @Transactional
    public RoutePointResponse deactivatePoint(UUID routeId, UUID stopId, UUID pointId) {
        authorizationService.requirePlatformAdmin();
        requireStopOnRoute(routeId, stopId);
        RoutePoint point = requirePointOnStop(stopId, pointId);
        point.deactivate();
        return RoutePointResponse.from(point);
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

    private Route requireRoute(UUID id) {
        return routeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Route was not found."));
    }

    private RouteStop requireStopOnRoute(UUID routeId, UUID stopId) {
        requireRoute(routeId);
        return routeStopRepository.findByIdAndRoute_Id(stopId, routeId)
                .orElseThrow(() -> new ResourceNotFoundException("Route stop was not found."));
    }

    private RoutePoint requirePointOnStop(UUID stopId, UUID pointId) {
        return routePointRepository.findByIdAndRouteStop_Id(pointId, stopId)
                .orElseThrow(() -> new ResourceNotFoundException("Route point was not found."));
    }

    private Operator requireOperator(UUID id) {
        return operatorRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Operator was not found."));
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
}
