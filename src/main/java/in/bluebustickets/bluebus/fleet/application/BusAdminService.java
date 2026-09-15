package in.bluebustickets.bluebus.fleet.application;

import java.util.List;
import java.util.UUID;

import in.bluebustickets.bluebus.fleet.api.admin.dto.BusResponse;
import in.bluebustickets.bluebus.fleet.domain.Bus;
import in.bluebustickets.bluebus.fleet.domain.BusStatus;
import in.bluebustickets.bluebus.fleet.domain.BusType;
import in.bluebustickets.bluebus.fleet.domain.SeatLayout;
import in.bluebustickets.bluebus.fleet.repository.BusRepository;
import in.bluebustickets.bluebus.fleet.repository.BusTypeRepository;
import in.bluebustickets.bluebus.fleet.repository.SeatLayoutRepository;
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
public class BusAdminService {

    private final BusRepository busRepository;
    private final OperatorRepository operatorRepository;
    private final BusTypeRepository busTypeRepository;
    private final SeatLayoutRepository seatLayoutRepository;
    private final AuthorizationService authorizationService;

    public BusAdminService(
            BusRepository busRepository,
            OperatorRepository operatorRepository,
            BusTypeRepository busTypeRepository,
            SeatLayoutRepository seatLayoutRepository,
            AuthorizationService authorizationService) {
        this.busRepository = busRepository;
        this.operatorRepository = operatorRepository;
        this.busTypeRepository = busTypeRepository;
        this.seatLayoutRepository = seatLayoutRepository;
        this.authorizationService = authorizationService;
    }

    @Transactional
    public BusResponse create(
            UUID operatorId,
            UUID busTypeId,
            UUID seatLayoutId,
            String registrationNumber,
            String displayName) {
        authorizationService.requirePlatformAdmin();
        Operator operator = requireOperator(operatorId);
        BusType busType = requireBusType(busTypeId);
        SeatLayout seatLayout = requireSeatLayout(seatLayoutId);
        String normalizedRegistration = requireText(registrationNumber, "Bus registration number is required");

        if (busRepository.existsByRegistrationNumberIgnoreCase(normalizedRegistration)) {
            throw new ApplicationConflictException("Bus registration number already exists.");
        }

        Bus bus = new Bus(operator, busType, seatLayout, normalizedRegistration);
        bus.updateDisplayName(blankToNull(displayName));
        return BusResponse.from(busRepository.save(bus));
    }

    @Transactional(readOnly = true)
    public BusResponse get(UUID id) {
        authorizationService.requirePlatformAdmin();
        return BusResponse.from(requireBus(id));
    }

    @Transactional(readOnly = true)
    public List<BusResponse> list(UUID operatorId, BusStatus status) {
        authorizationService.requirePlatformAdmin();
        List<Bus> buses;
        if (operatorId != null && status != null) {
            buses = busRepository.findByOperator_IdAndStatusOrderByRegistrationNumberAsc(operatorId, status);
        } else if (operatorId != null) {
            buses = busRepository.findByOperator_IdOrderByRegistrationNumberAsc(operatorId);
        } else if (status != null) {
            buses = busRepository.findByStatusOrderByRegistrationNumberAsc(status);
        } else {
            buses = busRepository.findAllByOrderByRegistrationNumberAsc();
        }
        return buses.stream().map(BusResponse::from).toList();
    }

    @Transactional
    public BusResponse update(UUID id, String displayName, UUID busTypeId, UUID seatLayoutId) {
        authorizationService.requirePlatformAdmin();
        Bus bus = requireBus(id);
        bus.updateDisplayName(blankToNull(displayName));
        bus.assignBusType(requireBusType(busTypeId));
        bus.assignSeatLayout(requireSeatLayout(seatLayoutId));
        return BusResponse.from(bus);
    }

    @Transactional
    public BusResponse activate(UUID id) {
        authorizationService.requirePlatformAdmin();
        Bus bus = requireBus(id);
        bus.activate();
        return BusResponse.from(bus);
    }

    @Transactional
    public BusResponse deactivate(UUID id) {
        authorizationService.requirePlatformAdmin();
        Bus bus = requireBus(id);
        bus.deactivate();
        return BusResponse.from(bus);
    }

    private Bus requireBus(UUID id) {
        return busRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Bus was not found."));
    }

    private Operator requireOperator(UUID id) {
        return operatorRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Operator was not found."));
    }

    private BusType requireBusType(UUID id) {
        return busTypeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Bus type was not found."));
    }

    private SeatLayout requireSeatLayout(UUID id) {
        return seatLayoutRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Seat layout was not found."));
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
}
