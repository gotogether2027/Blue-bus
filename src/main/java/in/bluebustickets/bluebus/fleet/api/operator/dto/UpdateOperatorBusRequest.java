package in.bluebustickets.bluebus.fleet.api.operator.dto;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.Size;

/**
 * Allow-list PATCH for operator bus display name, bus type, and seat layout.
 * Unknown fields (including operatorId, registrationNumber, status) are rejected.
 */
public class UpdateOperatorBusRequest {

    @Size(max = 120)
    private String displayName;
    private UUID busTypeId;
    private UUID seatLayoutId;

    private boolean displayNamePresent;
    private boolean busTypeIdPresent;
    private boolean seatLayoutIdPresent;

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
        this.displayNamePresent = true;
    }

    public UUID getBusTypeId() {
        return busTypeId;
    }

    public void setBusTypeId(UUID busTypeId) {
        this.busTypeId = busTypeId;
        this.busTypeIdPresent = true;
    }

    public UUID getSeatLayoutId() {
        return seatLayoutId;
    }

    public void setSeatLayoutId(UUID seatLayoutId) {
        this.seatLayoutId = seatLayoutId;
        this.seatLayoutIdPresent = true;
    }

    public boolean hasDisplayName() {
        return displayNamePresent;
    }

    public boolean hasBusTypeId() {
        return busTypeIdPresent;
    }

    public boolean hasSeatLayoutId() {
        return seatLayoutIdPresent;
    }

    public boolean hasSupportedField() {
        return displayNamePresent || busTypeIdPresent || seatLayoutIdPresent;
    }

    @JsonAnySetter
    public void rejectUnknown(String name, Object value) {
        throw new IllegalArgumentException("Unknown field '" + name + "'.");
    }
}
