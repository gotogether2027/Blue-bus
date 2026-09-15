package in.bluebustickets.bluebus.payments.api;

import java.util.UUID;

import in.bluebustickets.bluebus.identity.application.CurrentUserService;
import in.bluebustickets.bluebus.payments.api.dto.PaymentInitiationResponse;
import in.bluebustickets.bluebus.payments.api.dto.PaymentResponse;
import in.bluebustickets.bluebus.payments.application.PaymentInitiationService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class PaymentController {

    private final PaymentInitiationService paymentInitiationService;
    private final CurrentUserService currentUserService;

    public PaymentController(
            PaymentInitiationService paymentInitiationService,
            CurrentUserService currentUserService) {
        this.paymentInitiationService = paymentInitiationService;
        this.currentUserService = currentUserService;
    }

    @PostMapping("/bookings/{bookingId}/payments")
    @ResponseStatus(HttpStatus.CREATED)
    public PaymentInitiationResponse initiate(
            Authentication authentication,
            @PathVariable UUID bookingId,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        UUID userId = currentUserService.requireAuthenticatedUserId(authentication);
        return paymentInitiationService.initiate(userId, bookingId, idempotencyKey);
    }

    @GetMapping("/payments/{paymentAttemptId}")
    public PaymentResponse get(
            Authentication authentication,
            @PathVariable UUID paymentAttemptId) {
        UUID userId = currentUserService.requireAuthenticatedUserId(authentication);
        return paymentInitiationService.getOwned(userId, paymentAttemptId);
    }
}
