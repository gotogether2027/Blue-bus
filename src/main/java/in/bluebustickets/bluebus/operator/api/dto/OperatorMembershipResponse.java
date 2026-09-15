package in.bluebustickets.bluebus.operator.api.dto;

import java.util.UUID;

import in.bluebustickets.bluebus.identity.domain.RoleCode;

public record OperatorMembershipResponse(
        UUID operatorId,
        String operatorDisplayName,
        RoleCode role) {
}
