package in.bluebustickets.bluebus.booking.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record BookingTripStopResponse(
        UUID tripStopId,
        UUID locationId,
        int sequenceNumber,
        String city,
        String state,
        String locality,
        Instant scheduledArrivalAt,
        Instant scheduledDepartureAt,
        List<BookingTripPointResponse> points) {
}
