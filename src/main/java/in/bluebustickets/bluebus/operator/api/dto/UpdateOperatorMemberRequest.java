package in.bluebustickets.bluebus.operator.api.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import in.bluebustickets.bluebus.identity.domain.RoleCode;
import in.bluebustickets.bluebus.operator.domain.OperatorUserStatus;

/**
 * Allow-list PATCH for operator membership role/status. Unknown fields are rejected.
 */
public class UpdateOperatorMemberRequest {

    private RoleCode role;
    private OperatorUserStatus status;
    private boolean rolePresent;
    private boolean statusPresent;

    public RoleCode getRole() {
        return role;
    }

    public void setRole(RoleCode role) {
        this.role = role;
        this.rolePresent = true;
    }

    public OperatorUserStatus getStatus() {
        return status;
    }

    public void setStatus(OperatorUserStatus status) {
        this.status = status;
        this.statusPresent = true;
    }

    public boolean hasRole() {
        return rolePresent;
    }

    public boolean hasStatus() {
        return statusPresent;
    }

    public boolean hasSupportedField() {
        return rolePresent || statusPresent;
    }

    @JsonAnySetter
    public void rejectUnknown(String name, Object value) {
        throw new IllegalArgumentException("Unknown field '" + name + "'.");
    }
}
