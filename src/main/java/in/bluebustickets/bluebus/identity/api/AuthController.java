package in.bluebustickets.bluebus.identity.api;

import in.bluebustickets.bluebus.identity.api.dto.CustomerIdentityResponse;
import in.bluebustickets.bluebus.identity.api.dto.LoginRequest;
import in.bluebustickets.bluebus.identity.api.dto.LoginResponse;
import in.bluebustickets.bluebus.identity.api.dto.RefreshTokenRequest;
import in.bluebustickets.bluebus.identity.api.dto.RegisterCustomerRequest;
import in.bluebustickets.bluebus.identity.application.AuthenticationService;
import in.bluebustickets.bluebus.identity.application.CurrentUserService;
import in.bluebustickets.bluebus.identity.application.CustomerRegistrationService;
import in.bluebustickets.bluebus.identity.application.RefreshTokenService;
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
 * Registration does not issue tokens; clients use {@code POST /login} afterwards.
 * Access JWTs remain valid until expiry after logout; only the refresh family is revoked.
 */
@RestController
@RequestMapping("/api/v1/auth")
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class AuthController {

    private final AuthenticationService authenticationService;
    private final CustomerRegistrationService customerRegistrationService;
    private final CurrentUserService currentUserService;
    private final RefreshTokenService refreshTokenService;

    public AuthController(
            AuthenticationService authenticationService,
            CustomerRegistrationService customerRegistrationService,
            CurrentUserService currentUserService,
            RefreshTokenService refreshTokenService) {
        this.authenticationService = authenticationService;
        this.customerRegistrationService = customerRegistrationService;
        this.currentUserService = currentUserService;
        this.refreshTokenService = refreshTokenService;
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

    @PostMapping("/refresh")
    @ResponseStatus(HttpStatus.OK)
    public LoginResponse refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return refreshTokenService.refresh(request.refreshToken());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody RefreshTokenRequest request) {
        refreshTokenService.logout(request.refreshToken());
    }

    @GetMapping("/me")
    @ResponseStatus(HttpStatus.OK)
    public CustomerIdentityResponse me(Authentication authentication) {
        return currentUserService.currentUser(authentication);
    }
}
