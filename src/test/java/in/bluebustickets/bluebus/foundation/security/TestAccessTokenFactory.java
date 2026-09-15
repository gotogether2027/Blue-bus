package in.bluebustickets.bluebus.foundation.security;

import java.util.List;
import java.util.UUID;

import in.bluebustickets.bluebus.foundation.security.JwtConfiguration.JwtTokenService;
import in.bluebustickets.bluebus.identity.domain.Role;
import in.bluebustickets.bluebus.identity.domain.RoleCode;
import in.bluebustickets.bluebus.identity.domain.RoleScope;
import in.bluebustickets.bluebus.identity.domain.User;
import in.bluebustickets.bluebus.identity.domain.UserRole;
import in.bluebustickets.bluebus.identity.domain.UserStatus;
import in.bluebustickets.bluebus.identity.repository.RoleRepository;
import in.bluebustickets.bluebus.identity.repository.UserRepository;
import in.bluebustickets.bluebus.identity.repository.UserRoleRepository;
import in.bluebustickets.bluebus.operator.domain.Operator;
import in.bluebustickets.bluebus.operator.domain.OperatorUser;
import in.bluebustickets.bluebus.operator.repository.OperatorRepository;
import in.bluebustickets.bluebus.operator.repository.OperatorUserRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Issues real HS256 access tokens for RBAC/integration tests. JWT claims may differ from DB roles
 * so tests can prove database authorization is authoritative.
 */
@Component
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class TestAccessTokenFactory {

    public static final String PASSWORD = "CorrectHorseBatteryStaple!";

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;
    private final OperatorRepository operatorRepository;
    private final OperatorUserRepository operatorUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService jwtTokenService;

    public TestAccessTokenFactory(
            UserRepository userRepository,
            RoleRepository roleRepository,
            UserRoleRepository userRoleRepository,
            OperatorRepository operatorRepository,
            OperatorUserRepository operatorUserRepository,
            PasswordEncoder passwordEncoder,
            JwtTokenService jwtTokenService) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.userRoleRepository = userRoleRepository;
        this.operatorRepository = operatorRepository;
        this.operatorUserRepository = operatorUserRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenService = jwtTokenService;
    }

    public IssuedUser issuePlatformAdmin() {
        return issuePlatformUser(RoleCode.ADMIN, List.of("ADMIN"), UserStatus.ACTIVE);
    }

    public IssuedUser issueSuperAdmin() {
        return issuePlatformUser(RoleCode.SUPER_ADMIN, List.of("SUPER_ADMIN"), UserStatus.ACTIVE);
    }

    public IssuedUser issueCustomer() {
        return issuePlatformUser(RoleCode.CUSTOMER, List.of("CUSTOMER"), UserStatus.ACTIVE);
    }

    public IssuedUser issuePlatformUser(RoleCode dbRole, List<String> jwtRoles, UserStatus status) {
        if (dbRole.requiredScope() != RoleScope.PLATFORM) {
            throw new IllegalArgumentException("Platform user factory requires a PLATFORM role");
        }
        User user = persistUser(status);
        Role role = roleRepository.findByCode(dbRole).orElseThrow();
        userRoleRepository.saveAndFlush(new UserRole(user, role));
        return issueToken(user, jwtRoles);
    }

    public IssuedUser issueOperatorMember(RoleCode operatorRole, List<String> jwtRoles) {
        if (operatorRole.requiredScope() != RoleScope.OPERATOR) {
            throw new IllegalArgumentException("Operator member factory requires an OPERATOR role");
        }
        User user = persistUser(UserStatus.ACTIVE);
        Operator operator = operatorRepository.saveAndFlush(
                new Operator("RBAC Operator " + UUID.randomUUID(), "RBAC Op"));
        Role role = roleRepository.findByCode(operatorRole).orElseThrow();
        operatorUserRepository.saveAndFlush(new OperatorUser(operator, user, role));
        return issueToken(user, jwtRoles);
    }

    public IssuedOperatorMember issueActiveOperatorMember(RoleCode operatorRole, List<String> jwtRoles) {
        if (operatorRole.requiredScope() != RoleScope.OPERATOR) {
            throw new IllegalArgumentException("Operator member factory requires an OPERATOR role");
        }
        User user = persistUser(UserStatus.ACTIVE);
        Operator operator = persistActiveOperator();
        Role role = roleRepository.findByCode(operatorRole).orElseThrow();
        operatorUserRepository.saveAndFlush(new OperatorUser(operator, user, role));
        IssuedUser issued = issueToken(user, jwtRoles);
        return new IssuedOperatorMember(issued.user(), issued.accessToken(), operator);
    }

    public Operator persistActiveOperator() {
        Operator operator = operatorRepository.saveAndFlush(
                new Operator("Portal Operator " + UUID.randomUUID(), "Portal Op " + UUID.randomUUID()));
        operator.activate();
        return operatorRepository.saveAndFlush(operator);
    }

    public OperatorUser attachMembership(Operator operator, User user, RoleCode operatorRole) {
        if (operatorRole.requiredScope() != RoleScope.OPERATOR) {
            throw new IllegalArgumentException("Operator member factory requires an OPERATOR role");
        }
        Role role = roleRepository.findByCode(operatorRole).orElseThrow();
        return operatorUserRepository.saveAndFlush(new OperatorUser(operator, user, role));
    }

    public IssuedUser issueToken(User user, List<String> jwtRoles) {
        String token = jwtTokenService.issueAccessToken(user.getId(), user.getEmail(), jwtRoles).tokenValue();
        return new IssuedUser(user, token);
    }

    public static RequestPostProcessor bearer(String accessToken) {
        return request -> {
            request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
            return request;
        };
    }

    private User persistUser(UserStatus status) {
        UUID id = UUID.randomUUID();
        String suffix = id.toString().replace("-", "").substring(0, 12);
        User user = new User(
                "rbac-" + suffix + "@example.test",
                "+9198" + String.format("%08d", Math.floorMod(id.getLeastSignificantBits(), 100_000_000L)),
                "Rbac",
                "User");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setStatus(status);
        return userRepository.saveAndFlush(user);
    }

    public record IssuedUser(User user, String accessToken) {
    }

    public record IssuedOperatorMember(User user, String accessToken, Operator operator) {
    }
}
