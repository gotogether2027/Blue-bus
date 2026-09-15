package in.bluebustickets.bluebus.operator.api.dto;

import java.util.UUID;

import in.bluebustickets.bluebus.identity.domain.RoleCode;
import in.bluebustickets.bluebus.operator.domain.OperatorUser;
import in.bluebustickets.bluebus.operator.domain.OperatorUserStatus;

public record OperatorMemberResponse(
        UUID userId,
        String email,
        String firstName,
        String lastName,
        RoleCode role,
        OperatorUserStatus status) {

    public static OperatorMemberResponse from(OperatorUser membership) {
        return new OperatorMemberResponse(
                membership.getUser().getId(),
                membership.getUser().getEmail(),
                membership.getUser().getFirstName(),
                membership.getUser().getLastName(),
                membership.getRole().getCode(),
                membership.getStatus());
    }
}
