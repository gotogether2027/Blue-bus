package in.bluebustickets.bluebus.scheduling.api.operator;

import java.util.List;
import java.util.UUID;

import in.bluebustickets.bluebus.scheduling.api.admin.dto.RoutePointResponse;
import in.bluebustickets.bluebus.scheduling.api.admin.dto.RouteResponse;
import in.bluebustickets.bluebus.scheduling.api.admin.dto.RouteStopResponse;
import in.bluebustickets.bluebus.scheduling.api.operator.dto.CreateOperatorRoutePointRequest;
import in.bluebustickets.bluebus.scheduling.api.operator.dto.CreateOperatorRouteRequest;
import in.bluebustickets.bluebus.scheduling.api.operator.dto.CreateOperatorRouteStopRequest;
import in.bluebustickets.bluebus.scheduling.api.operator.dto.UpdateOperatorRoutePointRequest;
import in.bluebustickets.bluebus.scheduling.api.operator.dto.UpdateOperatorRouteRequest;
import in.bluebustickets.bluebus.scheduling.api.operator.dto.UpdateOperatorRouteStopRequest;
import in.bluebustickets.bluebus.scheduling.application.OperatorRouteAdminService;
import in.bluebustickets.bluebus.scheduling.domain.RouteStatus;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/operator/{operatorId}/routes")
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class OperatorRouteController {

    private final OperatorRouteAdminService operatorRouteAdminService;

    public OperatorRouteController(OperatorRouteAdminService operatorRouteAdminService) {
        this.operatorRouteAdminService = operatorRouteAdminService;
    }

    @GetMapping
    @ResponseStatus(HttpStatus.OK)
    public List<RouteResponse> list(
            @PathVariable UUID operatorId,
            @RequestParam(required = false) RouteStatus status) {
        return operatorRouteAdminService.list(operatorId, status);
    }

    @GetMapping("/{routeId}")
    @ResponseStatus(HttpStatus.OK)
    public RouteResponse get(@PathVariable UUID operatorId, @PathVariable UUID routeId) {
        return operatorRouteAdminService.get(operatorId, routeId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RouteResponse create(
            @PathVariable UUID operatorId,
            @Valid @RequestBody CreateOperatorRouteRequest request) {
        return operatorRouteAdminService.create(
                operatorId,
                request.getCode(),
                request.getName(),
                request.getSourceLocationId(),
                request.getDestinationLocationId(),
                request.toAdminStopDefinitions());
    }

    @PatchMapping("/{routeId}")
    @ResponseStatus(HttpStatus.OK)
    public RouteResponse update(
            @PathVariable UUID operatorId,
            @PathVariable UUID routeId,
            @Valid @RequestBody UpdateOperatorRouteRequest request) {
        if (!request.hasSupportedField()) {
            throw new IllegalArgumentException(
                    "At least one of name, sourceLocationId, or destinationLocationId is required.");
        }
        return operatorRouteAdminService.update(
                operatorId,
                routeId,
                request.getName(),
                request.hasName(),
                request.getSourceLocationId(),
                request.hasSourceLocationId(),
                request.getDestinationLocationId(),
                request.hasDestinationLocationId());
    }

    @PostMapping("/{routeId}/activate")
    @ResponseStatus(HttpStatus.OK)
    public RouteResponse activate(@PathVariable UUID operatorId, @PathVariable UUID routeId) {
        return operatorRouteAdminService.activate(operatorId, routeId);
    }

    @PostMapping("/{routeId}/deactivate")
    @ResponseStatus(HttpStatus.OK)
    public RouteResponse deactivate(@PathVariable UUID operatorId, @PathVariable UUID routeId) {
        return operatorRouteAdminService.deactivate(operatorId, routeId);
    }

    @PostMapping("/{routeId}/stops")
    @ResponseStatus(HttpStatus.CREATED)
    public RouteStopResponse addStop(
            @PathVariable UUID operatorId,
            @PathVariable UUID routeId,
            @Valid @RequestBody CreateOperatorRouteStopRequest request) {
        return operatorRouteAdminService.addStop(
                operatorId,
                routeId,
                request.getLocationId(),
                request.getSequenceNumber(),
                request.getStopKind(),
                request.getArrivalOffsetMinutes(),
                request.getDepartureOffsetMinutes(),
                request.getDistanceKm(),
                request.toAdminDefinition().points());
    }

    @PatchMapping("/{routeId}/stops/{stopId}")
    @ResponseStatus(HttpStatus.OK)
    public RouteStopResponse updateStop(
            @PathVariable UUID operatorId,
            @PathVariable UUID routeId,
            @PathVariable UUID stopId,
            @Valid @RequestBody UpdateOperatorRouteStopRequest request) {
        return operatorRouteAdminService.updateStop(
                operatorId,
                routeId,
                stopId,
                request.getLocationId(),
                request.getSequenceNumber(),
                request.getStopKind(),
                request.getArrivalOffsetMinutes(),
                request.getDepartureOffsetMinutes(),
                request.getDistanceKm());
    }

    @PostMapping("/{routeId}/stops/{stopId}/points")
    @ResponseStatus(HttpStatus.CREATED)
    public RoutePointResponse addPoint(
            @PathVariable UUID operatorId,
            @PathVariable UUID routeId,
            @PathVariable UUID stopId,
            @Valid @RequestBody CreateOperatorRoutePointRequest request) {
        return operatorRouteAdminService.addPoint(
                operatorId,
                routeId,
                stopId,
                request.getName(),
                request.getPointType(),
                request.getAddress(),
                request.getLatitude(),
                request.getLongitude());
    }

    @PatchMapping("/{routeId}/stops/{stopId}/points/{pointId}")
    @ResponseStatus(HttpStatus.OK)
    public RoutePointResponse updatePoint(
            @PathVariable UUID operatorId,
            @PathVariable UUID routeId,
            @PathVariable UUID stopId,
            @PathVariable UUID pointId,
            @Valid @RequestBody UpdateOperatorRoutePointRequest request) {
        return operatorRouteAdminService.updatePoint(
                operatorId,
                routeId,
                stopId,
                pointId,
                request.getName(),
                request.getPointType(),
                request.getAddress(),
                request.getLatitude(),
                request.getLongitude());
    }

    @PostMapping("/{routeId}/points/{pointId}/activate")
    @ResponseStatus(HttpStatus.OK)
    public RoutePointResponse activatePoint(
            @PathVariable UUID operatorId,
            @PathVariable UUID routeId,
            @PathVariable UUID pointId) {
        return operatorRouteAdminService.activatePoint(operatorId, routeId, pointId);
    }

    @PostMapping("/{routeId}/points/{pointId}/deactivate")
    @ResponseStatus(HttpStatus.OK)
    public RoutePointResponse deactivatePoint(
            @PathVariable UUID operatorId,
            @PathVariable UUID routeId,
            @PathVariable UUID pointId) {
        return operatorRouteAdminService.deactivatePoint(operatorId, routeId, pointId);
    }
}
