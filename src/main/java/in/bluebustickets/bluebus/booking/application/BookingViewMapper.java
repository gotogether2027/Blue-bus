package in.bluebustickets.bluebus.booking.application;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import in.bluebustickets.bluebus.booking.api.dto.BookingItemResponse;
import in.bluebustickets.bluebus.booking.api.dto.BookingPassengerResponse;
import in.bluebustickets.bluebus.booking.api.dto.BookingResponse;
import in.bluebustickets.bluebus.booking.api.dto.BookingTripPointResponse;
import in.bluebustickets.bluebus.booking.api.dto.BookingTripResponse;
import in.bluebustickets.bluebus.booking.api.dto.BookingTripStopResponse;
import in.bluebustickets.bluebus.booking.api.operator.dto.OperatorBookingItemResponse;
import in.bluebustickets.bluebus.booking.api.operator.dto.OperatorBookingPassengerResponse;
import in.bluebustickets.bluebus.booking.api.operator.dto.OperatorBookingResponse;
import in.bluebustickets.bluebus.booking.domain.Booking;
import in.bluebustickets.bluebus.scheduling.domain.PointType;
import in.bluebustickets.bluebus.scheduling.domain.Trip;
import in.bluebustickets.bluebus.scheduling.domain.TripPoint;
import in.bluebustickets.bluebus.scheduling.domain.TripStop;
import in.bluebustickets.bluebus.scheduling.repository.TripPointRepository;
import in.bluebustickets.bluebus.scheduling.repository.TripRepository;
import in.bluebustickets.bluebus.scheduling.repository.TripStopRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class BookingViewMapper {

    private final TripRepository tripRepository;
    private final TripStopRepository tripStopRepository;
    private final TripPointRepository tripPointRepository;

    public BookingViewMapper(
            TripRepository tripRepository,
            TripStopRepository tripStopRepository,
            TripPointRepository tripPointRepository) {
        this.tripRepository = tripRepository;
        this.tripStopRepository = tripStopRepository;
        this.tripPointRepository = tripPointRepository;
    }

    public BookingResponse toResponse(Booking booking) {
        return toResponses(List.of(booking)).get(0);
    }

    public OperatorBookingResponse toOperatorResponse(Booking booking) {
        return toOperatorResponses(List.of(booking)).get(0);
    }

    public List<BookingResponse> toResponses(Collection<Booking> bookings) {
        if (bookings.isEmpty()) {
            return List.of();
        }
        Map<UUID, Trip> trips = tripRepository.findGraphByIdIn(
                        bookings.stream().map(Booking::getTripId).collect(Collectors.toSet()))
                .stream()
                .collect(Collectors.toMap(Trip::getId, trip -> trip));

        var stopIds = bookings.stream()
                .flatMap(booking -> java.util.stream.Stream.of(
                        booking.getOriginTripStopId(), booking.getDestinationTripStopId()))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, TripStop> stops = stopIds.isEmpty()
                ? Map.of()
                : tripStopRepository.findWithLocationByIdIn(stopIds).stream()
                        .collect(Collectors.toMap(TripStop::getId, stop -> stop));
        Map<UUID, List<TripPoint>> pointsByStop = stopIds.isEmpty()
                ? Map.of()
                : tripPointRepository.findByTripStop_IdInOrderByNameAsc(stopIds).stream()
                        .filter(TripPoint::isActive)
                        .collect(Collectors.groupingBy(
                                point -> point.getTripStop().getId(),
                                HashMap::new,
                                Collectors.toList()));

        return bookings.stream()
                .map(booking -> map(booking, trips, stops, pointsByStop))
                .toList();
    }

    public List<OperatorBookingResponse> toOperatorResponses(Collection<Booking> bookings) {
        if (bookings.isEmpty()) {
            return List.of();
        }
        Map<UUID, Trip> trips = tripRepository.findGraphByIdIn(
                        bookings.stream().map(Booking::getTripId).collect(Collectors.toSet()))
                .stream()
                .collect(Collectors.toMap(Trip::getId, trip -> trip));

        var stopIds = bookings.stream()
                .flatMap(booking -> java.util.stream.Stream.of(
                        booking.getOriginTripStopId(), booking.getDestinationTripStopId()))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, TripStop> stops = stopIds.isEmpty()
                ? Map.of()
                : tripStopRepository.findWithLocationByIdIn(stopIds).stream()
                        .collect(Collectors.toMap(TripStop::getId, stop -> stop));
        Map<UUID, List<TripPoint>> pointsByStop = stopIds.isEmpty()
                ? Map.of()
                : tripPointRepository.findByTripStop_IdInOrderByNameAsc(stopIds).stream()
                        .filter(TripPoint::isActive)
                        .collect(Collectors.groupingBy(
                                point -> point.getTripStop().getId(),
                                HashMap::new,
                                Collectors.toList()));

        return bookings.stream()
                .map(booking -> mapOperator(booking, trips, stops, pointsByStop))
                .toList();
    }

    private static BookingResponse map(
            Booking booking,
            Map<UUID, Trip> trips,
            Map<UUID, TripStop> stops,
            Map<UUID, List<TripPoint>> pointsByStop) {
        List<BookingItemResponse> items = booking.getItems().stream()
                .map(item -> new BookingItemResponse(
                        item.getId(),
                        item.getInventoryId(),
                        item.getPassenger() == null ? null : item.getPassenger().getId(),
                        item.getSeatNumber(),
                        item.getSeatType(),
                        item.getOriginSequence(),
                        item.getDestinationSequence(),
                        item.getBaseAmount(),
                        item.getTotalAmount(),
                        item.getStatus()))
                .toList();
        List<BookingPassengerResponse> passengers = booking.getPassengers().stream()
                .map(passenger -> new BookingPassengerResponse(
                        passenger.getId(),
                        passenger.getFullName(),
                        passenger.getAge(),
                        passenger.getGender()))
                .toList();

        Trip trip = trips.get(booking.getTripId());
        TripStop origin = stops.get(booking.getOriginTripStopId());
        TripStop destination = stops.get(booking.getDestinationTripStopId());
        if (trip == null || origin == null || destination == null) {
            throw new IllegalStateException("Booking trip snapshot references are incomplete.");
        }

        return new BookingResponse(
                booking.getId(),
                booking.getBookingReference(),
                booking.getTripId(),
                booking.getHoldId(),
                booking.getStatus(),
                booking.getOriginSequence(),
                booking.getDestinationSequence(),
                booking.getOriginTripStopId(),
                booking.getDestinationTripStopId(),
                booking.getCurrency(),
                booking.getBaseAmount(),
                booking.getTaxAmount(),
                booking.getFeeAmount(),
                booking.getDiscountAmount(),
                booking.getTotalAmount(),
                booking.getCreatedAt(),
                booking.getPaymentExpiresAt(),
                items,
                passengers,
                new BookingTripResponse(
                        trip.getId(),
                        trip.getServiceDate(),
                        trip.getTimeZone(),
                        trip.getScheduledDepartureAt(),
                        trip.getScheduledArrivalAt(),
                        trip.getStatus(),
                        trip.getOperator().getId(),
                        trip.getOperator().getDisplayName(),
                        trip.getBus().getId(),
                        trip.getBus().getRegistrationNumber(),
                        trip.getBus().getDisplayName(),
                        trip.getRoute().getId(),
                        trip.getRoute().getCode(),
                        trip.getRoute().getName(),
                        mapStop(origin, pointsByStop, point ->
                                point.getPointType() == PointType.BOARDING
                                        || point.getPointType() == PointType.BOTH),
                        mapStop(destination, pointsByStop, point ->
                                point.getPointType() == PointType.DROPPING
                                        || point.getPointType() == PointType.BOTH)));
    }

    private static OperatorBookingResponse mapOperator(
            Booking booking,
            Map<UUID, Trip> trips,
            Map<UUID, TripStop> stops,
            Map<UUID, List<TripPoint>> pointsByStop) {
        BookingResponse customerView = map(booking, trips, stops, pointsByStop);
        List<OperatorBookingItemResponse> items = booking.getItems().stream()
                .map(item -> new OperatorBookingItemResponse(
                        item.getId(),
                        item.getPassenger() == null ? null : item.getPassenger().getId(),
                        item.getSeatNumber(),
                        item.getSeatType(),
                        item.getOriginSequence(),
                        item.getDestinationSequence(),
                        item.getStatus()))
                .toList();
        List<OperatorBookingPassengerResponse> passengers = booking.getPassengers().stream()
                .map(passenger -> new OperatorBookingPassengerResponse(
                        passenger.getId(),
                        passenger.getFullName(),
                        passenger.getAge(),
                        passenger.getGender()))
                .toList();
        return new OperatorBookingResponse(
                customerView.bookingId(),
                customerView.bookingReference(),
                customerView.status(),
                customerView.tripId(),
                customerView.originSequence(),
                customerView.destinationSequence(),
                customerView.originTripStopId(),
                customerView.destinationTripStopId(),
                customerView.currency(),
                customerView.totalAmount(),
                customerView.createdAt(),
                items,
                passengers,
                customerView.trip());
    }

    private static BookingTripStopResponse mapStop(
            TripStop stop,
            Map<UUID, List<TripPoint>> pointsByStop,
            Predicate<TripPoint> pointFilter) {
        var location = stop.getLocation();
        List<BookingTripPointResponse> points = pointsByStop.getOrDefault(stop.getId(), List.of()).stream()
                .filter(pointFilter)
                .map(point -> new BookingTripPointResponse(
                        point.getId(), point.getName(), point.getPointType(), point.getAddress()))
                .toList();
        return new BookingTripStopResponse(
                stop.getId(),
                location.getId(),
                stop.getSequenceNumber(),
                location.getCity(),
                location.getState(),
                location.getLocality(),
                stop.getScheduledArrivalAt(),
                stop.getScheduledDepartureAt(),
                points);
    }
}
