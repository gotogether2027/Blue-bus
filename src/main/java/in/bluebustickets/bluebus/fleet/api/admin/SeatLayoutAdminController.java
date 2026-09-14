package in.bluebustickets.bluebus.fleet.api.admin;

import java.util.List;
import java.util.UUID;

import in.bluebustickets.bluebus.fleet.api.admin.dto.CreateSeatLayoutRequest;
import in.bluebustickets.bluebus.fleet.api.admin.dto.SeatLayoutResponse;
import in.bluebustickets.bluebus.fleet.api.admin.dto.UpdateSeatLayoutRequest;
import in.bluebustickets.bluebus.fleet.application.SeatLayoutAdminService;
import in.bluebustickets.bluebus.fleet.domain.SeatLayoutStatus;
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
@RequestMapping("/api/v1/admin/seat-layouts")
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class SeatLayoutAdminController {

    private final SeatLayoutAdminService seatLayoutAdminService;

    public SeatLayoutAdminController(SeatLayoutAdminService seatLayoutAdminService) {
        this.seatLayoutAdminService = seatLayoutAdminService;
    }

    @PostMapping
    public ResponseEntity<SeatLayoutResponse> create(@Valid @RequestBody CreateSeatLayoutRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(seatLayoutAdminService.create(
                request.operatorId(),
                request.name(),
                request.version(),
                request.deckCount(),
                request.rowCount(),
                request.columnCount(),
                request.seats()));
    }

    @GetMapping("/{id}")
    public SeatLayoutResponse get(@PathVariable UUID id) {
        return seatLayoutAdminService.get(id);
    }

    @GetMapping
    public List<SeatLayoutResponse> list(
            @RequestParam(required = false) UUID operatorId,
            @RequestParam(required = false) SeatLayoutStatus status) {
        return seatLayoutAdminService.list(operatorId, status);
    }

    @PutMapping("/{id}")
    public SeatLayoutResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateSeatLayoutRequest request) {
        return seatLayoutAdminService.update(
                id,
                request.name(),
                request.deckCount(),
                request.rowCount(),
                request.columnCount());
    }

    @PostMapping("/{id}/activate")
    public SeatLayoutResponse activate(@PathVariable UUID id) {
        return seatLayoutAdminService.activate(id);
    }

    @PostMapping("/{id}/deactivate")
    public SeatLayoutResponse deactivate(@PathVariable UUID id) {
        return seatLayoutAdminService.deactivate(id);
    }
}
