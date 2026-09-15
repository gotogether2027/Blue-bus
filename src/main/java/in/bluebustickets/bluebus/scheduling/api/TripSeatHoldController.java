package in.bluebustickets.bluebus.scheduling.api;

import java.util.UUID;

import in.bluebustickets.bluebus.scheduling.api.dto.CreateSeatHoldRequest;
import in.bluebustickets.bluebus.scheduling.api.dto.SeatHoldResponse;
import in.bluebustickets.bluebus.scheduling.application.CustomerSeatHoldService;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public create endpoint for temporary customer seat holds.
 * Authentication/ownership is deferred; {@code userId} is always null for this phase.
 */
@RestController
@RequestMapping("/api/v1/trips/{tripId}/holds")
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class TripSeatHoldController {

    private final CustomerSeatHoldService customerSeatHoldService;

    public TripSeatHoldController(CustomerSeatHoldService customerSeatHoldService) {
        this.customerSeatHoldService = customerSeatHoldService;
    }

    @PostMapping
    public ResponseEntity<SeatHoldResponse> createHold(
            @PathVariable UUID tripId,
            @Valid @RequestBody CreateSeatHoldRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(customerSeatHoldService.create(tripId, request));
    }
}
