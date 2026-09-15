package in.bluebustickets.bluebus.booking.api.dto;

import java.util.UUID;

public record BookingPassengerResponse(
        UUID passengerId,
        String fullName,
        Integer age,
        String gender) {
}
