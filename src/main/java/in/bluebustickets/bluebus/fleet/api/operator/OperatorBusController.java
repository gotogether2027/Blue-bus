package in.bluebustickets.bluebus.fleet.api.operator;

import java.util.List;
import java.util.UUID;

import in.bluebustickets.bluebus.fleet.api.admin.dto.BusResponse;
import in.bluebustickets.bluebus.fleet.application.OperatorBusQueryService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/operator/{operatorId}/buses")
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class OperatorBusController {

    private final OperatorBusQueryService operatorBusQueryService;

    public OperatorBusController(OperatorBusQueryService operatorBusQueryService) {
        this.operatorBusQueryService = operatorBusQueryService;
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
}
