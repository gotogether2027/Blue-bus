package in.bluebustickets.bluebus.scheduling.api;

import java.util.UUID;

import in.bluebustickets.bluebus.scheduling.api.dto.TripSeatAvailabilityResponse;
import in.bluebustickets.bluebus.scheduling.application.TripSeatAvailabilityQueryService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public read-only seat availability for a trip origin/destination pair.
 * Authentication remains optional. Abuse protection is the in-process IP rate limiter.
 */
@RestController
@RequestMapping("/api/v1/trips")
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class TripSeatAvailabilityController {

    private final TripSeatAvailabilityQueryService tripSeatAvailabilityQueryService;

    public TripSeatAvailabilityController(TripSeatAvailabilityQueryService tripSeatAvailabilityQueryService) {
        this.tripSeatAvailabilityQueryService = tripSeatAvailabilityQueryService;
    }

    @GetMapping("/{tripId}/seat-availability")
    public TripSeatAvailabilityResponse getSeatAvailability(
            @PathVariable UUID tripId,
            @RequestParam UUID originStopId,
            @RequestParam UUID destinationStopId) {
        return tripSeatAvailabilityQueryService.getAvailability(tripId, originStopId, destinationStopId);
    }
}
