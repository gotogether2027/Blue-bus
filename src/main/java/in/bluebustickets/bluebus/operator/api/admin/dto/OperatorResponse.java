package in.bluebustickets.bluebus.operator.api.admin.dto;

import java.util.UUID;

import in.bluebustickets.bluebus.operator.domain.Operator;
import in.bluebustickets.bluebus.operator.domain.OperatorStatus;

public record OperatorResponse(
        UUID id,
        String legalName,
        String displayName,
        OperatorStatus status,
        String supportEmail,
        String supportPhoneE164) {

    public static OperatorResponse from(Operator operator) {
        return new OperatorResponse(
                operator.getId(),
                operator.getLegalName(),
                operator.getDisplayName(),
                operator.getStatus(),
                operator.getSupportEmail(),
                operator.getSupportPhoneE164());
    }
}
