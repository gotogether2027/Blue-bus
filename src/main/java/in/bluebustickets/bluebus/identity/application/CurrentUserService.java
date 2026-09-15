package in.bluebustickets.bluebus.identity.application;

import java.util.List;
import java.util.UUID;

import in.bluebustickets.bluebus.foundation.api.error.ResourceNotFoundException;
import in.bluebustickets.bluebus.identity.api.dto.CustomerIdentityResponse;
import in.bluebustickets.bluebus.identity.domain.User;
import in.bluebustickets.bluebus.identity.domain.UserRole;
import in.bluebustickets.bluebus.identity.repository.UserRepository;
import in.bluebustickets.bluebus.identity.repository.UserRoleRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Authenticated customer identity for {@code GET /api/v1/auth/me}.
 * Identity is taken from the JWT subject (user id); never from request parameters.
 */
@Service
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class CurrentUserService {

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;

    public CurrentUserService(UserRepository userRepository, UserRoleRepository userRoleRepository) {
        this.userRepository = userRepository;
        this.userRoleRepository = userRoleRepository;
    }

    @Transactional(readOnly = true)
    public CustomerIdentityResponse currentUser(Authentication authentication) {
        UUID userId = requireAuthenticatedUserId(authentication);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User was not found."));
        List<String> roles = userRoleRepository.findByUserIdWithRole(userId).stream()
                .map(UserRole::getRole)
                .map(role -> role.getCode().name())
                .sorted()
                .toList();
        return new CustomerIdentityResponse(
                user.getId(),
                user.getFirstName(),
                user.getLastName(),
                user.getEmail(),
                roles,
                user.getStatus());
    }

    public UUID requireAuthenticatedUserId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ResourceNotFoundException("User was not found.");
        }
        Object principal = authentication.getPrincipal();
        String subject;
        if (principal instanceof Jwt jwt) {
            subject = jwt.getSubject();
        } else if (authentication.getName() != null) {
            subject = authentication.getName();
        } else {
            throw new ResourceNotFoundException("User was not found.");
        }
        try {
            return UUID.fromString(subject);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new ResourceNotFoundException("User was not found.");
        }
    }
}
