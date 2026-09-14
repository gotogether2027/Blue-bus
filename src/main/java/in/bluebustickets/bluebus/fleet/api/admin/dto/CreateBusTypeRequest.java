package in.bluebustickets.bluebus.fleet.api.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateBusTypeRequest(
        @NotBlank @Size(max = 60) String code,
        @NotBlank @Size(max = 120) String displayName) {
}
