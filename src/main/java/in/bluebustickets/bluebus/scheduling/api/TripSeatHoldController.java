package in.bluebustickets.bluebus.scheduling.api;

import java.util.UUID;

import in.bluebustickets.bluebus.identity.application.CurrentUserService;
import in.bluebustickets.bluebus.scheduling.api.dto.CreateSeatHoldRequest;
import in.bluebustickets.bluebus.scheduling.api.dto.SeatHoldResponse;
import in.bluebustickets.bluebus.scheduling.application.CustomerSeatHoldService;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Create endpoint for temporary customer seat holds.
 * Remains publicly reachable, but when a Bearer JWT is present the hold is owned by that user
 * ({@code seat_holds.user_id}). Bookings require an owned hold — anonymous holds cannot be booked.
 */
@RestController
@RequestMapping("/api/v1/trips/{tripId}/holds")
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class TripSeatHoldController {

    private final CustomerSeatHoldService customerSeatHoldService;
    private final CurrentUserService currentUserService;

    public TripSeatHoldController(
            CustomerSeatHoldService customerSeatHoldService,
            CurrentUserService currentUserService) {
        this.customerSeatHoldService = customerSeatHoldService;
        this.currentUserService = currentUserService;
    }

    @PostMapping
    public ResponseEntity<SeatHoldResponse> createHold(
            @PathVariable UUID tripId,
            @Valid @RequestBody CreateSeatHoldRequest request,
            Authentication authentication) {
        UUID userId = currentUserService.optionalAuthenticatedUserId(authentication);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(customerSeatHoldService.create(tripId, request, userId));
    }
}
