package in.bluebustickets.bluebus.scheduling.api.admin.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateLocationRequest(
        @Pattern(regexp = "^[A-Z]{2}$", message = "must be a 2-letter ISO country code")
        String countryCode,
        @NotBlank @Size(max = 120) String state,
        @Size(max = 120) String district,
        @NotBlank @Size(max = 120) String city,
        @Size(max = 160) String locality,
        @DecimalMin("-90.0") @DecimalMax("90.0") BigDecimal latitude,
        @DecimalMin("-180.0") @DecimalMax("180.0") BigDecimal longitude,
        @Size(max = 64) String timeZone) {
}
