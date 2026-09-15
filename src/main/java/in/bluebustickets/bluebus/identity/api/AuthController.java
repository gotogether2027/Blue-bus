package in.bluebustickets.bluebus.identity.api;

import in.bluebustickets.bluebus.identity.api.dto.CustomerIdentityResponse;
import in.bluebustickets.bluebus.identity.api.dto.LoginRequest;
import in.bluebustickets.bluebus.identity.api.dto.LoginResponse;
import in.bluebustickets.bluebus.identity.api.dto.RegisterCustomerRequest;
import in.bluebustickets.bluebus.identity.application.AuthenticationService;
import in.bluebustickets.bluebus.identity.application.CurrentUserService;
import in.bluebustickets.bluebus.identity.application.CustomerRegistrationService;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authentication and customer identity endpoints.
 * Registration does not issue a JWT; clients use {@code POST /login} afterwards.
 */
@RestController
@RequestMapping("/api/v1/auth")
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class AuthController {

    private final AuthenticationService authenticationService;
    private final CustomerRegistrationService customerRegistrationService;
    private final CurrentUserService currentUserService;

    public AuthController(
            AuthenticationService authenticationService,
            CustomerRegistrationService customerRegistrationService,
            CurrentUserService currentUserService) {
        this.authenticationService = authenticationService;
        this.customerRegistrationService = customerRegistrationService;
        this.currentUserService = currentUserService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public CustomerIdentityResponse register(@Valid @RequestBody RegisterCustomerRequest request) {
        return customerRegistrationService.register(request);
    }

    @PostMapping("/login")
    @ResponseStatus(HttpStatus.OK)
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return authenticationService.login(request);
    }

    @GetMapping("/me")
    @ResponseStatus(HttpStatus.OK)
    public CustomerIdentityResponse me(Authentication authentication) {
        return currentUserService.currentUser(authentication);
    }
}
