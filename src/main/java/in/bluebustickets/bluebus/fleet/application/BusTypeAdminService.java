package in.bluebustickets.bluebus.fleet.application;

import java.util.List;
import java.util.UUID;

import in.bluebustickets.bluebus.fleet.domain.BusType;
import in.bluebustickets.bluebus.fleet.repository.BusTypeRepository;
import in.bluebustickets.bluebus.foundation.api.error.ApplicationConflictException;
import in.bluebustickets.bluebus.foundation.api.error.ResourceNotFoundException;
import in.bluebustickets.bluebus.identity.application.AuthorizationService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class BusTypeAdminService {

    private final BusTypeRepository busTypeRepository;
    private final AuthorizationService authorizationService;

    public BusTypeAdminService(
            BusTypeRepository busTypeRepository,
            AuthorizationService authorizationService) {
        this.busTypeRepository = busTypeRepository;
        this.authorizationService = authorizationService;
    }

    @Transactional
    public BusType create(String code, String displayName) {
        authorizationService.requirePlatformAdmin();
        String normalizedCode = requireText(code, "Bus type code is required");
        String normalizedName = requireText(displayName, "Bus type display name is required");
        if (busTypeRepository.existsByCodeIgnoreCase(normalizedCode)) {
            throw new ApplicationConflictException("Bus type code already exists.");
        }
        return busTypeRepository.save(new BusType(normalizedCode, normalizedName));
    }

    @Transactional(readOnly = true)
    public BusType get(UUID id) {
        authorizationService.requirePlatformAdmin();
        return busTypeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Bus type was not found."));
    }

    @Transactional(readOnly = true)
    public List<BusType> list(Boolean active) {
        authorizationService.requirePlatformAdmin();
        if (active == null) {
            return busTypeRepository.findAllByOrderByCodeAsc();
        }
        return busTypeRepository.findByActiveOrderByCodeAsc(active);
    }

    @Transactional
    public BusType update(UUID id, String displayName) {
        authorizationService.requirePlatformAdmin();
        BusType busType = get(id);
        busType.updateDisplayName(requireText(displayName, "Bus type display name is required"));
        return busType;
    }

    @Transactional
    public BusType activate(UUID id) {
        authorizationService.requirePlatformAdmin();
        BusType busType = get(id);
        busType.activate();
        return busType;
    }

    @Transactional
    public BusType deactivate(UUID id) {
        authorizationService.requirePlatformAdmin();
        BusType busType = get(id);
        busType.deactivate();
        return busType;
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
