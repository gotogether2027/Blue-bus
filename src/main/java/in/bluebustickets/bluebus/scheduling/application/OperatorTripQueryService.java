package in.bluebustickets.bluebus.scheduling.application;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import in.bluebustickets.bluebus.identity.domain.RoleCode;
import in.bluebustickets.bluebus.operator.application.OperatorAuthorizationService;
import in.bluebustickets.bluebus.scheduling.api.admin.dto.TripResponse;
import in.bluebustickets.bluebus.scheduling.api.admin.dto.TripSeatInventoryResponse;
import in.bluebustickets.bluebus.scheduling.api.admin.dto.TripStopResponse;
import in.bluebustickets.bluebus.scheduling.domain.Trip;
import in.bluebustickets.bluebus.scheduling.domain.TripPoint;
import in.bluebustickets.bluebus.scheduling.domain.TripStatus;
import in.bluebustickets.bluebus.scheduling.domain.TripStop;
import in.bluebustickets.bluebus.scheduling.repository.TripPointRepository;
import in.bluebustickets.bluebus.scheduling.repository.TripRepository;
import in.bluebustickets.bluebus.scheduling.repository.TripSeatInventoryRepository;
import in.bluebustickets.bluebus.scheduling.repository.TripStopRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class OperatorTripQueryService {

    private final OperatorAuthorizationService operatorAuthorizationService;
    private final TripRepository tripRepository;
    private final TripStopRepository tripStopRepository;
    private final TripPointRepository tripPointRepository;
    private final TripSeatInventoryRepository tripSeatInventoryRepository;

    public OperatorTripQueryService(
            OperatorAuthorizationService operatorAuthorizationService,
            TripRepository tripRepository,
            TripStopRepository tripStopRepository,
            TripPointRepository tripPointRepository,
            TripSeatInventoryRepository tripSeatInventoryRepository) {
        this.operatorAuthorizationService = operatorAuthorizationService;
        this.tripRepository = tripRepository;
        this.tripStopRepository = tripStopRepository;
        this.tripPointRepository = tripPointRepository;
        this.tripSeatInventoryRepository = tripSeatInventoryRepository;
    }

    @Transactional(readOnly = true)
    public List<TripResponse> list(UUID operatorId, LocalDate serviceDate, TripStatus status) {
        operatorAuthorizationService.requireMember(operatorId, RoleCode.OPERATOR_ADMIN, RoleCode.OPERATOR_STAFF);
        return findTrips(operatorId, serviceDate, status).stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public TripResponse get(UUID operatorId, UUID tripId) {
        operatorAuthorizationService.requireMember(operatorId, RoleCode.OPERATOR_ADMIN, RoleCode.OPERATOR_STAFF);
        Trip trip = tripRepository.findByIdAndOperator_Id(tripId, operatorId)
                .orElseThrow(OperatorAuthorizationService::hiddenNotFound);
        return toResponse(trip);
    }

    private List<Trip> findTrips(UUID operatorId, LocalDate serviceDate, TripStatus status) {
        if (serviceDate != null && status != null) {
            return tripRepository.findByOperator_IdAndServiceDateAndStatusOrderByScheduledDepartureAtAsc(
                    operatorId, serviceDate, status);
        }
        if (serviceDate != null) {
            return tripRepository.findByOperator_IdAndServiceDateOrderByScheduledDepartureAtAsc(operatorId, serviceDate);
        }
        if (status != null) {
            return tripRepository.findByOperator_IdAndStatusOrderByScheduledDepartureAtAsc(operatorId, status);
        }
        return tripRepository.findByOperator_IdOrderByScheduledDepartureAtAsc(operatorId);
    }

    private TripResponse toResponse(Trip trip) {
        List<TripStop> stops = tripStopRepository.findByTripIdOrderBySequenceNumberAsc(trip.getId());
        List<UUID> stopIds = stops.stream().map(TripStop::getId).toList();
        Map<UUID, List<TripPoint>> pointsByStop = new LinkedHashMap<>();
        for (UUID stopId : stopIds) {
            pointsByStop.put(stopId, new ArrayList<>());
        }
        if (!stopIds.isEmpty()) {
            for (TripPoint point : tripPointRepository.findByTripStop_IdInOrderByNameAsc(stopIds)) {
                pointsByStop.get(point.getTripStop().getId()).add(point);
            }
        }
        List<TripStopResponse> stopResponses = stops.stream()
                .map(stop -> TripStopResponse.from(stop, pointsByStop.getOrDefault(stop.getId(), List.of())))
                .toList();
        List<TripSeatInventoryResponse> inventoryResponses = tripSeatInventoryRepository
                .findByTrip_IdOrderByDeckNumberAscRowNumberAscColumnNumberAsc(trip.getId())
                .stream()
                .map(TripSeatInventoryResponse::from)
                .toList();
        return TripResponse.from(trip, stopResponses, inventoryResponses);
    }
}
