package in.bluebustickets.bluebus.scheduling.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import in.bluebustickets.bluebus.foundation.api.error.ResourceNotFoundException;
import in.bluebustickets.bluebus.identity.application.AuthorizationService;
import in.bluebustickets.bluebus.scheduling.domain.Location;
import in.bluebustickets.bluebus.scheduling.repository.LocationRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class LocationAdminService {

    private final LocationRepository locationRepository;
    private final AuthorizationService authorizationService;

    public LocationAdminService(
            LocationRepository locationRepository,
            AuthorizationService authorizationService) {
        this.locationRepository = locationRepository;
        this.authorizationService = authorizationService;
    }

    @Transactional
    public Location create(
            String countryCode,
            String state,
            String district,
            String city,
            String locality,
            BigDecimal latitude,
            BigDecimal longitude,
            String timeZone) {
        authorizationService.requirePlatformAdmin();
        Location location = new Location(
                requireText(state, "Location state is required"),
                requireText(city, "Location city is required"));
        location.updateDetails(
                normalizeCountryCode(countryCode),
                state.trim(),
                blankToNull(district),
                city.trim(),
                blankToNull(locality),
                latitude,
                longitude,
                blankToNull(timeZone));
        return locationRepository.save(location);
    }

    @Transactional(readOnly = true)
    public Location get(UUID id) {
        authorizationService.requirePlatformAdmin();
        return locationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Location was not found."));
    }

    @Transactional(readOnly = true)
    public List<Location> search(Boolean active, String state, String city) {
        authorizationService.requirePlatformAdmin();
        boolean hasFilters = active != null
                || (state != null && !state.isBlank())
                || (city != null && !city.isBlank());
        if (!hasFilters) {
            return locationRepository.findAllByOrderByStateAscCityAsc();
        }
        return locationRepository.search(
                active,
                blankToNull(state),
                blankToNull(city));
    }

    @Transactional
    public Location update(
            UUID id,
            String countryCode,
            String state,
            String district,
            String city,
            String locality,
            BigDecimal latitude,
            BigDecimal longitude,
            String timeZone) {
        authorizationService.requirePlatformAdmin();
        Location location = get(id);
        location.updateDetails(
                normalizeCountryCode(countryCode),
                requireText(state, "Location state is required"),
                blankToNull(district),
                requireText(city, "Location city is required"),
                blankToNull(locality),
                latitude,
                longitude,
                blankToNull(timeZone));
        return location;
    }

    @Transactional
    public Location activate(UUID id) {
        authorizationService.requirePlatformAdmin();
        Location location = get(id);
        location.activate();
        return location;
    }

    @Transactional
    public Location deactivate(UUID id) {
        authorizationService.requirePlatformAdmin();
        Location location = get(id);
        location.deactivate();
        return location;
    }

    private static String normalizeCountryCode(String countryCode) {
        if (countryCode == null || countryCode.isBlank()) {
            return "IN";
        }
        String normalized = countryCode.trim().toUpperCase();
        if (!normalized.matches("^[A-Z]{2}$")) {
            throw new IllegalArgumentException("Country code must be a 2-letter ISO value");
        }
        return normalized;
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
