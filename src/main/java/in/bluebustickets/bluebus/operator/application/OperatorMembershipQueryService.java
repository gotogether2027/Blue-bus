package in.bluebustickets.bluebus.operator.application;

import java.util.List;

import in.bluebustickets.bluebus.identity.application.AuthorizationService;
import in.bluebustickets.bluebus.identity.domain.User;
import in.bluebustickets.bluebus.operator.api.dto.OperatorMembershipResponse;
import in.bluebustickets.bluebus.operator.domain.OperatorStatus;
import in.bluebustickets.bluebus.operator.domain.OperatorUserStatus;
import in.bluebustickets.bluebus.operator.repository.OperatorUserRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class OperatorMembershipQueryService {

    private final AuthorizationService authorizationService;
    private final OperatorUserRepository operatorUserRepository;

    public OperatorMembershipQueryService(
            AuthorizationService authorizationService,
            OperatorUserRepository operatorUserRepository) {
        this.authorizationService = authorizationService;
        this.operatorUserRepository = operatorUserRepository;
    }

    @Transactional(readOnly = true)
    public List<OperatorMembershipResponse> listCurrent() {
        User user = authorizationService.requireActiveUser();
        return operatorUserRepository
                .findByUserIdAndStatusAndOperatorStatus(
                        user.getId(),
                        OperatorUserStatus.ACTIVE,
                        OperatorStatus.ACTIVE)
                .stream()
                .map(membership -> new OperatorMembershipResponse(
                        membership.getOperator().getId(),
                        membership.getOperator().getDisplayName(),
                        membership.getRole().getCode()))
                .toList();
    }
}
