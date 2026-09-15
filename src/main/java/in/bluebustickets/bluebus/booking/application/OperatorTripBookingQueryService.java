package in.bluebustickets.bluebus.booking.application;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import in.bluebustickets.bluebus.booking.api.operator.dto.OperatorBookingResponse;
import in.bluebustickets.bluebus.booking.domain.Booking;
import in.bluebustickets.bluebus.booking.repository.BookingRepository;
import in.bluebustickets.bluebus.identity.domain.RoleCode;
import in.bluebustickets.bluebus.operator.application.OperatorAuthorizationService;
import in.bluebustickets.bluebus.scheduling.domain.Trip;
import in.bluebustickets.bluebus.scheduling.repository.TripRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class OperatorTripBookingQueryService {

    private final OperatorAuthorizationService operatorAuthorizationService;
    private final TripRepository tripRepository;
    private final BookingRepository bookingRepository;
    private final BookingViewMapper bookingViewMapper;

    public OperatorTripBookingQueryService(
            OperatorAuthorizationService operatorAuthorizationService,
            TripRepository tripRepository,
            BookingRepository bookingRepository,
            BookingViewMapper bookingViewMapper) {
        this.operatorAuthorizationService = operatorAuthorizationService;
        this.tripRepository = tripRepository;
        this.bookingRepository = bookingRepository;
        this.bookingViewMapper = bookingViewMapper;
    }

    @Transactional(readOnly = true)
    public List<OperatorBookingResponse> list(UUID operatorId, UUID tripId) {
        Trip trip = requireOwnedTrip(operatorId, tripId);
        List<Booking> bookings = bookingRepository.findDetailedByTripIdAndOperatorId(trip.getId(), operatorId)
                .stream()
                .filter(booking -> matchesTripAndOperator(booking, trip, operatorId))
                .toList();
        return bookingViewMapper.toOperatorResponses(bookings);
    }

    @Transactional(readOnly = true)
    public OperatorBookingResponse get(UUID operatorId, UUID tripId, UUID bookingId) {
        Trip trip = requireOwnedTrip(operatorId, tripId);
        Booking booking = bookingRepository
                .findDetailedByIdAndTripIdAndOperatorId(bookingId, trip.getId(), operatorId)
                .orElseThrow(OperatorAuthorizationService::hiddenNotFound);
        if (!matchesTripAndOperator(booking, trip, operatorId)) {
            throw OperatorAuthorizationService.hiddenNotFound();
        }
        return bookingViewMapper.toOperatorResponse(booking);
    }

    private Trip requireOwnedTrip(UUID operatorId, UUID tripId) {
        operatorAuthorizationService.requireMember(operatorId, RoleCode.OPERATOR_ADMIN, RoleCode.OPERATOR_STAFF);
        return tripRepository.findByIdAndOperator_Id(tripId, operatorId)
                .orElseThrow(OperatorAuthorizationService::hiddenNotFound);
    }

    private static boolean matchesTripAndOperator(Booking booking, Trip trip, UUID operatorId) {
        return Objects.equals(booking.getOperatorId(), operatorId)
                && Objects.equals(trip.getOperator().getId(), operatorId)
                && Objects.equals(booking.getTripId(), trip.getId());
    }
}
