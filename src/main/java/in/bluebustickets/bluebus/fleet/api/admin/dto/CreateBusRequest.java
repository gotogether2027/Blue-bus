package in.bluebustickets.bluebus.fleet.api.admin.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateBusRequest(
        @NotNull UUID operatorId,
        @NotNull UUID busTypeId,
        @NotNull UUID seatLayoutId,
        @NotBlank
        @Pattern(regexp = "^[A-Za-z0-9 -]{4,30}$", message = "must be a valid registration number")
        String registrationNumber,
        @Size(max = 120) String displayName) {
}
