package in.bluebustickets.bluebus.identity.application;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import in.bluebustickets.bluebus.foundation.api.error.ApplicationForbiddenException;
import in.bluebustickets.bluebus.identity.domain.RoleCode;
import in.bluebustickets.bluebus.identity.domain.User;
import in.bluebustickets.bluebus.identity.domain.UserRole;
import in.bluebustickets.bluebus.identity.domain.UserStatus;
import in.bluebustickets.bluebus.identity.repository.UserRepository;
import in.bluebustickets.bluebus.identity.repository.UserRoleRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Database-backed authorization for platform privileges and active-user status.
 * JWT role claims are never treated as authoritative for admin access.
 */
@Service
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class AuthorizationService {

    static final String CACHED_USER_ATTRIBUTE = AuthorizationService.class.getName() + ".user";
    static final String CACHED_ROLE_CODES_ATTRIBUTE = AuthorizationService.class.getName() + ".roleCodes";

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;

    public AuthorizationService(UserRepository userRepository, UserRoleRepository userRoleRepository) {
        this.userRepository = userRepository;
        this.userRoleRepository = userRoleRepository;
    }

    @Transactional(readOnly = true)
    public User requireActiveUser() {
        return requireActiveUser(currentUserId());
    }

    @Transactional(readOnly = true)
    public User requireActiveUser(UUID userId) {
        User user = loadUser(userId);
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw unauthorized();
        }
        return user;
    }

    @Transactional(readOnly = true)
    public User requirePlatformAdmin() {
        return requirePlatformAdmin(currentUserId());
    }

    @Transactional(readOnly = true)
    public User requirePlatformAdmin(UUID userId) {
        User user = requireActiveUser(userId);
        if (!hasAnyPlatformRole(userId, RoleCode.ADMIN, RoleCode.SUPER_ADMIN)) {
            throw forbidden();
        }
        return user;
    }

    @Transactional(readOnly = true)
    public User requireSuperAdmin() {
        return requireSuperAdmin(currentUserId());
    }

    @Transactional(readOnly = true)
    public User requireSuperAdmin(UUID userId) {
        User user = requireActiveUser(userId);
        if (!hasAnyPlatformRole(userId, RoleCode.SUPER_ADMIN)) {
            throw forbidden();
        }
        return user;
    }

    private User loadUser(UUID userId) {
        if (userId == null) {
            throw unauthorized();
        }
        User cached = cachedUser();
        if (cached != null && userId.equals(cached.getId())) {
            return cached;
        }
        User user = userRepository.findById(userId).orElseThrow(AuthorizationService::unauthorized);
        cacheUser(user);
        return user;
    }

    private boolean hasAnyPlatformRole(UUID userId, RoleCode... allowed) {
        Set<RoleCode> allowedCodes = EnumSet.noneOf(RoleCode.class);
        allowedCodes.addAll(List.of(allowed));
        return platformRoleCodes(userId).stream().anyMatch(allowedCodes::contains);
    }

    @SuppressWarnings("unchecked")
    private Set<RoleCode> platformRoleCodes(UUID userId) {
        Set<RoleCode> cached = (Set<RoleCode>) requestAttribute(CACHED_ROLE_CODES_ATTRIBUTE);
        if (cached != null) {
            return cached;
        }
        Set<RoleCode> codes = EnumSet.noneOf(RoleCode.class);
        for (UserRole membership : userRoleRepository.findByUserIdWithRole(userId)) {
            codes.add(membership.getRole().getCode());
        }
        setRequestAttribute(CACHED_ROLE_CODES_ATTRIBUTE, codes);
        return codes;
    }

    private UUID currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            throw unauthorized();
        }
        Object principal = authentication.getPrincipal();
        String subject;
        if (principal instanceof Jwt jwt) {
            subject = jwt.getSubject();
        } else if (authentication.getName() != null) {
            subject = authentication.getName();
        } else {
            throw unauthorized();
        }
        try {
            return UUID.fromString(subject);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw unauthorized();
        }
    }

    private User cachedUser() {
        Object cached = requestAttribute(CACHED_USER_ATTRIBUTE);
        return cached instanceof User user ? user : null;
    }

    private void cacheUser(User user) {
        setRequestAttribute(CACHED_USER_ATTRIBUTE, user);
    }

    private static Object requestAttribute(String name) {
        HttpServletRequest request = currentRequest();
        return request == null ? null : request.getAttribute(name);
    }

    private static void setRequestAttribute(String name, Object value) {
        HttpServletRequest request = currentRequest();
        if (request != null) {
            request.setAttribute(name, value);
        }
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            attributes.setAttribute(name, value, RequestAttributes.SCOPE_REQUEST);
        }
    }

    private static HttpServletRequest currentRequest() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            return servletAttributes.getRequest();
        }
        return null;
    }

    private static BadCredentialsException unauthorized() {
        return new BadCredentialsException("Invalid credentials.");
    }

    private static ApplicationForbiddenException forbidden() {
        return new ApplicationForbiddenException();
    }
}
