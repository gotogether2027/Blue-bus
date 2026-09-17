package in.bluebustickets.bluebus.foundation.security;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import in.bluebustickets.bluebus.identity.application.AuthorizationService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rejects otherwise-valid access JWTs when the user's database status is no longer ACTIVE.
 * Public endpoints stay reachable without this check so optional JWTs cannot break them.
 */
@Component
@ConditionalOnBean(AuthorizationService.class)
public class ActiveUserAuthenticationFilter extends OncePerRequestFilter {

    private static final RequestMatcher PUBLIC_ENDPOINTS = new OrRequestMatcher(List.of(
            new AntPathRequestMatcher("/api/v1/health"),
            new AntPathRequestMatcher("/error"),
            new AntPathRequestMatcher("/api/v1/auth/login", HttpMethod.POST.name()),
            new AntPathRequestMatcher("/api/v1/auth/register", HttpMethod.POST.name()),
            new AntPathRequestMatcher("/api/v1/auth/refresh", HttpMethod.POST.name()),
            new AntPathRequestMatcher("/api/v1/auth/logout", HttpMethod.POST.name()),
            new AntPathRequestMatcher("/api/v1/search/trips", HttpMethod.GET.name()),
            new AntPathRequestMatcher("/api/v1/locations", HttpMethod.GET.name()),
            new AntPathRequestMatcher("/api/v1/trips/*/seat-availability"),
            new AntPathRequestMatcher("/api/v1/trips/*/holds", HttpMethod.POST.name()),
            new AntPathRequestMatcher("/api/v1/holds/*", HttpMethod.GET.name()),
            new AntPathRequestMatcher("/api/v1/holds/*", HttpMethod.DELETE.name()),
            new AntPathRequestMatcher("/api/v1/payments/webhooks/*", HttpMethod.POST.name())));

    private final AuthorizationService authorizationService;

    public ActiveUserAuthenticationFilter(AuthorizationService authorizationService) {
        this.authorizationService = authorizationService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return PUBLIC_ENDPOINTS.matches(request);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken)
                && authentication.getPrincipal() instanceof Jwt jwt) {
            UUID userId;
            try {
                userId = UUID.fromString(jwt.getSubject());
            } catch (IllegalArgumentException | NullPointerException exception) {
                throw new org.springframework.security.authentication.BadCredentialsException("Invalid credentials.");
            }
            authorizationService.requireActiveUser(userId);
        }
        filterChain.doFilter(request, response);
    }
}
