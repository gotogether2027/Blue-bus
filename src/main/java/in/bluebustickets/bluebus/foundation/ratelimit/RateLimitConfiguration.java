package in.bluebustickets.bluebus.foundation.ratelimit;

import java.time.Clock;

import in.bluebustickets.bluebus.foundation.api.error.ApiErrorResponseWriter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
@ConditionalOnProperty(prefix = "blue-bus.rate-limit", name = "enabled", matchIfMissing = true)
public class RateLimitConfiguration {

    @Bean
    InMemoryRateLimiter inMemoryRateLimiter(RateLimitProperties properties, Clock clock) {
        return new InMemoryRateLimiter(properties, clock);
    }

    @Bean
    RateLimitFilter rateLimitFilter(
            InMemoryRateLimiter inMemoryRateLimiter, ApiErrorResponseWriter apiErrorResponseWriter) {
        return new RateLimitFilter(inMemoryRateLimiter, apiErrorResponseWriter);
    }

    /**
     * Keep the limiter on the security chain only. Servlet-container auto-registration would
     * otherwise run the same {@code Filter} bean a second time.
     */
    @Bean
    FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(RateLimitFilter rateLimitFilter) {
        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>(rateLimitFilter);
        registration.setEnabled(false);
        return registration;
    }
}
