package in.bluebustickets.bluebus.fleet.api.admin;

import java.util.List;
import java.util.UUID;

import in.bluebustickets.bluebus.fleet.api.admin.dto.BusResponse;
import in.bluebustickets.bluebus.fleet.api.admin.dto.CreateBusRequest;
import in.bluebustickets.bluebus.fleet.api.admin.dto.UpdateBusRequest;
import in.bluebustickets.bluebus.fleet.application.BusAdminService;
import in.bluebustickets.bluebus.fleet.domain.BusStatus;
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
@RequestMapping("/api/v1/admin/buses")
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class BusAdminController {

    private final BusAdminService busAdminService;

    public BusAdminController(BusAdminService busAdminService) {
        this.busAdminService = busAdminService;
    }

    @PostMapping
    public ResponseEntity<BusResponse> create(@Valid @RequestBody CreateBusRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(busAdminService.create(
                request.operatorId(),
                request.busTypeId(),
                request.seatLayoutId(),
                request.registrationNumber(),
                request.displayName()));
    }

    @GetMapping("/{id}")
    public BusResponse get(@PathVariable UUID id) {
        return busAdminService.get(id);
    }

    @GetMapping
    public List<BusResponse> list(
            @RequestParam(required = false) UUID operatorId,
            @RequestParam(required = false) BusStatus status) {
        return busAdminService.list(operatorId, status);
    }

    @PutMapping("/{id}")
    public BusResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateBusRequest request) {
        return busAdminService.update(id, request.displayName(), request.busTypeId(), request.seatLayoutId());
    }

    @PostMapping("/{id}/activate")
    public BusResponse activate(@PathVariable UUID id) {
        return busAdminService.activate(id);
    }

    @PostMapping("/{id}/deactivate")
    public BusResponse deactivate(@PathVariable UUID id) {
        return busAdminService.deactivate(id);
    }
}
