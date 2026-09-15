package in.bluebustickets.bluebus.booking.api.operator;

import java.util.List;
import java.util.UUID;

import in.bluebustickets.bluebus.booking.api.operator.dto.OperatorBookingResponse;
import in.bluebustickets.bluebus.booking.application.OperatorTripBookingQueryService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/operator/{operatorId}/trips/{tripId}/bookings")
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class OperatorTripBookingController {

    private final OperatorTripBookingQueryService operatorTripBookingQueryService;

    public OperatorTripBookingController(OperatorTripBookingQueryService operatorTripBookingQueryService) {
        this.operatorTripBookingQueryService = operatorTripBookingQueryService;
    }

    @GetMapping
    @ResponseStatus(HttpStatus.OK)
    public List<OperatorBookingResponse> list(
            @PathVariable UUID operatorId,
            @PathVariable UUID tripId) {
        return operatorTripBookingQueryService.list(operatorId, tripId);
    }

    @GetMapping("/{bookingId}")
    @ResponseStatus(HttpStatus.OK)
    public OperatorBookingResponse get(
            @PathVariable UUID operatorId,
            @PathVariable UUID tripId,
            @PathVariable UUID bookingId) {
        return operatorTripBookingQueryService.get(operatorId, tripId, bookingId);
    }
}
