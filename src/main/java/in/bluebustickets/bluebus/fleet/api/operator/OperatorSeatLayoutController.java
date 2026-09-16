package in.bluebustickets.bluebus.fleet.api.operator;

import java.util.List;
import java.util.UUID;

import in.bluebustickets.bluebus.fleet.api.admin.dto.SeatLayoutResponse;
import in.bluebustickets.bluebus.fleet.api.operator.dto.CreateOperatorSeatLayoutRequest;
import in.bluebustickets.bluebus.fleet.api.operator.dto.UpdateOperatorSeatLayoutRequest;
import in.bluebustickets.bluebus.fleet.application.OperatorSeatLayoutAdminService;
import in.bluebustickets.bluebus.fleet.domain.SeatLayoutStatus;
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
@RequestMapping("/api/v1/operator/{operatorId}/seat-layouts")
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class OperatorSeatLayoutController {

    private final OperatorSeatLayoutAdminService operatorSeatLayoutAdminService;

    public OperatorSeatLayoutController(OperatorSeatLayoutAdminService operatorSeatLayoutAdminService) {
        this.operatorSeatLayoutAdminService = operatorSeatLayoutAdminService;
    }

    @GetMapping
    @ResponseStatus(HttpStatus.OK)
    public List<SeatLayoutResponse> list(
            @PathVariable UUID operatorId,
            @RequestParam(required = false) SeatLayoutStatus status) {
        return operatorSeatLayoutAdminService.list(operatorId, status);
    }

    @GetMapping("/{layoutId}")
    @ResponseStatus(HttpStatus.OK)
    public SeatLayoutResponse get(@PathVariable UUID operatorId, @PathVariable UUID layoutId) {
        return operatorSeatLayoutAdminService.get(operatorId, layoutId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SeatLayoutResponse create(
            @PathVariable UUID operatorId,
            @Valid @RequestBody CreateOperatorSeatLayoutRequest request) {
        return operatorSeatLayoutAdminService.create(
                operatorId,
                request.getName(),
                request.getVersion(),
                request.getDeckCount(),
                request.getRowCount(),
                request.getColumnCount(),
                request.getSeats());
    }

    @PatchMapping("/{layoutId}")
    @ResponseStatus(HttpStatus.OK)
    public SeatLayoutResponse update(
            @PathVariable UUID operatorId,
            @PathVariable UUID layoutId,
            @Valid @RequestBody UpdateOperatorSeatLayoutRequest request) {
        if (!request.hasSupportedField()) {
            throw new IllegalArgumentException(
                    "At least one of name, deckCount, rowCount, or columnCount is required.");
        }
        return operatorSeatLayoutAdminService.update(
                operatorId,
                layoutId,
                request.getName(),
                request.hasName(),
                request.getDeckCount(),
                request.hasDeckCount(),
                request.getRowCount(),
                request.hasRowCount(),
                request.getColumnCount(),
                request.hasColumnCount());
    }

    @PostMapping("/{layoutId}/activate")
    @ResponseStatus(HttpStatus.OK)
    public SeatLayoutResponse activate(@PathVariable UUID operatorId, @PathVariable UUID layoutId) {
        return operatorSeatLayoutAdminService.activate(operatorId, layoutId);
    }

    @PostMapping("/{layoutId}/deactivate")
    @ResponseStatus(HttpStatus.OK)
    public SeatLayoutResponse deactivate(@PathVariable UUID operatorId, @PathVariable UUID layoutId) {
        return operatorSeatLayoutAdminService.deactivate(operatorId, layoutId);
    }
}
