package in.bluebustickets.bluebus.scheduling.api.admin.dto;

import java.math.BigDecimal;

import in.bluebustickets.bluebus.scheduling.domain.PointType;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RoutePointDefinitionRequest(
        @NotBlank @Size(max = 160) String name,
        @NotNull PointType pointType,
        @Size(max = 255) String address,
        @DecimalMin("-90.0") @DecimalMax("90.0") BigDecimal latitude,
        @DecimalMin("-180.0") @DecimalMax("180.0") BigDecimal longitude,
        Boolean active) {
}
