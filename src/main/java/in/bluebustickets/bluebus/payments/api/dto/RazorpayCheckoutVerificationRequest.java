package in.bluebustickets.bluebus.payments.api.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

public record RazorpayCheckoutVerificationRequest(
        @NotBlank
        @JsonProperty("razorpayPaymentId")
        @JsonAlias("razorpay_payment_id")
        String razorpayPaymentId,
        @NotBlank
        @JsonProperty("razorpayOrderId")
        @JsonAlias("razorpay_order_id")
        String razorpayOrderId,
        @NotBlank
        @JsonProperty("razorpaySignature")
        @JsonAlias("razorpay_signature")
        String razorpaySignature) {
}
