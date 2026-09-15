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
import in.bluebustickets.bluebus.foundation.api.error.ResourceNotFoundException;
import in.bluebustickets.bluebus.identity.application.AuthorizationService;
import in.bluebustickets.bluebus.operator.domain.Operator;
import in.bluebustickets.bluebus.operator.repository.OperatorRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class SeatLayoutAdminService {

    private final SeatLayoutRepository seatLayoutRepository;
    private final SeatRepository seatRepository;
    private final OperatorRepository operatorRepository;
    private final AuthorizationService authorizationService;

    public SeatLayoutAdminService(
            SeatLayoutRepository seatLayoutRepository,
            SeatRepository seatRepository,
            OperatorRepository operatorRepository,
            AuthorizationService authorizationService) {
        this.seatLayoutRepository = seatLayoutRepository;
        this.seatRepository = seatRepository;
        this.operatorRepository = operatorRepository;
        this.authorizationService = authorizationService;
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
        authorizationService.requirePlatformAdmin();
        Operator operator = operatorRepository.findById(operatorId)
                .orElseThrow(() -> new ResourceNotFoundException("Operator was not found."));
        String normalizedName = requireText(name, "Seat layout name is required");
        requirePositive(version, "Seat layout version must be positive");
        requirePositive(deckCount, "Seat layout deck count must be positive");
        requirePositive(rowCount, "Seat layout row count must be positive");
        requirePositive(columnCount, "Seat layout column count must be positive");

        if (seatLayoutRepository.existsByOperator_IdAndNameIgnoreCaseAndVersion(operatorId, normalizedName, version)) {
            throw new ApplicationConflictException("Seat layout name and version already exist for this operator.");
        }

        SeatLayout layout = seatLayoutRepository.save(new SeatLayout(
                operator, normalizedName, version, deckCount, rowCount, columnCount));
        List<Seat> persistedSeats = persistSeats(layout, seats);
        return SeatLayoutResponse.from(layout, persistedSeats);
    }

    @Transactional(readOnly = true)
    public SeatLayoutResponse get(UUID id) {
        authorizationService.requirePlatformAdmin();
        SeatLayout layout = requireLayout(id);
        return SeatLayoutResponse.from(layout, seatsFor(layout.getId()));
    }

    @Transactional(readOnly = true)
    public List<SeatLayoutResponse> list(UUID operatorId, SeatLayoutStatus status) {
        authorizationService.requirePlatformAdmin();
        List<SeatLayout> layouts;
        if (operatorId != null && status != null) {
            layouts = seatLayoutRepository.findByOperator_IdAndStatusOrderByNameAscVersionAsc(operatorId, status);
        } else if (operatorId != null) {
            layouts = seatLayoutRepository.findByOperator_IdOrderByNameAscVersionAsc(operatorId);
        } else if (status != null) {
            layouts = seatLayoutRepository.findByStatusOrderByNameAscVersionAsc(status);
        } else {
            layouts = seatLayoutRepository.findAllByOrderByNameAscVersionAsc();
        }
        return toResponses(layouts);
    }

    @Transactional
    public SeatLayoutResponse update(UUID id, String name, int deckCount, int rowCount, int columnCount) {
        authorizationService.requirePlatformAdmin();
        SeatLayout layout = requireLayout(id);
        layout.updateMetadata(
                requireText(name, "Seat layout name is required"),
                deckCount,
                rowCount,
                columnCount);
        return SeatLayoutResponse.from(layout, seatsFor(layout.getId()));
    }

    /**
     * Maps to {@link SeatLayout#publish()} — DRAFT/PUBLISHED → PUBLISHED.
     * Archived layouts cannot be activated.
     */
    @Transactional
    public SeatLayoutResponse activate(UUID id) {
        authorizationService.requirePlatformAdmin();
        SeatLayout layout = requireLayout(id);
        layout.publish();
        return SeatLayoutResponse.from(layout, seatsFor(layout.getId()));
    }

    /**
     * Maps to {@link SeatLayout#archive()} — any status → ARCHIVED.
     */
    @Transactional
    public SeatLayoutResponse deactivate(UUID id) {
        authorizationService.requirePlatformAdmin();
        SeatLayout layout = requireLayout(id);
        layout.archive();
        return SeatLayoutResponse.from(layout, seatsFor(layout.getId()));
    }

    private List<Seat> persistSeats(SeatLayout layout, List<SeatDefinitionRequest> seats) {
        if (seats == null || seats.isEmpty()) {
            return List.of();
        }
        validateSeatDefinitions(layout, seats);
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
                throw new ApplicationConflictException("Duplicate seat number within layout: " + number);
            }
            String positionKey = seat.deckNumber() + ":" + seat.rowNumber() + ":" + seat.columnNumber();
            if (!positions.add(positionKey)) {
                throw new ApplicationConflictException(
                        "Duplicate seat position within layout: " + positionKey);
            }
        }
    }

    private SeatLayout requireLayout(UUID id) {
        return seatLayoutRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Seat layout was not found."));
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
                .map(layout -> SeatLayoutResponse.from(layout, seatsByLayout.getOrDefault(layout.getId(), List.of())))
                .toList();
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
}
