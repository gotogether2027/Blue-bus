package in.bluebustickets.bluebus.scheduling.api.admin;

import java.util.List;
import java.util.UUID;

import in.bluebustickets.bluebus.scheduling.api.admin.dto.CreateRoutePointRequest;
import in.bluebustickets.bluebus.scheduling.api.admin.dto.CreateRouteRequest;
import in.bluebustickets.bluebus.scheduling.api.admin.dto.CreateRouteStopRequest;
import in.bluebustickets.bluebus.scheduling.api.admin.dto.RoutePointResponse;
import in.bluebustickets.bluebus.scheduling.api.admin.dto.RouteResponse;
import in.bluebustickets.bluebus.scheduling.api.admin.dto.RouteStopResponse;
import in.bluebustickets.bluebus.scheduling.api.admin.dto.UpdateRoutePointRequest;
import in.bluebustickets.bluebus.scheduling.api.admin.dto.UpdateRouteRequest;
import in.bluebustickets.bluebus.scheduling.api.admin.dto.UpdateRouteStopRequest;
import in.bluebustickets.bluebus.scheduling.application.RouteAdminService;
import in.bluebustickets.bluebus.scheduling.domain.RouteStatus;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/routes")
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class RouteAdminController {

    private final RouteAdminService routeAdminService;

    public RouteAdminController(RouteAdminService routeAdminService) {
        this.routeAdminService = routeAdminService;
    }

    @PostMapping
    public ResponseEntity<RouteResponse> create(@Valid @RequestBody CreateRouteRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(routeAdminService.create(
                request.operatorId(),
                request.code(),
                request.name(),
                request.sourceLocationId(),
                request.destinationLocationId(),
                request.stops()));
    }

    @GetMapping("/{id}")
    public RouteResponse get(@PathVariable UUID id) {
        return routeAdminService.get(id);
    }

    @GetMapping
    public List<RouteResponse> list(
            @RequestParam(required = false) UUID operatorId,
            @RequestParam(required = false) RouteStatus status) {
        return routeAdminService.list(operatorId, status);
    }

    @PutMapping("/{id}")
    public RouteResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateRouteRequest request) {
        return routeAdminService.update(id, request.name(), request.sourceLocationId(), request.destinationLocationId());
    }

    @PostMapping("/{id}/activate")
    public RouteResponse activate(@PathVariable UUID id) {
        return routeAdminService.activate(id);
    }

    @PostMapping("/{id}/deactivate")
    public RouteResponse deactivate(@PathVariable UUID id) {
        return routeAdminService.deactivate(id);
    }

    @PostMapping("/{routeId}/stops")
    public ResponseEntity<RouteStopResponse> addStop(
            @PathVariable UUID routeId,
            @Valid @RequestBody CreateRouteStopRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(routeAdminService.addStop(
                routeId,
                request.locationId(),
                request.sequenceNumber(),
                request.stopKind(),
                request.arrivalOffsetMinutes(),
                request.departureOffsetMinutes(),
                request.distanceKm(),
                request.points()));
    }

    @GetMapping("/{routeId}/stops/{stopId}")
    public RouteStopResponse getStop(@PathVariable UUID routeId, @PathVariable UUID stopId) {
        return routeAdminService.getStop(routeId, stopId);
    }

    @PutMapping("/{routeId}/stops/{stopId}")
    public RouteStopResponse updateStop(
            @PathVariable UUID routeId,
            @PathVariable UUID stopId,
            @Valid @RequestBody UpdateRouteStopRequest request) {
        return routeAdminService.updateStop(
                routeId,
                stopId,
                request.locationId(),
                request.sequenceNumber(),
                request.stopKind(),
                request.arrivalOffsetMinutes(),
                request.departureOffsetMinutes(),
                request.distanceKm());
    }

    @PostMapping("/{routeId}/stops/{stopId}/points")
    public ResponseEntity<RoutePointResponse> addPoint(
            @PathVariable UUID routeId,
            @PathVariable UUID stopId,
            @Valid @RequestBody CreateRoutePointRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(routeAdminService.addPoint(
                routeId,
                stopId,
                request.name(),
                request.pointType(),
                request.address(),
                request.latitude(),
                request.longitude()));
    }

    @PutMapping("/{routeId}/stops/{stopId}/points/{pointId}")
    public RoutePointResponse updatePoint(
            @PathVariable UUID routeId,
            @PathVariable UUID stopId,
            @PathVariable UUID pointId,
            @Valid @RequestBody UpdateRoutePointRequest request) {
        return routeAdminService.updatePoint(
                routeId,
                stopId,
                pointId,
                request.name(),
                request.pointType(),
                request.address(),
                request.latitude(),
                request.longitude());
    }

    @PostMapping("/{routeId}/stops/{stopId}/points/{pointId}/activate")
    public RoutePointResponse activatePoint(
            @PathVariable UUID routeId,
            @PathVariable UUID stopId,
            @PathVariable UUID pointId) {
        return routeAdminService.activatePoint(routeId, stopId, pointId);
    }

    @PostMapping("/{routeId}/stops/{stopId}/points/{pointId}/deactivate")
    public RoutePointResponse deactivatePoint(
            @PathVariable UUID routeId,
            @PathVariable UUID stopId,
            @PathVariable UUID pointId) {
        return routeAdminService.deactivatePoint(routeId, stopId, pointId);
    }
}
