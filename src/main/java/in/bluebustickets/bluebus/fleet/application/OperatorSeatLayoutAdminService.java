package in.bluebustickets.bluebus.fleet.application;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import in.bluebustickets.bluebus.fleet.api.admin.dto.SeatDefinitionRequest;
import in.bluebustickets.bluebus.fleet.api.admin.dto.SeatLayoutResponse;
import in.bluebustickets.bluebus.fleet.domain.Seat;
import in.bluebustickets.bluebus.fleet.domain.SeatLayout;
import in.bluebustickets.bluebus.fleet.domain.SeatLayoutStatus;
import in.bluebustickets.bluebus.fleet.repository.SeatLayoutRepository;
import in.bluebustickets.bluebus.fleet.repository.SeatRepository;
import in.bluebustickets.bluebus.foundation.api.error.ApplicationConflictException;
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
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Operator-scoped seat layout administration. Creates serialize on the operator row;
 * existing-layout mutations lock the layout row and revalidate ACTIVE {@code OPERATOR_ADMIN}.
 * Does not alter trips, inventory, holds, or bookings.
 */
@Service
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class OperatorSeatLayoutAdminService {

    private static final String NAME_VERSION_CONFLICT =
            "Seat layout name and version already exist for this operator.";
    private static final String EMPTY_SEATS = "Seat layout requires at least one seat.";

    @PersistenceContext
    private EntityManager entityManager;

    private final OperatorAuthorizationService operatorAuthorizationService;
    private final OperatorUserRepository operatorUserRepository;
    private final OperatorRepository operatorRepository;
    private final SeatLayoutRepository seatLayoutRepository;
    private final SeatRepository seatRepository;

    /**
     * Optional test barrier after early authorize and before resource lock. Production null.
     */
    private volatile Runnable afterAuthorizeBeforeLockForTests;

    /**
     * Optional test barrier after create validation and before persist. Production null.
     */
    private volatile Runnable afterValidationBeforeSaveForTests;

    public OperatorSeatLayoutAdminService(
            OperatorAuthorizationService operatorAuthorizationService,
            OperatorUserRepository operatorUserRepository,
            OperatorRepository operatorRepository,
            SeatLayoutRepository seatLayoutRepository,
            SeatRepository seatRepository) {
        this.operatorAuthorizationService = operatorAuthorizationService;
        this.operatorUserRepository = operatorUserRepository;
        this.operatorRepository = operatorRepository;
        this.seatLayoutRepository = seatLayoutRepository;
        this.seatRepository = seatRepository;
    }

    @Transactional(readOnly = true)
    public List<SeatLayoutResponse> list(UUID operatorId, SeatLayoutStatus status) {
        operatorAuthorizationService.requireMember(
                operatorId, RoleCode.OPERATOR_ADMIN, RoleCode.OPERATOR_STAFF);
        List<SeatLayout> layouts = status == null
                ? seatLayoutRepository.findByOperator_IdOrderByNameAscVersionAsc(operatorId)
                : seatLayoutRepository.findByOperator_IdAndStatusOrderByNameAscVersionAsc(operatorId, status);
        return toResponses(layouts);
    }

    @Transactional(readOnly = true)
    public SeatLayoutResponse get(UUID operatorId, UUID layoutId) {
        operatorAuthorizationService.requireMember(
                operatorId, RoleCode.OPERATOR_ADMIN, RoleCode.OPERATOR_STAFF);
        SeatLayout layout = seatLayoutRepository.findByIdAndOperator_Id(layoutId, operatorId)
                .orElseThrow(OperatorAuthorizationService::hiddenNotFound);
        return SeatLayoutResponse.from(layout, seatsFor(layout.getId()));
    }

    @Transactional
    public SeatLayoutResponse create(
            UUID operatorId,
            String name,
            int version,
            int deckCount,
            int rowCount,
            int columnCount,
            List<SeatDefinitionRequest> seats) {
        authorizeAdminMutation(operatorId);

        String normalizedName = requireText(name, "Seat layout name is required");
        requirePositive(version, "Seat layout version must be positive");
        requirePositive(deckCount, "Seat layout deck count must be positive");
        requirePositive(rowCount, "Seat layout row count must be positive");
        requirePositive(columnCount, "Seat layout column count must be positive");
        if (seats == null || seats.isEmpty()) {
            throw new IllegalArgumentException(EMPTY_SEATS);
        }

        if (seatLayoutRepository.existsByOperator_IdAndNameIgnoreCaseAndVersion(
                operatorId, normalizedName, version)) {
            throw new ApplicationConflictException(NAME_VERSION_CONFLICT);
        }

        SeatLayout draftDimensions = new SeatLayout(
                operatorRepository.getReferenceById(operatorId),
                normalizedName,
                version,
                deckCount,
                rowCount,
                columnCount);
        validateSeatDefinitions(draftDimensions, seats);

        Runnable afterValidation = afterValidationBeforeSaveForTests;
        if (afterValidation != null) {
            afterValidation.run();
        }

        try {
            Operator operator = operatorRepository.getReferenceById(operatorId);
            SeatLayout layout = seatLayoutRepository.saveAndFlush(new SeatLayout(
                    operator, normalizedName, version, deckCount, rowCount, columnCount));
            List<Seat> persistedSeats = persistSeats(layout, seats);
            return SeatLayoutResponse.from(layout, persistedSeats);
        } catch (DataIntegrityViolationException exception) {
            if (isNameVersionUniquenessViolation(exception)) {
                throw new ApplicationConflictException(NAME_VERSION_CONFLICT);
            }
            throw exception;
        }
    }

    @Transactional
    public SeatLayoutResponse update(
            UUID operatorId,
            UUID layoutId,
            String name,
            boolean namePresent,
            Integer deckCount,
            boolean deckCountPresent,
            Integer rowCount,
            boolean rowCountPresent,
            Integer columnCount,
            boolean columnCountPresent) {
        if (!namePresent && !deckCountPresent && !rowCountPresent && !columnCountPresent) {
            throw new IllegalArgumentException(
                    "At least one of name, deckCount, rowCount, or columnCount is required.");
        }

        SeatLayout layout = lockOwnedLayoutForAdminMutation(operatorId, layoutId);

        String nextName = namePresent ? requireText(name, "Seat layout name is required") : layout.getName();
        int nextDeck = deckCountPresent ? requirePositive(deckCount, "Seat layout deck count must be positive")
                : layout.getDeckCount();
        int nextRow = rowCountPresent ? requirePositive(rowCount, "Seat layout row count must be positive")
                : layout.getRowCount();
        int nextColumn = columnCountPresent
                ? requirePositive(columnCount, "Seat layout column count must be positive")
                : layout.getColumnCount();

        List<Seat> existingSeats = seatsFor(layout.getId());
        validateExistingSeatsFitDimensions(existingSeats, nextDeck, nextRow, nextColumn);

        layout.updateDraftMetadata(nextName, nextDeck, nextRow, nextColumn);
        entityManager.flush();
        return SeatLayoutResponse.from(layout, existingSeats);
    }

    @Transactional
    public SeatLayoutResponse activate(UUID operatorId, UUID layoutId) {
        SeatLayout layout = lockOwnedLayoutForAdminMutation(operatorId, layoutId);
        if (layout.getStatus() == SeatLayoutStatus.PUBLISHED) {
            return SeatLayoutResponse.from(layout, seatsFor(layout.getId()));
        }
        requireAtLeastOneSeat(layout.getId());
        layout.publish();
        entityManager.flush();
        return SeatLayoutResponse.from(layout, seatsFor(layout.getId()));
    }

    @Transactional
    public SeatLayoutResponse deactivate(UUID operatorId, UUID layoutId) {
        SeatLayout layout = lockOwnedLayoutForAdminMutation(operatorId, layoutId);
        layout.archive();
        entityManager.flush();
        return SeatLayoutResponse.from(layout, seatsFor(layout.getId()));
    }

    private SeatLayout lockOwnedLayoutForAdminMutation(UUID operatorId, UUID layoutId) {
        OperatorAccess earlyAccess = operatorAuthorizationService.requireMember(
                operatorId, RoleCode.OPERATOR_ADMIN);
        Runnable barrier = afterAuthorizeBeforeLockForTests;
        if (barrier != null) {
            barrier.run();
        }
        SeatLayout layout = lockLayoutForUpdate(operatorId, layoutId);
        revalidateCallerAdminAfterLock(operatorId, earlyAccess.userId());
        return layout;
    }

    private OperatorAccess authorizeAdminMutation(UUID operatorId) {
        OperatorAccess earlyAccess = operatorAuthorizationService.requireMember(
                operatorId, RoleCode.OPERATOR_ADMIN);
        Runnable barrier = afterAuthorizeBeforeLockForTests;
        if (barrier != null) {
            barrier.run();
        }
        Operator operator = lockOperatorForUpdate(operatorId);
        return revalidateCallerAdminAfterOperatorLock(operator, earlyAccess.userId());
    }

    @SuppressWarnings("unchecked")
    private Operator lockOperatorForUpdate(UUID operatorId) {
        List<Operator> rows = entityManager.createNativeQuery("""
                SELECT *
                FROM operators
                WHERE id = :id
                FOR UPDATE
                """, Operator.class)
                .setParameter("id", operatorId)
                .getResultList();
        if (rows.isEmpty()) {
            throw OperatorAuthorizationService.hiddenNotFound();
        }
        return rows.get(0);
    }

    @SuppressWarnings("unchecked")
    private SeatLayout lockLayoutForUpdate(UUID operatorId, UUID layoutId) {
        List<SeatLayout> rows = entityManager.createNativeQuery("""
                SELECT *
                FROM seat_layouts
                WHERE id = :id
                  AND operator_id = :operatorId
                FOR UPDATE
                """, SeatLayout.class)
                .setParameter("id", layoutId)
                .setParameter("operatorId", operatorId)
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

    private OperatorAccess revalidateCallerAdminAfterOperatorLock(Operator operator, UUID callerUserId) {
        entityManager.refresh(operator);
        if (operator.getStatus() != OperatorStatus.ACTIVE) {
            throw new ApplicationForbiddenException();
        }
        OperatorUser callerMembership = operatorUserRepository
                .findByOperatorIdAndUserId(operator.getId(), callerUserId)
                .orElseThrow(OperatorAuthorizationService::hiddenNotFound);
        entityManager.refresh(callerMembership);
        if (callerMembership.getStatus() != OperatorUserStatus.ACTIVE) {
            throw OperatorAuthorizationService.hiddenNotFound();
        }
        if (callerMembership.getRole().getCode() != RoleCode.OPERATOR_ADMIN) {
            throw new ApplicationForbiddenException();
        }
        return new OperatorAccess(
                callerUserId,
                operator.getId(),
                RoleCode.OPERATOR_ADMIN,
                operator.getStatus());
    }

    private List<Seat> persistSeats(SeatLayout layout, List<SeatDefinitionRequest> seats) {
        List<Seat> persisted = new ArrayList<>(seats.size());
        for (SeatDefinitionRequest definition : seats) {
            Seat seat = new Seat(
                    layout,
                    requireText(definition.seatNumber(), "Seat number is required"),
                    definition.deckNumber(),
                    definition.rowNumber(),
                    definition.columnNumber(),
                    requireText(definition.seatType(), "Seat type is required"));
            if (definition.sellable() != null) {
                seat.markSellable(definition.sellable());
            }
            persisted.add(seatRepository.save(seat));
        }
        return persisted;
    }

    private void validateSeatDefinitions(SeatLayout layout, List<SeatDefinitionRequest> seats) {
        Set<String> numbers = new HashSet<>();
        Set<String> positions = new HashSet<>();
        for (SeatDefinitionRequest seat : seats) {
            String number = requireText(seat.seatNumber(), "Seat number is required");
            requireText(seat.seatType(), "Seat type is required");
            requirePositive(seat.deckNumber(), "Seat deck number must be positive");
            requirePositive(seat.rowNumber(), "Seat row number must be positive");
            requirePositive(seat.columnNumber(), "Seat column number must be positive");
            if (seat.deckNumber() > layout.getDeckCount()
                    || seat.rowNumber() > layout.getRowCount()
                    || seat.columnNumber() > layout.getColumnCount()) {
                throw new IllegalArgumentException(
                        "Seat position is outside the layout dimensions for seat " + number);
            }
            if (!numbers.add(number.toLowerCase())) {
                throw new IllegalArgumentException("Duplicate seat number within layout: " + number);
            }
            String positionKey = seat.deckNumber() + ":" + seat.rowNumber() + ":" + seat.columnNumber();
            if (!positions.add(positionKey)) {
                throw new IllegalArgumentException("Duplicate seat position within layout: " + positionKey);
            }
        }
    }

    private static void validateExistingSeatsFitDimensions(
            List<Seat> seats, int deckCount, int rowCount, int columnCount) {
        for (Seat seat : seats) {
            if (seat.getDeckNumber() > deckCount
                    || seat.getRowNumber() > rowCount
                    || seat.getColumnNumber() > columnCount) {
                throw new IllegalArgumentException(
                        "Layout dimensions cannot be reduced below existing seat "
                                + seat.getSeatNumber());
            }
        }
    }

    private void requireAtLeastOneSeat(UUID layoutId) {
        if (seatRepository.countBySeatLayoutId(layoutId) < 1) {
            throw new IllegalArgumentException(EMPTY_SEATS);
        }
    }

    private List<Seat> seatsFor(UUID layoutId) {
        return seatRepository.findBySeatLayoutIdOrderByDeckNumberAscRowNumberAscColumnNumberAsc(layoutId);
    }

    private List<SeatLayoutResponse> toResponses(List<SeatLayout> layouts) {
        if (layouts.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = layouts.stream().map(SeatLayout::getId).toList();
        Map<UUID, List<Seat>> seatsByLayout = new LinkedHashMap<>();
        for (UUID id : ids) {
            seatsByLayout.put(id, new ArrayList<>());
        }
        for (Seat seat : seatRepository.findBySeatLayoutIdInOrderByDeckNumberAscRowNumberAscColumnNumberAsc(ids)) {
            seatsByLayout.get(seat.getSeatLayout().getId()).add(seat);
        }
        return layouts.stream()
                .map(layout -> SeatLayoutResponse.from(
                        layout, seatsByLayout.getOrDefault(layout.getId(), List.of())))
                .toList();
    }

    static boolean isNameVersionUniquenessViolation(DataIntegrityViolationException exception) {
        Throwable cursor = exception;
        while (cursor != null) {
            String message = cursor.getMessage();
            if (message != null && message.contains("ux_seat_layouts_operator_name_version_lower")) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private static void requirePositive(int value, String message) {
        if (value < 1) {
            throw new IllegalArgumentException(message);
        }
    }

    private static int requirePositive(Integer value, String message) {
        if (value == null || value < 1) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }
}
