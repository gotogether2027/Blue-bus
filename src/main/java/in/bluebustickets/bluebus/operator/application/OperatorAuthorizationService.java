package in.bluebustickets.bluebus.operator.application;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import in.bluebustickets.bluebus.foundation.api.error.ApplicationForbiddenException;
import in.bluebustickets.bluebus.foundation.api.error.ResourceNotFoundException;
import in.bluebustickets.bluebus.identity.application.AuthorizationService;
import in.bluebustickets.bluebus.identity.domain.RoleCode;
import in.bluebustickets.bluebus.identity.domain.User;
import in.bluebustickets.bluebus.operator.domain.Operator;
import in.bluebustickets.bluebus.operator.domain.OperatorStatus;
import in.bluebustickets.bluebus.operator.domain.OperatorUser;
import in.bluebustickets.bluebus.operator.domain.OperatorUserStatus;
import in.bluebustickets.bluebus.operator.repository.OperatorRepository;
import in.bluebustickets.bluebus.operator.repository.OperatorUserRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Database-backed authorization for operator-scoped APIs. JWT role claims and client-supplied
 * operator/user identifiers are never treated as proof of membership.
 */
@Service
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class OperatorAuthorizationService {

    static final String HIDDEN_NOT_FOUND = "Resource was not found.";

    private final AuthorizationService authorizationService;
    private final OperatorRepository operatorRepository;
    private final OperatorUserRepository operatorUserRepository;

    public OperatorAuthorizationService(
            AuthorizationService authorizationService,
            OperatorRepository operatorRepository,
            OperatorUserRepository operatorUserRepository) {
        this.authorizationService = authorizationService;
        this.operatorRepository = operatorRepository;
        this.operatorUserRepository = operatorUserRepository;
    }

    @Transactional(readOnly = true)
    public OperatorAccess requireMember(UUID operatorId, RoleCode... allowedOperatorRoles) {
        User user = authorizationService.requireActiveUser();
        if (operatorId == null) {
            throw hiddenNotFound();
        }
        Operator operator = operatorRepository.findById(operatorId).orElseThrow(OperatorAuthorizationService::hiddenNotFound);
        OperatorUser membership = operatorUserRepository
                .findByOperatorIdAndUserId(operatorId, user.getId())
                .orElseThrow(OperatorAuthorizationService::hiddenNotFound);
        if (membership.getStatus() != OperatorUserStatus.ACTIVE) {
            throw hiddenNotFound();
        }
        if (operator.getStatus() != OperatorStatus.ACTIVE) {
            throw forbidden();
        }
        RoleCode membershipRole = membership.getRole().getCode();
        if (!allowed(allowedOperatorRoles).contains(membershipRole)) {
            throw forbidden();
        }
        return new OperatorAccess(user.getId(), operator.getId(), membershipRole, operator.getStatus());
    }

    public static ResourceNotFoundException hiddenNotFound() {
        return new ResourceNotFoundException(HIDDEN_NOT_FOUND);
    }

    private static Set<RoleCode> allowed(RoleCode... allowedOperatorRoles) {
        if (allowedOperatorRoles == null || allowedOperatorRoles.length == 0) {
            return EnumSet.noneOf(RoleCode.class);
        }
        return EnumSet.copyOf(List.of(allowedOperatorRoles));
    }

    private static ApplicationForbiddenException forbidden() {
        return new ApplicationForbiddenException();
    }
}
