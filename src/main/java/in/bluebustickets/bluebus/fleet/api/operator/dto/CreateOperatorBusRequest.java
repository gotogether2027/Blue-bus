package in.bluebustickets.bluebus.fleet.api.operator.dto;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Create an operator-owned bus. Path {@code operatorId} is authoritative; body operatorId is rejected.
 */
public class CreateOperatorBusRequest {

    @NotNull
    private UUID busTypeId;

    @NotNull
    private UUID seatLayoutId;

    @NotBlank
    @Pattern(regexp = "^[A-Za-z0-9 -]{4,30}$", message = "must be a valid registration number")
    private String registrationNumber;

    @Size(max = 120)
    private String displayName;

    public UUID getBusTypeId() {
        return busTypeId;
    }

    public void setBusTypeId(UUID busTypeId) {
        this.busTypeId = busTypeId;
    }

    public UUID getSeatLayoutId() {
        return seatLayoutId;
    }

    public void setSeatLayoutId(UUID seatLayoutId) {
        this.seatLayoutId = seatLayoutId;
    }

    public String getRegistrationNumber() {
        return registrationNumber;
    }

    public void setRegistrationNumber(String registrationNumber) {
        this.registrationNumber = registrationNumber;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    @JsonAnySetter
    public void rejectUnknown(String name, Object value) {
        throw new IllegalArgumentException("Unknown field '" + name + "'.");
    }
}
