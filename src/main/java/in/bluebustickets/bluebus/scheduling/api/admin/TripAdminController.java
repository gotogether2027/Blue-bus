package in.bluebustickets.bluebus.scheduling.api.admin;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import in.bluebustickets.bluebus.scheduling.api.admin.dto.CreateTripRequest;
import in.bluebustickets.bluebus.scheduling.api.admin.dto.TripResponse;
import in.bluebustickets.bluebus.scheduling.api.admin.dto.UpdateTripRequest;
import in.bluebustickets.bluebus.scheduling.application.TripAdminService;
import in.bluebustickets.bluebus.scheduling.domain.TripStatus;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/trips")
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class TripAdminController {

    private final TripAdminService tripAdminService;

    public TripAdminController(TripAdminService tripAdminService) {
        this.tripAdminService = tripAdminService;
    }

    @PostMapping
    public ResponseEntity<TripResponse> create(@Valid @RequestBody CreateTripRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(tripAdminService.create(
                request.busId(),
                request.routeId(),
                request.scheduledDepartureAt(),
                request.scheduledArrivalAt(),
                request.baseFare(),
                request.bookingOpensAt(),
                request.bookingClosesAt(),
                request.timeZone()));
    }

    @GetMapping("/{id}")
    public TripResponse get(@PathVariable UUID id) {
        return tripAdminService.get(id);
    }

    @GetMapping
    public List<TripResponse> list(
            @RequestParam(required = false) UUID busId,
            @RequestParam(required = false) UUID routeId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate serviceDate,
            @RequestParam(required = false) TripStatus status) {
        return tripAdminService.list(busId, routeId, serviceDate, status);
    }

    @PutMapping("/{id}")
    public TripResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateTripRequest request) {
        return tripAdminService.update(id, request.baseFare(), request.bookingOpensAt(), request.bookingClosesAt());
    }

    @PostMapping("/{id}/activate")
    public TripResponse activate(@PathVariable UUID id) {
        return tripAdminService.activate(id);
    }

    @PostMapping("/{id}/deactivate")
    public TripResponse deactivate(@PathVariable UUID id) {
        return tripAdminService.deactivate(id);
    }
}
