package in.bluebustickets.bluebus.booking.api.dto;

import jakarta.validation.constraints.Size;

public record CancelBookingRequest(
        @Size(max = 500) String reason) {
}
