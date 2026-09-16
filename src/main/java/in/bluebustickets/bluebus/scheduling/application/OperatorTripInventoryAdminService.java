package in.bluebustickets.bluebus.scheduling.application;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import in.bluebustickets.bluebus.foundation.api.error.ApplicationForbiddenException;
import in.bluebustickets.bluebus.identity.domain.RoleCode;
import in.bluebustickets.bluebus.operator.application.OperatorAccess;
import in.bluebustickets.bluebus.operator.application.OperatorAuthorizationService;
import in.bluebustickets.bluebus.operator.domain.Operator;
import in.bluebustickets.bluebus.operator.domain.OperatorStatus;
import in.bluebustickets.bluebus.operator.domain.OperatorUser;
import in.bluebustickets.bluebus.operator.domain.OperatorUserStatus;
import in.bluebustickets.bluebus.operator.repository.OperatorRepository;
import in.bluebustickets.bluebus.operator.repository.OperatorUserRepository;
import in.bluebustickets.bluebus.scheduling.api.admin.dto.TripSeatInventoryResponse;
import in.bluebustickets.bluebus.scheduling.domain.Trip;
import in.bluebustickets.bluebus.scheduling.domain.TripSeatInventory;
import in.bluebustickets.bluebus.scheduling.domain.TripStatus;
import in.bluebustickets.bluebus.scheduling.repository.TripRepository;
import in.bluebustickets.bluebus.scheduling.repository.TripSeatInventoryRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Operator-scoped physical trip seat inventory administration.
 * <p>
 * Block/unblock mutate {@code trip_seat_inventory.physical_status} only. They never create,
 * cancel, release, or expire segment allocations, holds, bookings, tickets, or payments.
 * Lock order: trip {@code FOR UPDATE} by {@code (id, operatorId)}, then inventory
 * {@code FOR UPDATE} by {@code (id, trip_id)}. Allocations are not locked.
 */
@Service
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class OperatorTripInventoryAdminService {

    private static final Set<TripStatus> MUTABLE_TRIP_STATUSES = EnumSet.of(
            TripStatus.DRAFT,
            TripStatus.SCHEDULED,
            TripStatus.ON_SALE);

    private static final String TRIP_STATUS_REFUSED =
            "Trip seat inventory cannot be changed while the trip is %s.";

    @PersistenceContext
    private EntityManager entityManager;

    private final OperatorAuthorizationService operatorAuthorizationService;
    private final OperatorUserRepository operatorUserRepository;
    private final OperatorRepository operatorRepository;
    private final TripRepository tripRepository;
    private final TripSeatInventoryRepository tripSeatInventoryRepository;

    /**
     * Optional test barrier after early authorize and before the trip row lock. Production null.
     */
    private volatile Runnable afterAuthorizeBeforeLockForTests;

    public OperatorTripInventoryAdminService(
            OperatorAuthorizationService operatorAuthorizationService,
            OperatorUserRepository operatorUserRepository,
            OperatorRepository operatorRepository,
            TripRepository tripRepository,
            TripSeatInventoryRepository tripSeatInventoryRepository) {
        this.operatorAuthorizationService = operatorAuthorizationService;
        this.operatorUserRepository = operatorUserRepository;
        this.operatorRepository = operatorRepository;
        this.tripRepository = tripRepository;
        this.tripSeatInventoryRepository = tripSeatInventoryRepository;
    }

    @Transactional(readOnly = true)
    public List<TripSeatInventoryResponse> list(UUID operatorId, UUID tripId) {
        requireOwnedTripForRead(operatorId, tripId);
        return tripSeatInventoryRepository
                .findByTrip_IdOrderByDeckNumberAscRowNumberAscColumnNumberAsc(tripId)
                .stream()
                .map(TripSeatInventoryResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public TripSeatInventoryResponse get(UUID operatorId, UUID tripId, UUID inventoryId) {
        requireOwnedTripForRead(operatorId, tripId);
        TripSeatInventory inventory = tripSeatInventoryRepository.findByIdAndTrip_Id(inventoryId, tripId)
                .orElseThrow(OperatorAuthorizationService::hiddenNotFound);
        return TripSeatInventoryResponse.from(inventory);
    }

    @Transactional
    public TripSeatInventoryResponse block(UUID operatorId, UUID tripId, UUID inventoryId, String reason) {
        TripSeatInventory inventory = lockOwnedInventoryForMutation(operatorId, tripId, inventoryId);
        inventory.block(reason);
        entityManager.flush();
        return TripSeatInventoryResponse.from(inventory);
    }

    @Transactional
    public TripSeatInventoryResponse unblock(UUID operatorId, UUID tripId, UUID inventoryId) {
        TripSeatInventory inventory = lockOwnedInventoryForMutation(operatorId, tripId, inventoryId);
        inventory.unblock();
        entityManager.flush();
        return TripSeatInventoryResponse.from(inventory);
    }

    private Trip requireOwnedTripForRead(UUID operatorId, UUID tripId) {
        operatorAuthorizationService.requireMember(
                operatorId, RoleCode.OPERATOR_ADMIN, RoleCode.OPERATOR_STAFF);
        return tripRepository.findByIdAndOperator_Id(tripId, operatorId)
                .orElseThrow(OperatorAuthorizationService::hiddenNotFound);
    }

    private TripSeatInventory lockOwnedInventoryForMutation(
            UUID operatorId, UUID tripId, UUID inventoryId) {
        OperatorAccess earlyAccess = operatorAuthorizationService.requireMember(
                operatorId, RoleCode.OPERATOR_ADMIN);
        Runnable barrier = afterAuthorizeBeforeLockForTests;
        if (barrier != null) {
            barrier.run();
        }

        Trip trip = lockTripForUpdate(operatorId, tripId);
        revalidateCallerAdminAfterLock(operatorId, earlyAccess.userId());
        requireMutableTripStatus(trip);

        return lockInventoryForUpdate(trip.getId(), inventoryId);
    }

    @SuppressWarnings("unchecked")
    private Trip lockTripForUpdate(UUID operatorId, UUID tripId) {
        List<Trip> rows = entityManager.createNativeQuery("""
                SELECT *
                FROM trips
                WHERE id = :id
                  AND operator_id = :operatorId
                FOR UPDATE
                """, Trip.class)
                .setParameter("id", tripId)
                .setParameter("operatorId", operatorId)
                .getResultList();
        if (rows.isEmpty()) {
            throw OperatorAuthorizationService.hiddenNotFound();
        }
        return rows.get(0);
    }

    @SuppressWarnings("unchecked")
    private TripSeatInventory lockInventoryForUpdate(UUID tripId, UUID inventoryId) {
        List<TripSeatInventory> rows = entityManager.createNativeQuery("""
                SELECT *
                FROM trip_seat_inventory
                WHERE id = :id
                  AND trip_id = :tripId
                FOR UPDATE
                """, TripSeatInventory.class)
                .setParameter("id", inventoryId)
                .setParameter("tripId", tripId)
                .getResultList();
        if (rows.isEmpty()) {
            throw OperatorAuthorizationService.hiddenNotFound();
        }
        return rows.get(0);
    }

    private void revalidateCallerAdminAfterLock(UUID operatorId, UUID callerUserId) {
        Operator operator = operatorRepository.findById(operatorId)
                .orElseThrow(OperatorAuthorizationService::hiddenNotFound);
        entityManager.refresh(operator);
        if (operator.getStatus() != OperatorStatus.ACTIVE) {
            throw new ApplicationForbiddenException();
        }

        OperatorUser callerMembership = operatorUserRepository
                .findByOperatorIdAndUserId(operatorId, callerUserId)
                .orElseThrow(OperatorAuthorizationService::hiddenNotFound);
        entityManager.refresh(callerMembership);
        if (callerMembership.getStatus() != OperatorUserStatus.ACTIVE) {
            throw OperatorAuthorizationService.hiddenNotFound();
        }
        if (callerMembership.getRole().getCode() != RoleCode.OPERATOR_ADMIN) {
            throw new ApplicationForbiddenException();
        }
    }

    private static void requireMutableTripStatus(Trip trip) {
        if (!MUTABLE_TRIP_STATUSES.contains(trip.getStatus())) {
            throw new IllegalArgumentException(TRIP_STATUS_REFUSED.formatted(trip.getStatus()));
        }
    }
}
