package in.bluebustickets.bluebus.scheduling.api.admin.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import in.bluebustickets.bluebus.scheduling.domain.Trip;
import in.bluebustickets.bluebus.scheduling.domain.TripStatus;

public record TripResponse(
        UUID id,
        UUID operatorId,
        UUID busId,
        UUID routeId,
        UUID seatLayoutId,
        LocalDate serviceDate,
        String timeZone,
        Instant scheduledDepartureAt,
        Instant scheduledArrivalAt,
        BigDecimal baseFare,
        Instant bookingOpensAt,
        Instant bookingClosesAt,
        TripStatus status,
        List<TripStopResponse> stops,
        List<TripSeatInventoryResponse> seatInventory) {

    public static TripResponse from(
            Trip trip,
            List<TripStopResponse> stops,
            List<TripSeatInventoryResponse> seatInventory) {
        return new TripResponse(
                trip.getId(),
                trip.getOperator().getId(),
                trip.getBus().getId(),
                trip.getRoute().getId(),
                trip.getSeatLayout().getId(),
                trip.getServiceDate(),
                trip.getTimeZone(),
                trip.getScheduledDepartureAt(),
                trip.getScheduledArrivalAt(),
                trip.getBaseFare(),
                trip.getBookingOpensAt(),
                trip.getBookingClosesAt(),
                trip.getStatus(),
                stops,
                seatInventory);
    }
}
