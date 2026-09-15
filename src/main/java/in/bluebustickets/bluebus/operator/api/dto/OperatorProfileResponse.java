package in.bluebustickets.bluebus.operator.api.dto;

import java.util.UUID;

import in.bluebustickets.bluebus.operator.domain.Operator;
import in.bluebustickets.bluebus.operator.domain.OperatorStatus;

public record OperatorProfileResponse(
        UUID id,
        String legalName,
        String displayName,
        OperatorStatus status,
        String supportEmail,
        String supportPhoneE164) {

    public static OperatorProfileResponse from(Operator operator) {
        return new OperatorProfileResponse(
                operator.getId(),
                operator.getLegalName(),
                operator.getDisplayName(),
                operator.getStatus(),
                operator.getSupportEmail(),
                operator.getSupportPhoneE164());
    }
}
