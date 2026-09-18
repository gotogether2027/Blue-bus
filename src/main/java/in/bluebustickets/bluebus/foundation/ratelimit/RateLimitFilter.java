package in.bluebustickets.bluebus.foundation.ratelimit;

import java.io.IOException;
import java.util.List;

import in.bluebustickets.bluebus.foundation.api.error.ApiErrorResponseWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Applies in-process IP rate limits to selected public endpoints before they execute.
 * Health, authenticated business APIs, and static resources are not matched.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    static final String RETRY_AFTER = "Retry-After";
    static final String TOO_MANY_REQUESTS_MESSAGE = "Too many requests. Try again later.";

    private static final Logger LOGGER = LoggerFactory.getLogger(RateLimitFilter.class);

    private static final List<ProtectedRoute> ROUTES = List.of(
            new ProtectedRoute(
                    RateLimitCategory.AUTH,
                    new AntPathRequestMatcher("/api/v1/auth/login", HttpMethod.POST.name())),
            new ProtectedRoute(
                    RateLimitCategory.AUTH,
                    new AntPathRequestMatcher("/api/v1/auth/register", HttpMethod.POST.name())),
            new ProtectedRoute(
                    RateLimitCategory.AUTH,
                    new AntPathRequestMatcher("/api/v1/auth/refresh", HttpMethod.POST.name())),
            new ProtectedRoute(
                    RateLimitCategory.PUBLIC_API,
                    new AntPathRequestMatcher("/api/v1/search/trips", HttpMethod.GET.name())),
            new ProtectedRoute(
                    RateLimitCategory.PUBLIC_API,
                    new AntPathRequestMatcher("/api/v1/locations", HttpMethod.GET.name())),
            new ProtectedRoute(
                    RateLimitCategory.PUBLIC_API,
                    new AntPathRequestMatcher("/api/v1/trips/*/seat-availability", HttpMethod.GET.name())),
            new ProtectedRoute(
                    RateLimitCategory.HOLDS,
                    new AntPathRequestMatcher("/api/v1/trips/*/holds", HttpMethod.POST.name())),
            new ProtectedRoute(
                    RateLimitCategory.HOLDS,
                    new AntPathRequestMatcher("/api/v1/holds/*", HttpMethod.DELETE.name())),
            new ProtectedRoute(
                    RateLimitCategory.WEBHOOK,
                    new AntPathRequestMatcher("/api/v1/payments/webhooks/*", HttpMethod.POST.name())));

    private final InMemoryRateLimiter rateLimiter;
    private final ApiErrorResponseWriter apiErrorResponseWriter;

    public RateLimitFilter(InMemoryRateLimiter rateLimiter, ApiErrorResponseWriter apiErrorResponseWriter) {
        this.rateLimiter = rateLimiter;
        this.apiErrorResponseWriter = apiErrorResponseWriter;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        RateLimitCategory category = matchCategory(request);
        if (category == null) {
            filterChain.doFilter(request, response);
            return;
        }
        String clientKey = ClientIpResolver.resolve(request);
        InMemoryRateLimiter.Decision decision = rateLimiter.allow(category, clientKey);
        if (decision.allowed()) {
            filterChain.doFilter(request, response);
            return;
        }
        LOGGER.debug("Rate limit exceeded for category={}", category);
        response.setHeader(RETRY_AFTER, Integer.toString(decision.retryAfterSeconds()));
        apiErrorResponseWriter.write(
                request, response, HttpStatus.TOO_MANY_REQUESTS, TOO_MANY_REQUESTS_MESSAGE);
    }

    private static RateLimitCategory matchCategory(HttpServletRequest request) {
        for (ProtectedRoute route : ROUTES) {
            if (route.matcher.matches(request)) {
                return route.category;
            }
        }
        return null;
    }

    private record ProtectedRoute(RateLimitCategory category, RequestMatcher matcher) {
    }
}
