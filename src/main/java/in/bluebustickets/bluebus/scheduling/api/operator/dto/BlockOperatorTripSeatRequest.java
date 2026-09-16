package in.bluebustickets.bluebus.scheduling.api.operator.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Physically block one trip seat inventory row. Path ids are authoritative;
 * unknown fields and body operator/trip/inventory ids are rejected.
 */
public class BlockOperatorTripSeatRequest {

    @NotBlank
    @Size(max = 255)
    private String reason;

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    @JsonAnySetter
    public void rejectUnknown(String name, Object value) {
        throw new IllegalArgumentException("Unknown field '" + name + "'.");
    }
}
