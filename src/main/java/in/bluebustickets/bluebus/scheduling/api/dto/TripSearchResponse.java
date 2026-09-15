package in.bluebustickets.bluebus.scheduling.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import in.bluebustickets.bluebus.scheduling.domain.TripStatus;

public record TripSearchResponse(
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
        BigDecimal baseFare,
        String currency,
        long availableSeatCount,
        TripSearchStopResponse origin,
        TripSearchStopResponse destination) {
}
