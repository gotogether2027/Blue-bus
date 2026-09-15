package in.bluebustickets.bluebus.scheduling.api.operator;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import in.bluebustickets.bluebus.scheduling.api.admin.dto.TripResponse;
import in.bluebustickets.bluebus.scheduling.application.OperatorTripQueryService;
import in.bluebustickets.bluebus.scheduling.domain.TripStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/operator/{operatorId}/trips")
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class OperatorTripController {

    private final OperatorTripQueryService operatorTripQueryService;

    public OperatorTripController(OperatorTripQueryService operatorTripQueryService) {
        this.operatorTripQueryService = operatorTripQueryService;
    }

    @GetMapping
    @ResponseStatus(HttpStatus.OK)
    public List<TripResponse> list(
            @PathVariable UUID operatorId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate serviceDate,
            @RequestParam(required = false) TripStatus status) {
        return operatorTripQueryService.list(operatorId, serviceDate, status);
    }

    @GetMapping("/{tripId}")
    @ResponseStatus(HttpStatus.OK)
    public TripResponse get(@PathVariable UUID operatorId, @PathVariable UUID tripId) {
        return operatorTripQueryService.get(operatorId, tripId);
    }
}
