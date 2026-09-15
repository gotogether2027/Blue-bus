package in.bluebustickets.bluebus.fleet.api.operator;

import java.util.List;
import java.util.UUID;

import in.bluebustickets.bluebus.fleet.api.admin.dto.BusResponse;
import in.bluebustickets.bluebus.fleet.api.operator.dto.CreateOperatorBusRequest;
import in.bluebustickets.bluebus.fleet.api.operator.dto.UpdateOperatorBusRequest;
import in.bluebustickets.bluebus.fleet.application.OperatorBusAdminService;
import in.bluebustickets.bluebus.fleet.application.OperatorBusQueryService;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/operator/{operatorId}/buses")
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class OperatorBusController {

    private final OperatorBusQueryService operatorBusQueryService;
    private final OperatorBusAdminService operatorBusAdminService;

    public OperatorBusController(
            OperatorBusQueryService operatorBusQueryService,
            OperatorBusAdminService operatorBusAdminService) {
        this.operatorBusQueryService = operatorBusQueryService;
        this.operatorBusAdminService = operatorBusAdminService;
    }

    @GetMapping
    @ResponseStatus(HttpStatus.OK)
    public List<BusResponse> list(@PathVariable UUID operatorId) {
        return operatorBusQueryService.list(operatorId);
    }

    @GetMapping("/{busId}")
    @ResponseStatus(HttpStatus.OK)
    public BusResponse get(@PathVariable UUID operatorId, @PathVariable UUID busId) {
        return operatorBusQueryService.get(operatorId, busId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BusResponse create(
            @PathVariable UUID operatorId,
            @Valid @RequestBody CreateOperatorBusRequest request) {
        return operatorBusAdminService.create(
                operatorId,
                request.getBusTypeId(),
                request.getSeatLayoutId(),
                request.getRegistrationNumber(),
                request.getDisplayName());
    }

    @PatchMapping("/{busId}")
    @ResponseStatus(HttpStatus.OK)
    public BusResponse update(
            @PathVariable UUID operatorId,
            @PathVariable UUID busId,
            @Valid @RequestBody UpdateOperatorBusRequest request) {
        if (!request.hasSupportedField()) {
            throw new IllegalArgumentException(
                    "At least one of displayName, busTypeId, or seatLayoutId is required.");
        }
        return operatorBusAdminService.update(
                operatorId,
                busId,
                request.getDisplayName(),
                request.hasDisplayName(),
                request.getBusTypeId(),
                request.hasBusTypeId(),
                request.getSeatLayoutId(),
                request.hasSeatLayoutId());
    }

    @PostMapping("/{busId}/activate")
    @ResponseStatus(HttpStatus.OK)
    public BusResponse activate(@PathVariable UUID operatorId, @PathVariable UUID busId) {
        return operatorBusAdminService.activate(operatorId, busId);
    }

    @PostMapping("/{busId}/deactivate")
    @ResponseStatus(HttpStatus.OK)
    public BusResponse deactivate(@PathVariable UUID operatorId, @PathVariable UUID busId) {
        return operatorBusAdminService.deactivate(operatorId, busId);
    }

    @PostMapping("/{busId}/maintenance")
    @ResponseStatus(HttpStatus.OK)
    public BusResponse maintenance(@PathVariable UUID operatorId, @PathVariable UUID busId) {
        return operatorBusAdminService.markMaintenance(operatorId, busId);
    }
}
