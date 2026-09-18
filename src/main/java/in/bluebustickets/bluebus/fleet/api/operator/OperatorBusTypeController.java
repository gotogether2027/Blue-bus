package in.bluebustickets.bluebus.fleet.api.operator;

import java.util.List;
import java.util.UUID;

import in.bluebustickets.bluebus.fleet.api.admin.dto.BusTypeResponse;
import in.bluebustickets.bluebus.fleet.application.OperatorBusTypeQueryService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only active bus-type catalog for authenticated operator members.
 */
@RestController
@RequestMapping("/api/v1/operator/{operatorId}/bus-types")
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class OperatorBusTypeController {

    private final OperatorBusTypeQueryService operatorBusTypeQueryService;

    public OperatorBusTypeController(OperatorBusTypeQueryService operatorBusTypeQueryService) {
        this.operatorBusTypeQueryService = operatorBusTypeQueryService;
    }

    @GetMapping
    @ResponseStatus(HttpStatus.OK)
    public List<BusTypeResponse> listActive(@PathVariable UUID operatorId) {
        return operatorBusTypeQueryService.listActive(operatorId);
    }
}
