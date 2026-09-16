package in.bluebustickets.bluebus.scheduling.api.operator;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import in.bluebustickets.bluebus.scheduling.api.admin.dto.TripResponse;
import in.bluebustickets.bluebus.scheduling.api.operator.dto.CreateOperatorTripRequest;
import in.bluebustickets.bluebus.scheduling.api.operator.dto.UpdateOperatorTripRequest;
import in.bluebustickets.bluebus.scheduling.application.OperatorTripAdminService;
import in.bluebustickets.bluebus.scheduling.application.OperatorTripQueryService;
import in.bluebustickets.bluebus.scheduling.domain.TripStatus;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.format.annotation.DateTimeFormat;
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
@RequestMapping("/api/v1/operator/{operatorId}/trips")
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class OperatorTripController {

    private final OperatorTripQueryService operatorTripQueryService;
    private final OperatorTripAdminService operatorTripAdminService;

    public OperatorTripController(
            OperatorTripQueryService operatorTripQueryService,
            OperatorTripAdminService operatorTripAdminService) {
        this.operatorTripQueryService = operatorTripQueryService;
        this.operatorTripAdminService = operatorTripAdminService;
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

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TripResponse create(
            @PathVariable UUID operatorId,
            @Valid @RequestBody CreateOperatorTripRequest request) {
        return operatorTripAdminService.create(
                operatorId,
                request.getBusId(),
                request.getRouteId(),
                request.getScheduledDepartureAt(),
                request.getScheduledArrivalAt(),
                request.getBaseFare(),
                request.getBookingOpensAt(),
                request.getBookingClosesAt(),
                request.getTimeZone());
    }

    @PatchMapping("/{tripId}")
    @ResponseStatus(HttpStatus.OK)
    public TripResponse update(
            @PathVariable UUID operatorId,
            @PathVariable UUID tripId,
            @Valid @RequestBody UpdateOperatorTripRequest request) {
        if (!request.hasSupportedField()) {
            throw new IllegalArgumentException(
                    "At least one of baseFare, bookingOpensAt, or bookingClosesAt is required.");
        }
        return operatorTripAdminService.update(
                operatorId,
                tripId,
                request.getBaseFare(),
                request.hasBaseFare(),
                request.getBookingOpensAt(),
                request.hasBookingOpensAt(),
                request.getBookingClosesAt(),
                request.hasBookingClosesAt());
    }

    @PostMapping("/{tripId}/schedule")
    @ResponseStatus(HttpStatus.OK)
    public TripResponse schedule(@PathVariable UUID operatorId, @PathVariable UUID tripId) {
        return operatorTripAdminService.schedule(operatorId, tripId);
    }

    @PostMapping("/{tripId}/cancel")
    @ResponseStatus(HttpStatus.OK)
    public TripResponse cancel(@PathVariable UUID operatorId, @PathVariable UUID tripId) {
        return operatorTripAdminService.cancel(operatorId, tripId);
    }
}
