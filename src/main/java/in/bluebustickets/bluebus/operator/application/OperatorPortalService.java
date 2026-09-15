package in.bluebustickets.bluebus.operator.application;

import java.util.UUID;

import in.bluebustickets.bluebus.identity.domain.RoleCode;
import in.bluebustickets.bluebus.operator.api.dto.OperatorProfileResponse;
import in.bluebustickets.bluebus.operator.domain.Operator;
import in.bluebustickets.bluebus.operator.repository.OperatorRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class OperatorPortalService {

    private final OperatorAuthorizationService operatorAuthorizationService;
    private final OperatorRepository operatorRepository;

    public OperatorPortalService(
            OperatorAuthorizationService operatorAuthorizationService,
            OperatorRepository operatorRepository) {
        this.operatorAuthorizationService = operatorAuthorizationService;
        this.operatorRepository = operatorRepository;
    }

    @Transactional(readOnly = true)
    public OperatorProfileResponse get(UUID operatorId) {
        OperatorAccess access = operatorAuthorizationService.requireMember(
                operatorId, RoleCode.OPERATOR_ADMIN, RoleCode.OPERATOR_STAFF);
        return OperatorProfileResponse.from(requireOperator(access.operatorId()));
    }

    @Transactional
    public OperatorProfileResponse updateSupportContact(
            UUID operatorId,
            String supportEmail,
            String supportPhoneE164) {
        OperatorAccess access = operatorAuthorizationService.requireMember(
                operatorId, RoleCode.OPERATOR_ADMIN);
        Operator operator = requireOperator(access.operatorId());
        operator.updateSupportContact(blankToNull(supportEmail), blankToNull(supportPhoneE164));
        return OperatorProfileResponse.from(operator);
    }

    private Operator requireOperator(UUID operatorId) {
        return operatorRepository.findById(operatorId)
                .orElseThrow(OperatorAuthorizationService::hiddenNotFound);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
