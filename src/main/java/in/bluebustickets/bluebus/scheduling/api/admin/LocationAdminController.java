package in.bluebustickets.bluebus.scheduling.api.admin;

import java.util.List;
import java.util.UUID;

import in.bluebustickets.bluebus.scheduling.api.admin.dto.CreateLocationRequest;
import in.bluebustickets.bluebus.scheduling.api.admin.dto.LocationResponse;
import in.bluebustickets.bluebus.scheduling.api.admin.dto.UpdateLocationRequest;
import in.bluebustickets.bluebus.scheduling.application.LocationAdminService;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/locations")
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class LocationAdminController {

    private final LocationAdminService locationAdminService;

    public LocationAdminController(LocationAdminService locationAdminService) {
        this.locationAdminService = locationAdminService;
    }

    @PostMapping
    public ResponseEntity<LocationResponse> create(@Valid @RequestBody CreateLocationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(LocationResponse.from(locationAdminService.create(
                request.countryCode(),
                request.state(),
                request.district(),
                request.city(),
                request.locality(),
                request.latitude(),
                request.longitude(),
                request.timeZone())));
    }

    @GetMapping("/{id}")
    public LocationResponse get(@PathVariable UUID id) {
        return LocationResponse.from(locationAdminService.get(id));
    }

    @GetMapping
    public List<LocationResponse> search(
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String city) {
        return locationAdminService.search(active, state, city).stream()
                .map(LocationResponse::from)
                .toList();
    }

    @PutMapping("/{id}")
    public LocationResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateLocationRequest request) {
        return LocationResponse.from(locationAdminService.update(
                id,
                request.countryCode(),
                request.state(),
                request.district(),
                request.city(),
                request.locality(),
                request.latitude(),
                request.longitude(),
                request.timeZone()));
    }

    @PostMapping("/{id}/activate")
    public LocationResponse activate(@PathVariable UUID id) {
        return LocationResponse.from(locationAdminService.activate(id));
    }

    @PostMapping("/{id}/deactivate")
    public LocationResponse deactivate(@PathVariable UUID id) {
        return LocationResponse.from(locationAdminService.deactivate(id));
    }
}
