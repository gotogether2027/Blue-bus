package in.bluebustickets.bluebus.operator.application;

import java.util.List;
import java.util.UUID;

import in.bluebustickets.bluebus.foundation.api.error.ApplicationConflictException;
import in.bluebustickets.bluebus.foundation.api.error.ApplicationForbiddenException;
import in.bluebustickets.bluebus.foundation.api.error.ResourceNotFoundException;
import in.bluebustickets.bluebus.identity.domain.Role;
import in.bluebustickets.bluebus.identity.domain.RoleCode;
import in.bluebustickets.bluebus.identity.domain.User;
import in.bluebustickets.bluebus.identity.repository.RoleRepository;
import in.bluebustickets.bluebus.identity.repository.UserRepository;
import in.bluebustickets.bluebus.operator.api.dto.OperatorMemberResponse;
import in.bluebustickets.bluebus.operator.domain.Operator;
import in.bluebustickets.bluebus.operator.domain.OperatorStatus;
import in.bluebustickets.bluebus.operator.domain.OperatorUser;
import in.bluebustickets.bluebus.operator.domain.OperatorUserStatus;
import in.bluebustickets.bluebus.operator.repository.OperatorRepository;
import in.bluebustickets.bluebus.operator.repository.OperatorUserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Operator-scoped membership administration. Mutations serialize on the operator row
 * ({@code FOR UPDATE}), then revalidate the caller's ACTIVE {@code OPERATOR_ADMIN}
 * membership so concurrent demotion/deactivation cannot ride stale authorization.
 */
@Service
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class OperatorMembershipAdminService {

    private static final String LAST_ADMIN_MESSAGE =
            "Operator must retain at least one ACTIVE OPERATOR_ADMIN membership.";

    @PersistenceContext
    private EntityManager entityManager;

    private final OperatorAuthorizationService operatorAuthorizationService;
    private final OperatorRepository operatorRepository;
    private final OperatorUserRepository operatorUserRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;

    /**
     * Optional test barrier invoked after the initial authorize check and before the operator
     * row lock. Production remains {@code null}.
     */
    private volatile Runnable afterAuthorizeBeforeLockForTests;

    public OperatorMembershipAdminService(
            OperatorAuthorizationService operatorAuthorizationService,
            OperatorRepository operatorRepository,
            OperatorUserRepository operatorUserRepository,
            UserRepository userRepository,
            RoleRepository roleRepository) {
        this.operatorAuthorizationService = operatorAuthorizationService;
        this.operatorRepository = operatorRepository;
        this.operatorUserRepository = operatorUserRepository;
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
    }

    @Transactional(readOnly = true)
    public List<OperatorMemberResponse> list(UUID operatorId) {
        operatorAuthorizationService.requireMember(
                operatorId, RoleCode.OPERATOR_ADMIN, RoleCode.OPERATOR_STAFF);
        return operatorUserRepository.findDetailedByOperatorIdOrderByUserName(operatorId).stream()
                .map(OperatorMemberResponse::from)
                .toList();
    }

    @Transactional
    public OperatorMemberResponse add(UUID operatorId, UUID userId, RoleCode roleCode) {
        authorizeAdminMutation(operatorId);
        RoleCode membershipRole = requireOperatorMembershipRole(roleCode);

        if (operatorUserRepository.findByOperatorIdAndUserId(operatorId, userId).isPresent()) {
            throw new ApplicationConflictException("Operator membership already exists.");
        }

        Operator operator = operatorRepository.findById(operatorId)
                .orElseThrow(OperatorAuthorizationService::hiddenNotFound);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User was not found."));
        Role role = roleRepository.findByCode(membershipRole)
                .orElseThrow(() -> new IllegalStateException("Operator role is not seeded: " + membershipRole));

        try {
            OperatorUser membership = operatorUserRepository.saveAndFlush(new OperatorUser(operator, user, role));
            return OperatorMemberResponse.from(reloadDetailed(operatorId, membership.getUser().getId()));
        } catch (DataIntegrityViolationException exception) {
            throw new ApplicationConflictException("Operator membership already exists.");
        }
    }

    @Transactional
    public OperatorMemberResponse update(
            UUID operatorId,
            UUID userId,
            RoleCode roleCode,
            boolean rolePresent,
            OperatorUserStatus status,
            boolean statusPresent) {
        authorizeAdminMutation(operatorId);
        if (!rolePresent && !statusPresent) {
            throw new IllegalArgumentException("At least one of role or status is required.");
        }
        if (rolePresent) {
            requireOperatorMembershipRole(roleCode);
        }
        if (statusPresent && status == null) {
            throw new IllegalArgumentException("status is required when provided.");
        }

        OperatorUser membership = lockMembershipForUpdate(operatorId, userId);

        RoleCode resultingRole = rolePresent ? roleCode : membership.getRole().getCode();
        OperatorUserStatus resultingStatus = statusPresent ? status : membership.getStatus();
        guardLastActiveAdmin(operatorId, membership, resultingRole, resultingStatus);

        if (rolePresent) {
            Role role = roleRepository.findByCode(resultingRole)
                    .orElseThrow(() -> new IllegalStateException("Operator role is not seeded: " + resultingRole));
            membership.assignRole(role);
        }
        if (statusPresent) {
            if (resultingStatus == OperatorUserStatus.ACTIVE) {
                membership.activate();
            } else {
                membership.deactivate();
            }
        }
        entityManager.flush();
        return OperatorMemberResponse.from(reloadDetailed(operatorId, userId));
    }

    @Transactional
    public OperatorMemberResponse deactivate(UUID operatorId, UUID userId) {
        authorizeAdminMutation(operatorId);
        OperatorUser membership = lockMembershipForUpdate(operatorId, userId);
        if (membership.getStatus() == OperatorUserStatus.INACTIVE) {
            return OperatorMemberResponse.from(reloadDetailed(operatorId, userId));
        }
        guardLastActiveAdmin(
                operatorId,
                membership,
                membership.getRole().getCode(),
                OperatorUserStatus.INACTIVE);
        membership.deactivate();
        entityManager.flush();
        return OperatorMemberResponse.from(reloadDetailed(operatorId, userId));
    }

    /**
     * Early authorize for fast failure, serialize on the operator row, then re-read the caller's
     * membership and operator status so concurrent invalidation cannot proceed on a stale check.
     */
    private OperatorAccess authorizeAdminMutation(UUID operatorId) {
        OperatorAccess earlyAccess = operatorAuthorizationService.requireMember(
                operatorId, RoleCode.OPERATOR_ADMIN);
        Runnable barrier = afterAuthorizeBeforeLockForTests;
        if (barrier != null) {
            barrier.run();
        }
        Operator operator = lockOperatorForUpdate(operatorId);
        return revalidateCallerAdminAfterOperatorLock(operator, earlyAccess.userId());
    }

    private OperatorAccess revalidateCallerAdminAfterOperatorLock(Operator operator, UUID callerUserId) {
        if (operator.getStatus() != OperatorStatus.ACTIVE) {
            throw new ApplicationForbiddenException();
        }
        OperatorUser callerMembership = operatorUserRepository
                .findByOperatorIdAndUserId(operator.getId(), callerUserId)
                .orElseThrow(OperatorAuthorizationService::hiddenNotFound);
        // Persistence-context snapshot from the early authorize must not win over concurrent commits.
        entityManager.refresh(callerMembership);
        if (callerMembership.getStatus() != OperatorUserStatus.ACTIVE) {
            throw OperatorAuthorizationService.hiddenNotFound();
        }
        if (callerMembership.getRole().getCode() != RoleCode.OPERATOR_ADMIN) {
            throw new ApplicationForbiddenException();
        }
        return new OperatorAccess(
                callerUserId,
                operator.getId(),
                RoleCode.OPERATOR_ADMIN,
                operator.getStatus());
    }

    private void guardLastActiveAdmin(
            UUID operatorId,
            OperatorUser membership,
            RoleCode resultingRole,
            OperatorUserStatus resultingStatus) {
        boolean currentlyActiveAdmin = membership.getStatus() == OperatorUserStatus.ACTIVE
                && membership.getRole().getCode() == RoleCode.OPERATOR_ADMIN;
        boolean willBeActiveAdmin = resultingStatus == OperatorUserStatus.ACTIVE
                && resultingRole == RoleCode.OPERATOR_ADMIN;
        if (currentlyActiveAdmin && !willBeActiveAdmin) {
            long activeAdmins = operatorUserRepository.countActiveOperatorAdmins(
                    operatorId, OperatorUserStatus.ACTIVE, RoleCode.OPERATOR_ADMIN);
            if (activeAdmins <= 1) {
                throw new ApplicationConflictException(LAST_ADMIN_MESSAGE);
            }
        }
    }

    private static RoleCode requireOperatorMembershipRole(RoleCode roleCode) {
        if (roleCode == null
                || (roleCode != RoleCode.OPERATOR_ADMIN && roleCode != RoleCode.OPERATOR_STAFF)) {
            throw new IllegalArgumentException("role must be OPERATOR_ADMIN or OPERATOR_STAFF.");
        }
        return roleCode;
    }

    @SuppressWarnings("unchecked")
    private Operator lockOperatorForUpdate(UUID operatorId) {
        List<Operator> rows = entityManager.createNativeQuery("""
                SELECT *
                FROM operators
                WHERE id = :id
                FOR UPDATE
                """, Operator.class)
                .setParameter("id", operatorId)
                .getResultList();
        if (rows.isEmpty()) {
            throw OperatorAuthorizationService.hiddenNotFound();
        }
        return rows.get(0);
    }

    private OperatorUser lockMembershipForUpdate(UUID operatorId, UUID userId) {
        OperatorUser membership = operatorUserRepository.findByOperatorIdAndUserId(operatorId, userId)
                .orElseThrow(OperatorAuthorizationService::hiddenNotFound);
        entityManager.lock(membership, LockModeType.PESSIMISTIC_WRITE);
        return membership;
    }

    private OperatorUser reloadDetailed(UUID operatorId, UUID userId) {
        return operatorUserRepository.findDetailedByOperatorIdOrderByUserName(operatorId).stream()
                .filter(membership -> membership.getUser().getId().equals(userId))
                .findFirst()
                .orElseThrow(OperatorAuthorizationService::hiddenNotFound);
    }
}
