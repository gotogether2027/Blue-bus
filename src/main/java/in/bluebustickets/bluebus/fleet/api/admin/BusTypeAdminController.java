package in.bluebustickets.bluebus.fleet.api.admin;

import java.util.List;
import java.util.UUID;

import in.bluebustickets.bluebus.fleet.api.admin.dto.BusTypeResponse;
import in.bluebustickets.bluebus.fleet.api.admin.dto.CreateBusTypeRequest;
import in.bluebustickets.bluebus.fleet.api.admin.dto.UpdateBusTypeRequest;
import in.bluebustickets.bluebus.fleet.application.BusTypeAdminService;
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
@RequestMapping("/api/v1/admin/bus-types")
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class BusTypeAdminController {

    private final BusTypeAdminService busTypeAdminService;

    public BusTypeAdminController(BusTypeAdminService busTypeAdminService) {
        this.busTypeAdminService = busTypeAdminService;
    }

    @PostMapping
    public ResponseEntity<BusTypeResponse> create(@Valid @RequestBody CreateBusTypeRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(BusTypeResponse.from(busTypeAdminService.create(request.code(), request.displayName())));
    }

    @GetMapping("/{id}")
    public BusTypeResponse get(@PathVariable UUID id) {
        return BusTypeResponse.from(busTypeAdminService.get(id));
    }

    @GetMapping
    public List<BusTypeResponse> list(@RequestParam(required = false) Boolean active) {
        return busTypeAdminService.list(active).stream().map(BusTypeResponse::from).toList();
    }

    @PutMapping("/{id}")
    public BusTypeResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateBusTypeRequest request) {
        return BusTypeResponse.from(busTypeAdminService.update(id, request.displayName()));
    }

    @PostMapping("/{id}/activate")
    public BusTypeResponse activate(@PathVariable UUID id) {
        return BusTypeResponse.from(busTypeAdminService.activate(id));
    }

    @PostMapping("/{id}/deactivate")
    public BusTypeResponse deactivate(@PathVariable UUID id) {
        return BusTypeResponse.from(busTypeAdminService.deactivate(id));
    }
}
