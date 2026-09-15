package in.bluebustickets.bluebus.booking.api.operator.dto;

import java.util.UUID;

public record OperatorBookingPassengerResponse(
        UUID passengerId,
        String fullName,
        Integer age,
        String gender) {
}
