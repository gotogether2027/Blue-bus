package in.bluebustickets.bluebus.identity.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Login credentials. Email is normalized (trim + lower-case) in {@code AuthenticationService}
 * before lookup, so Bean Validation does not apply {@code @Email} to the raw input.
 */
public record LoginRequest(
        @NotBlank @Size(max = 320) String email,
        @NotBlank String password) {
}
