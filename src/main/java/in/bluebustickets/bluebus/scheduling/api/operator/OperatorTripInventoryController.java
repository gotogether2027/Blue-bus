package in.bluebustickets.bluebus.scheduling.api.operator;

import java.util.List;
import java.util.UUID;

import in.bluebustickets.bluebus.scheduling.api.admin.dto.TripSeatInventoryResponse;
import in.bluebustickets.bluebus.scheduling.api.operator.dto.BlockOperatorTripSeatRequest;
import in.bluebustickets.bluebus.scheduling.application.OperatorTripInventoryAdminService;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/operator/{operatorId}/trips/{tripId}/inventory")
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class OperatorTripInventoryController {

    private final OperatorTripInventoryAdminService operatorTripInventoryAdminService;

    public OperatorTripInventoryController(
            OperatorTripInventoryAdminService operatorTripInventoryAdminService) {
        this.operatorTripInventoryAdminService = operatorTripInventoryAdminService;
    }

    @GetMapping
    @ResponseStatus(HttpStatus.OK)
    public List<TripSeatInventoryResponse> list(
            @PathVariable UUID operatorId,
            @PathVariable UUID tripId) {
        return operatorTripInventoryAdminService.list(operatorId, tripId);
    }

    @GetMapping("/{inventoryId}")
    @ResponseStatus(HttpStatus.OK)
    public TripSeatInventoryResponse get(
            @PathVariable UUID operatorId,
            @PathVariable UUID tripId,
            @PathVariable UUID inventoryId) {
        return operatorTripInventoryAdminService.get(operatorId, tripId, inventoryId);
    }

    @PostMapping("/{inventoryId}/block")
    @ResponseStatus(HttpStatus.OK)
    public TripSeatInventoryResponse block(
            @PathVariable UUID operatorId,
            @PathVariable UUID tripId,
            @PathVariable UUID inventoryId,
            @Valid @RequestBody BlockOperatorTripSeatRequest request) {
        return operatorTripInventoryAdminService.block(
                operatorId, tripId, inventoryId, request.getReason());
    }

    @PostMapping("/{inventoryId}/unblock")
    @ResponseStatus(HttpStatus.OK)
    public TripSeatInventoryResponse unblock(
            @PathVariable UUID operatorId,
            @PathVariable UUID tripId,
            @PathVariable UUID inventoryId) {
        return operatorTripInventoryAdminService.unblock(operatorId, tripId, inventoryId);
    }
}
