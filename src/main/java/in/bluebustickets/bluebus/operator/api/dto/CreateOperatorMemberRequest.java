package in.bluebustickets.bluebus.operator.api.dto;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import in.bluebustickets.bluebus.identity.domain.RoleCode;
import jakarta.validation.constraints.NotNull;

/**
 * Create operator membership for an existing platform user. Path {@code operatorId} is authoritative.
 */
public class CreateOperatorMemberRequest {

    @NotNull
    private UUID userId;

    @NotNull
    private RoleCode role;

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public RoleCode getRole() {
        return role;
    }

    public void setRole(RoleCode role) {
        this.role = role;
    }

    @JsonAnySetter
    public void rejectUnknown(String name, Object value) {
        throw new IllegalArgumentException("Unknown field '" + name + "'.");
    }
}
