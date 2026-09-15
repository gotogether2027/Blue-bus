package in.bluebustickets.bluebus.scheduling.application;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import in.bluebustickets.bluebus.foundation.api.error.ResourceNotFoundException;
import in.bluebustickets.bluebus.scheduling.api.dto.TripSearchPointResponse;
import in.bluebustickets.bluebus.scheduling.api.dto.TripSearchResponse;
import in.bluebustickets.bluebus.scheduling.api.dto.TripSearchStopResponse;
import in.bluebustickets.bluebus.scheduling.domain.PointType;
import in.bluebustickets.bluebus.scheduling.domain.Trip;
import in.bluebustickets.bluebus.scheduling.domain.TripPoint;
import in.bluebustickets.bluebus.scheduling.domain.TripStop;
import in.bluebustickets.bluebus.scheduling.repository.LocationRepository;
import in.bluebustickets.bluebus.scheduling.repository.TripPointRepository;
import in.bluebustickets.bluebus.scheduling.repository.TripRepository;
import in.bluebustickets.bluebus.scheduling.repository.TripSearchCandidate;
import in.bluebustickets.bluebus.scheduling.repository.TripStopRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class TripSearchService {

    private final LocationRepository locationRepository;
    private final TripRepository tripRepository;
    private final TripStopRepository tripStopRepository;
    private final TripPointRepository tripPointRepository;
    private final SeatAvailabilityService seatAvailabilityService;

    public TripSearchService(
            LocationRepository locationRepository,
            TripRepository tripRepository,
            TripStopRepository tripStopRepository,
            TripPointRepository tripPointRepository,
            SeatAvailabilityService seatAvailabilityService) {
        this.locationRepository = locationRepository;
        this.tripRepository = tripRepository;
        this.tripStopRepository = tripStopRepository;
        this.tripPointRepository = tripPointRepository;
        this.seatAvailabilityService = seatAvailabilityService;
    }

    @Transactional(readOnly = true)
    public List<TripSearchResponse> search(
            UUID originLocationId,
            UUID destinationLocationId,
            LocalDate serviceDate) {
        if (originLocationId == null || destinationLocationId == null || serviceDate == null) {
            throw new IllegalArgumentException("Origin, destination, and service date are required");
        }
        if (originLocationId.equals(destinationLocationId)) {
            throw new IllegalArgumentException("Origin and destination must be different");
        }
        if (!locationRepository.existsById(originLocationId)) {
            throw new ResourceNotFoundException("Origin location was not found.");
        }
        if (!locationRepository.existsById(destinationLocationId)) {
            throw new ResourceNotFoundException("Destination location was not found.");
        }

        List<TripSearchCandidate> candidates = tripRepository.searchCustomerTrips(
                originLocationId, destinationLocationId, serviceDate);
        if (candidates.isEmpty()) {
            return List.of();
        }

        var tripIds = candidates.stream().map(TripSearchCandidate::getTripId).collect(Collectors.toSet());
        Map<UUID, Trip> trips = tripRepository.findGraphByIdIn(tripIds).stream()
                .collect(Collectors.toMap(Trip::getId, trip -> trip));
        var stopIds = candidates.stream()
                .flatMap(candidate -> java.util.stream.Stream.of(
                        candidate.getOriginStopId(), candidate.getDestinationStopId()))
                .collect(Collectors.toSet());
        Map<UUID, TripStop> stops = tripStopRepository.findWithLocationByIdIn(stopIds).stream()
                .collect(Collectors.toMap(TripStop::getId, stop -> stop));
        Map<UUID, List<TripPoint>> pointsByStop = tripPointRepository
                .findByTripStop_IdInOrderByNameAsc(stopIds)
                .stream()
                .filter(TripPoint::isActive)
                .collect(Collectors.groupingBy(
                        point -> point.getTripStop().getId(),
                        HashMap::new,
                        Collectors.toList()));

        return candidates.stream()
                .map(candidate -> toResponse(candidate, trips, stops, pointsByStop))
                .toList();
    }

    private TripSearchResponse toResponse(
            TripSearchCandidate candidate,
            Map<UUID, Trip> trips,
            Map<UUID, TripStop> stops,
            Map<UUID, List<TripPoint>> pointsByStop) {
        Trip trip = trips.get(candidate.getTripId());
        TripStop origin = stops.get(candidate.getOriginStopId());
        TripStop destination = stops.get(candidate.getDestinationStopId());
        if (trip == null || origin == null || destination == null
                || origin.getSequenceNumber() >= destination.getSequenceNumber()) {
            throw new IllegalStateException("Trip search returned an invalid stop sequence.");
        }
        long availableSeats = seatAvailabilityService.countAvailableSeatsForKnownSegment(
                trip.getId(), origin.getSequenceNumber(), destination.getSequenceNumber());
        return new TripSearchResponse(
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
                trip.getBaseFare(),
                "INR",
                availableSeats,
                mapStop(origin, pointsByStop, point ->
                        point.getPointType() == PointType.BOARDING
                                || point.getPointType() == PointType.BOTH),
                mapStop(destination, pointsByStop, point ->
                        point.getPointType() == PointType.DROPPING
                                || point.getPointType() == PointType.BOTH));
    }

    private static TripSearchStopResponse mapStop(
            TripStop stop,
            Map<UUID, List<TripPoint>> pointsByStop,
            Predicate<TripPoint> filter) {
        var location = stop.getLocation();
        return new TripSearchStopResponse(
                stop.getId(),
                location.getId(),
                stop.getSequenceNumber(),
                location.getCity(),
                location.getState(),
                location.getLocality(),
                stop.getScheduledArrivalAt(),
                stop.getScheduledDepartureAt(),
                pointsByStop.getOrDefault(stop.getId(), List.of()).stream()
                        .filter(filter)
                        .map(point -> new TripSearchPointResponse(
                                point.getId(),
                                point.getName(),
                                point.getPointType(),
                                point.getAddress()))
                        .toList());
    }
}
