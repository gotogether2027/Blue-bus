package in.bluebustickets.bluebus.fleet.api.admin.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Supported bus update fields. Operator and registration number are immutable after create.
 * Bus type and seat layout may be reassigned via existing domain mutators (layout must match operator).
 */
public record UpdateBusRequest(
        @Size(max = 120) String displayName,
        @NotNull UUID busTypeId,
        @NotNull UUID seatLayoutId) {
}
