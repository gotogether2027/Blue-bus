package in.bluebustickets.bluebus.identity.application;

import java.util.List;

import in.bluebustickets.bluebus.foundation.security.JwtConfiguration.IssuedAccessToken;
import in.bluebustickets.bluebus.foundation.security.JwtConfiguration.JwtTokenService;
import in.bluebustickets.bluebus.identity.api.dto.LoginRequest;
import in.bluebustickets.bluebus.identity.api.dto.LoginResponse;
import in.bluebustickets.bluebus.identity.domain.User;
import in.bluebustickets.bluebus.identity.domain.UserRole;
import in.bluebustickets.bluebus.identity.domain.UserStatus;
import in.bluebustickets.bluebus.identity.repository.UserRepository;
import in.bluebustickets.bluebus.identity.repository.UserRoleRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Email/password login for existing users. Issues a JWT access token; registration and refresh
 * tokens are deferred.
 */
@Service
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class AuthenticationService {

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService jwtTokenService;

    public AuthenticationService(
            UserRepository userRepository,
            UserRoleRepository userRoleRepository,
            PasswordEncoder passwordEncoder,
            JwtTokenService jwtTokenService) {
        this.userRepository = userRepository;
        this.userRoleRepository = userRoleRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenService = jwtTokenService;
    }

    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        if (request == null || request.email() == null || request.password() == null) {
            throw invalidCredentials();
        }

        String email = EmailNormalizer.normalize(request.email());
        User user = userRepository.findByEmailIgnoreCase(email).orElse(null);
        if (user == null
                || user.getPasswordHash() == null
                || user.getPasswordHash().isBlank()
                || user.getStatus() != UserStatus.ACTIVE
                || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw invalidCredentials();
        }

        List<UserRole> memberships = userRoleRepository.findByUserIdWithRole(user.getId());
        List<String> roles = memberships.stream()
                .map(membership -> membership.getRole().getCode().name())
                .toList();

        IssuedAccessToken token = jwtTokenService.issueAccessToken(user.getId(), user.getEmail(), roles);
        return LoginResponse.bearer(token.tokenValue(), token.expiresInSeconds());
    }

    private static BadCredentialsException invalidCredentials() {
        return new BadCredentialsException("Invalid credentials.");
    }
}
