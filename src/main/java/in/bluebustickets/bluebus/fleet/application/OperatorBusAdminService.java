package in.bluebustickets.bluebus.fleet.application;

import java.util.List;
import java.util.UUID;

import in.bluebustickets.bluebus.fleet.api.admin.dto.BusResponse;
import in.bluebustickets.bluebus.fleet.domain.Bus;
import in.bluebustickets.bluebus.fleet.domain.BusType;
import in.bluebustickets.bluebus.fleet.domain.SeatLayout;
import in.bluebustickets.bluebus.fleet.domain.SeatLayoutStatus;
import in.bluebustickets.bluebus.fleet.repository.BusRepository;
import in.bluebustickets.bluebus.fleet.repository.BusTypeRepository;
import in.bluebustickets.bluebus.fleet.repository.SeatLayoutRepository;
import in.bluebustickets.bluebus.foundation.api.error.ApplicationConflictException;
import in.bluebustickets.bluebus.foundation.api.error.ApplicationForbiddenException;
import in.bluebustickets.bluebus.foundation.api.error.ResourceNotFoundException;
import in.bluebustickets.bluebus.identity.domain.RoleCode;
import in.bluebustickets.bluebus.operator.application.OperatorAccess;
import in.bluebustickets.bluebus.operator.application.OperatorAuthorizationService;
import in.bluebustickets.bluebus.operator.domain.Operator;
import in.bluebustickets.bluebus.operator.domain.OperatorStatus;
import in.bluebustickets.bluebus.operator.domain.OperatorUser;
import in.bluebustickets.bluebus.operator.domain.OperatorUserStatus;
import in.bluebustickets.bluebus.operator.repository.OperatorRepository;
import in.bluebustickets.bluebus.operator.repository.OperatorUserRepository;
import in.bluebustickets.bluebus.scheduling.repository.TripRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Operator-scoped bus administration. Existing-bus mutations lock the bus row
 * ({@code FOR UPDATE}) and revalidate ACTIVE {@code OPERATOR_ADMIN} membership so concurrent
 * demotion cannot complete a privileged write on a stale authorization snapshot.
 */
@Service
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class OperatorBusAdminService {

    private static final String REGISTRATION_CONFLICT = "Bus registration number already exists.";
    private static final String LAYOUT_TRIP_CONFLICT =
            "Seat layout cannot be changed while trips exist for this bus.";
    private static final String LAYOUT_NOT_PUBLISHED =
            "Seat layout must be PUBLISHED before it can be assigned to a bus.";
    private static final String BUS_TYPE_INACTIVE = "Bus type must be active.";

    @PersistenceContext
    private EntityManager entityManager;

    private final OperatorAuthorizationService operatorAuthorizationService;
    private final OperatorUserRepository operatorUserRepository;
    private final OperatorRepository operatorRepository;
    private final BusRepository busRepository;
    private final BusTypeRepository busTypeRepository;
    private final SeatLayoutRepository seatLayoutRepository;
    private final TripRepository tripRepository;

    /**
     * Optional test barrier after early authorize and before the bus row lock. Production null.
     */
    private volatile Runnable afterAuthorizeBeforeLockForTests;

    /**
     * Optional test barrier after create validation and before persist. Production null.
     */
    private volatile Runnable afterValidationBeforeSaveForTests;

    public OperatorBusAdminService(
            OperatorAuthorizationService operatorAuthorizationService,
            OperatorUserRepository operatorUserRepository,
            OperatorRepository operatorRepository,
            BusRepository busRepository,
            BusTypeRepository busTypeRepository,
            SeatLayoutRepository seatLayoutRepository,
            TripRepository tripRepository) {
        this.operatorAuthorizationService = operatorAuthorizationService;
        this.operatorUserRepository = operatorUserRepository;
        this.operatorRepository = operatorRepository;
        this.busRepository = busRepository;
        this.busTypeRepository = busTypeRepository;
        this.seatLayoutRepository = seatLayoutRepository;
        this.tripRepository = tripRepository;
    }

    @Transactional
    public BusResponse create(
            UUID operatorId,
            UUID busTypeId,
            UUID seatLayoutId,
            String registrationNumber,
            String displayName) {
        operatorAuthorizationService.requireMember(operatorId, RoleCode.OPERATOR_ADMIN);

        Operator operator = operatorRepository.findById(operatorId)
                .orElseThrow(OperatorAuthorizationService::hiddenNotFound);
        BusType busType = requireActiveBusType(busTypeId);
        SeatLayout seatLayout = requirePublishedOperatorLayout(operatorId, seatLayoutId);
        String normalizedRegistration = requireText(registrationNumber, "Bus registration number is required");

        if (busRepository.existsByRegistrationNumberIgnoreCase(normalizedRegistration)) {
            throw new ApplicationConflictException(REGISTRATION_CONFLICT);
        }

        Runnable afterValidation = afterValidationBeforeSaveForTests;
        if (afterValidation != null) {
            afterValidation.run();
        }

        try {
            Bus bus = new Bus(operator, busType, seatLayout, normalizedRegistration);
            bus.updateDisplayName(blankToNull(displayName));
            return BusResponse.from(busRepository.saveAndFlush(bus));
        } catch (DataIntegrityViolationException exception) {
            if (isRegistrationUniquenessViolation(exception)) {
                throw new ApplicationConflictException(REGISTRATION_CONFLICT);
            }
            throw exception;
        }
    }

    @Transactional
    public BusResponse update(
            UUID operatorId,
            UUID busId,
            String displayName,
            boolean displayNamePresent,
            UUID busTypeId,
            boolean busTypeIdPresent,
            UUID seatLayoutId,
            boolean seatLayoutIdPresent) {
        if (!displayNamePresent && !busTypeIdPresent && !seatLayoutIdPresent) {
            throw new IllegalArgumentException(
                    "At least one of displayName, busTypeId, or seatLayoutId is required.");
        }

        Bus bus = lockOwnedBusForAdminMutation(operatorId, busId);

        if (displayNamePresent) {
            bus.updateDisplayName(blankToNull(displayName));
        }
        if (busTypeIdPresent) {
            if (busTypeId == null) {
                throw new IllegalArgumentException("busTypeId is required when provided.");
            }
            bus.assignBusType(requireActiveBusType(busTypeId));
        }
        if (seatLayoutIdPresent) {
            if (seatLayoutId == null) {
                throw new IllegalArgumentException("seatLayoutId is required when provided.");
            }
            if (tripRepository.existsByBus_Id(busId)) {
                throw new ApplicationConflictException(LAYOUT_TRIP_CONFLICT);
            }
            bus.assignSeatLayout(requirePublishedOperatorLayout(operatorId, seatLayoutId));
        }

        entityManager.flush();
        return BusResponse.from(bus);
    }

    @Transactional
    public BusResponse activate(UUID operatorId, UUID busId) {
        Bus bus = lockOwnedBusForAdminMutation(operatorId, busId);
        bus.activate();
        entityManager.flush();
        return BusResponse.from(bus);
    }

    @Transactional
    public BusResponse deactivate(UUID operatorId, UUID busId) {
        Bus bus = lockOwnedBusForAdminMutation(operatorId, busId);
        bus.deactivate();
        entityManager.flush();
        return BusResponse.from(bus);
    }

    @Transactional
    public BusResponse markMaintenance(UUID operatorId, UUID busId) {
        Bus bus = lockOwnedBusForAdminMutation(operatorId, busId);
        bus.markMaintenance();
        entityManager.flush();
        return BusResponse.from(bus);
    }

    private Bus lockOwnedBusForAdminMutation(UUID operatorId, UUID busId) {
        OperatorAccess earlyAccess = operatorAuthorizationService.requireMember(
                operatorId, RoleCode.OPERATOR_ADMIN);
        Runnable barrier = afterAuthorizeBeforeLockForTests;
        if (barrier != null) {
            barrier.run();
        }
        Bus bus = lockBusForUpdate(operatorId, busId);
        revalidateCallerAdminAfterBusLock(operatorId, earlyAccess.userId());
        return bus;
    }

    @SuppressWarnings("unchecked")
    private Bus lockBusForUpdate(UUID operatorId, UUID busId) {
        List<Bus> rows = entityManager.createNativeQuery("""
                SELECT *
                FROM buses
                WHERE id = :id
                  AND operator_id = :operatorId
                FOR UPDATE
                """, Bus.class)
                .setParameter("id", busId)
                .setParameter("operatorId", operatorId)
                .getResultList();
        if (rows.isEmpty()) {
            throw OperatorAuthorizationService.hiddenNotFound();
        }
        return rows.get(0);
    }

    private void revalidateCallerAdminAfterBusLock(UUID operatorId, UUID callerUserId) {
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

    private BusType requireActiveBusType(UUID busTypeId) {
        BusType busType = busTypeRepository.findById(busTypeId)
                .orElseThrow(() -> new ResourceNotFoundException("Bus type was not found."));
        if (!busType.isActive()) {
            throw new ApplicationConflictException(BUS_TYPE_INACTIVE);
        }
        return busType;
    }

    private SeatLayout requirePublishedOperatorLayout(UUID operatorId, UUID seatLayoutId) {
        SeatLayout seatLayout = seatLayoutRepository.findByIdAndOperator_Id(seatLayoutId, operatorId)
                .orElseThrow(OperatorAuthorizationService::hiddenNotFound);
        if (seatLayout.getStatus() != SeatLayoutStatus.PUBLISHED) {
            throw new ApplicationConflictException(LAYOUT_NOT_PUBLISHED);
        }
        return seatLayout;
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    static boolean isRegistrationUniquenessViolation(DataIntegrityViolationException exception) {
        Throwable cursor = exception;
        while (cursor != null) {
            String message = cursor.getMessage();
            if (message != null && message.contains("ux_buses_registration_number_lower")) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return false;
    }
}
