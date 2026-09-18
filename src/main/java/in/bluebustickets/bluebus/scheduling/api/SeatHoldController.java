package in.bluebustickets.bluebus.scheduling.api;

import java.util.UUID;

import in.bluebustickets.bluebus.identity.application.CurrentUserService;
import in.bluebustickets.bluebus.scheduling.api.dto.SeatHoldResponse;
import in.bluebustickets.bluebus.scheduling.application.CustomerSeatHoldService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public get/cancel endpoints for temporary customer seat holds.
 * Anonymous holds are capability-style (UUID knowledge). Owned holds require the owner JWT;
 * other customers receive the same 404 as a missing hold.
 * DELETE cancels (ACTIVE → CANCELLED); it does not physically delete rows.
 */
@RestController
@RequestMapping("/api/v1/holds")
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class SeatHoldController {

    private final CustomerSeatHoldService customerSeatHoldService;
    private final CurrentUserService currentUserService;

    public SeatHoldController(
            CustomerSeatHoldService customerSeatHoldService, CurrentUserService currentUserService) {
        this.customerSeatHoldService = customerSeatHoldService;
        this.currentUserService = currentUserService;
    }

    @GetMapping("/{holdId}")
    public SeatHoldResponse getHold(@PathVariable UUID holdId, Authentication authentication) {
        return customerSeatHoldService.get(holdId, currentUserService.optionalAuthenticatedUserId(authentication));
    }

    @DeleteMapping("/{holdId}")
    public ResponseEntity<Void> cancelHold(@PathVariable UUID holdId, Authentication authentication) {
        customerSeatHoldService.cancel(holdId, currentUserService.optionalAuthenticatedUserId(authentication));
        return ResponseEntity.noContent().build();
    }
}
