package in.bluebustickets.bluebus.booking.api.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import in.bluebustickets.bluebus.scheduling.domain.TripStatus;

public record BookingTripResponse(
        UUID tripId,
        LocalDate serviceDate,
        String timeZone,
        Instant scheduledDepartureAt,
        Instant scheduledArrivalAt,
        TripStatus status,
        UUID operatorId,
        String operatorName,
        UUID busId,
        String busRegistrationNumber,
        String busDisplayName,
        UUID routeId,
        String routeCode,
        String routeName,
        BookingTripStopResponse origin,
        BookingTripStopResponse destination) {
}
