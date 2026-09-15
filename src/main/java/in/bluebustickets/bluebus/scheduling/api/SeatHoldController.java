package in.bluebustickets.bluebus.scheduling.api;

import java.util.UUID;

import in.bluebustickets.bluebus.scheduling.api.dto.SeatHoldResponse;
import in.bluebustickets.bluebus.scheduling.application.CustomerSeatHoldService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public get/cancel endpoints for temporary customer seat holds.
 * Hold UUID is treated as a capability-style identifier until authentication lands.
 * DELETE cancels (ACTIVE → CANCELLED); it does not physically delete rows.
 */
@RestController
@RequestMapping("/api/v1/holds")
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class SeatHoldController {

    private final CustomerSeatHoldService customerSeatHoldService;

    public SeatHoldController(CustomerSeatHoldService customerSeatHoldService) {
        this.customerSeatHoldService = customerSeatHoldService;
    }

    @GetMapping("/{holdId}")
    public SeatHoldResponse getHold(@PathVariable UUID holdId) {
        return customerSeatHoldService.get(holdId);
    }

    @DeleteMapping("/{holdId}")
    public ResponseEntity<Void> cancelHold(@PathVariable UUID holdId) {
        customerSeatHoldService.cancel(holdId);
        return ResponseEntity.noContent().build();
    }
}
