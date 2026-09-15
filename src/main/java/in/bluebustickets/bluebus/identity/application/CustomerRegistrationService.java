package in.bluebustickets.bluebus.identity.application;

import java.util.List;
import java.util.regex.Pattern;

import in.bluebustickets.bluebus.foundation.api.error.ApplicationConflictException;
import in.bluebustickets.bluebus.identity.api.dto.CustomerIdentityResponse;
import in.bluebustickets.bluebus.identity.api.dto.RegisterCustomerRequest;
import in.bluebustickets.bluebus.identity.domain.Role;
import in.bluebustickets.bluebus.identity.domain.RoleCode;
import in.bluebustickets.bluebus.identity.domain.User;
import in.bluebustickets.bluebus.identity.domain.UserRole;
import in.bluebustickets.bluebus.identity.domain.UserStatus;
import in.bluebustickets.bluebus.identity.repository.RoleRepository;
import in.bluebustickets.bluebus.identity.repository.UserRepository;
import in.bluebustickets.bluebus.identity.repository.UserRoleRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Customer self-registration. Always assigns {@link RoleCode#CUSTOMER}; never trusts client role/status.
 */
@Service
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class CustomerRegistrationService {

    /** Practical email shape after trim/lower-case; not full RFC 5322. */
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;
    private final PasswordEncoder passwordEncoder;

    public CustomerRegistrationService(
            UserRepository userRepository,
            RoleRepository roleRepository,
            UserRoleRepository userRoleRepository,
            PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.userRoleRepository = userRoleRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public CustomerIdentityResponse register(RegisterCustomerRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Registration request is required");
        }

        String email = EmailNormalizer.normalize(request.email());
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("Email is required");
        }
        if (!EMAIL_PATTERN.matcher(email).matches()) {
            throw new IllegalArgumentException("Email must be a valid email address");
        }

        String firstName = request.firstName() == null ? null : request.firstName().trim();
        if (firstName == null || firstName.isBlank()) {
            throw new IllegalArgumentException("First name is required");
        }
        String lastName = blankToNull(request.lastName() == null ? null : request.lastName().trim());

        if (userRepository.findByEmailIgnoreCase(email).isPresent()) {
            throw duplicateEmail();
        }

        Role customerRole = roleRepository.findByCode(RoleCode.CUSTOMER)
                .orElseThrow(() -> new IllegalStateException("CUSTOMER role is not seeded"));

        User user = new User(email, null, firstName, lastName);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        // status defaults to ACTIVE on the entity; never accept client status

        try {
            user = userRepository.saveAndFlush(user);
            userRoleRepository.saveAndFlush(new UserRole(user, customerRole));
        } catch (DataIntegrityViolationException exception) {
            throw duplicateEmail();
        }

        return new CustomerIdentityResponse(
                user.getId(),
                user.getFirstName(),
                user.getLastName(),
                user.getEmail(),
                List.of(RoleCode.CUSTOMER.name()),
                user.getStatus() == null ? UserStatus.ACTIVE : user.getStatus());
    }

    private static ApplicationConflictException duplicateEmail() {
        return new ApplicationConflictException("An account with this email already exists.");
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }
}
