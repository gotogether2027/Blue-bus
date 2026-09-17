package in.bluebustickets.bluebus.scheduling.application;

import java.util.List;

import in.bluebustickets.bluebus.scheduling.domain.Location;
import in.bluebustickets.bluebus.scheduling.repository.LocationRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Public ACTIVE location discovery for customer origin/destination pickers.
 * Always bounded; never returns inactive rows.
 */
@Service
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class LocationCustomerService {

    public static final int MAX_RESULTS = 100;

    private final LocationRepository locationRepository;

    public LocationCustomerService(LocationRepository locationRepository) {
        this.locationRepository = locationRepository;
    }

    @Transactional(readOnly = true)
    public List<Location> searchActive(String state, String city) {
        return locationRepository.searchActive(
                blankToNull(state),
                blankToNull(city),
                PageRequest.of(0, MAX_RESULTS));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
