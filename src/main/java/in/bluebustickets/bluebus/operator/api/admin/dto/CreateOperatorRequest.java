package in.bluebustickets.bluebus.operator.api.admin.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateOperatorRequest(
        @NotBlank @Size(max = 255) String legalName,
        @NotBlank @Size(max = 255) String displayName,
        @Email @Size(max = 320) String supportEmail,
        @Pattern(regexp = "^\\+[1-9]\\d{1,14}$", message = "must be an E.164 phone number")
        @Size(max = 20) String supportPhoneE164) {
}
