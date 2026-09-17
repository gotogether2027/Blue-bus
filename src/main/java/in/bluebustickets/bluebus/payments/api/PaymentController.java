package in.bluebustickets.bluebus.payments.api;

import java.util.List;
import java.util.UUID;

import in.bluebustickets.bluebus.identity.application.CurrentUserService;
import in.bluebustickets.bluebus.payments.api.dto.CreateRefundRequest;
import in.bluebustickets.bluebus.payments.api.dto.PaymentInitiationResponse;
import in.bluebustickets.bluebus.payments.api.dto.PaymentResponse;
import in.bluebustickets.bluebus.payments.api.dto.RazorpayCheckoutVerificationRequest;
import in.bluebustickets.bluebus.payments.api.dto.RefundResponse;
import in.bluebustickets.bluebus.payments.application.PaymentCheckoutVerificationService;
import in.bluebustickets.bluebus.payments.application.PaymentInitiationService;
import in.bluebustickets.bluebus.payments.application.RefundApplicationService;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class PaymentController {

    private final PaymentInitiationService paymentInitiationService;
    private final PaymentCheckoutVerificationService checkoutVerificationService;
    private final RefundApplicationService refundApplicationService;
    private final CurrentUserService currentUserService;

    public PaymentController(
            PaymentInitiationService paymentInitiationService,
            PaymentCheckoutVerificationService checkoutVerificationService,
            RefundApplicationService refundApplicationService,
            CurrentUserService currentUserService) {
        this.paymentInitiationService = paymentInitiationService;
        this.checkoutVerificationService = checkoutVerificationService;
        this.refundApplicationService = refundApplicationService;
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

    @GetMapping("/bookings/{bookingId}/payments")
    public List<PaymentResponse> listByBooking(
            Authentication authentication,
            @PathVariable UUID bookingId) {
        UUID userId = currentUserService.requireAuthenticatedUserId(authentication);
        return paymentInitiationService.listOwnedByBooking(userId, bookingId);
    }

    @GetMapping("/payments/{paymentAttemptId}")
    public PaymentResponse get(
            Authentication authentication,
            @PathVariable UUID paymentAttemptId) {
        UUID userId = currentUserService.requireAuthenticatedUserId(authentication);
        return paymentInitiationService.getOwned(userId, paymentAttemptId);
    }

    @PostMapping("/payments/{paymentAttemptId}/checkout")
    public PaymentResponse verifyCheckout(
            Authentication authentication,
            @PathVariable UUID paymentAttemptId,
            @Valid @RequestBody RazorpayCheckoutVerificationRequest request) {
        UUID userId = currentUserService.requireAuthenticatedUserId(authentication);
        return checkoutVerificationService.verify(
                userId,
                paymentAttemptId,
                request.razorpayOrderId(),
                request.razorpayPaymentId(),
                request.razorpaySignature());
    }

    @PostMapping("/payments/{paymentAttemptId}/refunds")
    @ResponseStatus(HttpStatus.CREATED)
    public RefundResponse refund(
            Authentication authentication,
            @PathVariable UUID paymentAttemptId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody(required = false) CreateRefundRequest request) {
        UUID userId = currentUserService.requireAuthenticatedUserId(authentication);
        String reason = request == null ? null : request.reason();
        return refundApplicationService.refund(userId, paymentAttemptId, idempotencyKey, reason);
    }

    @GetMapping("/bookings/{bookingId}/refunds")
    public List<RefundResponse> listRefundsByBooking(
            Authentication authentication,
            @PathVariable UUID bookingId) {
        UUID userId = currentUserService.requireAuthenticatedUserId(authentication);
        return refundApplicationService.listOwnedByBooking(userId, bookingId);
    }
}
