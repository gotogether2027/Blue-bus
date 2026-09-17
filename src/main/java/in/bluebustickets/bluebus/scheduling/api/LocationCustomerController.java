package in.bluebustickets.bluebus.scheduling.api;

import java.util.List;

import in.bluebustickets.bluebus.scheduling.api.dto.CustomerLocationResponse;
import in.bluebustickets.bluebus.scheduling.application.LocationCustomerService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public ACTIVE location catalog for customer search origin/destination pickers.
 */
@RestController
@RequestMapping("/api/v1/locations")
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class LocationCustomerController {

    private final LocationCustomerService locationCustomerService;

    public LocationCustomerController(LocationCustomerService locationCustomerService) {
        this.locationCustomerService = locationCustomerService;
    }

    @GetMapping
    public List<CustomerLocationResponse> search(
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String city) {
        return locationCustomerService.searchActive(state, city).stream()
                .map(CustomerLocationResponse::from)
                .toList();
    }
}
