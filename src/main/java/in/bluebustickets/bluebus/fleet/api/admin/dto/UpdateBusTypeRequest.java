package in.bluebustickets.bluebus.fleet.api.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateBusTypeRequest(
        @NotBlank @Size(max = 120) String displayName) {
}
