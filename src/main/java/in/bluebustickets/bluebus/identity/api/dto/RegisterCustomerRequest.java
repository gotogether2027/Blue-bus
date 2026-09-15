package in.bluebustickets.bluebus.identity.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Customer self-registration. Role and status are server-owned.
 * Email format is validated after trim/lower-case normalization in the service
 * so surrounding whitespace does not fail Bean Validation prematurely.
 */
public record RegisterCustomerRequest(
        @NotBlank @Size(max = 100) String firstName,
        @Size(max = 100) String lastName,
        @NotBlank @Size(max = 320) String email,
        @NotBlank
        @Size(min = 8, max = 72, message = "Password must be between 8 and 72 characters")
        @Pattern(
                regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$",
                message = "Password must contain at least one letter and one digit")
        String password) {
}
