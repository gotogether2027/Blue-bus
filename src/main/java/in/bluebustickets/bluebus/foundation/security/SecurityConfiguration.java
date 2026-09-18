package in.bluebustickets.bluebus.foundation.security;

import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import in.bluebustickets.bluebus.foundation.api.error.ApiErrorResponseWriter;
import in.bluebustickets.bluebus.foundation.ratelimit.RateLimitFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties(CorsProperties.class)
public class SecurityConfiguration {

    /**
     * Prevents Spring Security from creating a generated in-memory user. Credential verification
     * for login goes through {@code AuthenticationService}, not form login.
     */
    @Bean
    UserDetailsService deferredUserDetailsService() {
        return username -> {
            throw new UsernameNotFoundException("Use POST /api/v1/auth/login for authentication.");
        };
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            ApiErrorResponseWriter apiErrorResponseWriter,
            JwtAuthenticationConverter jwtAuthenticationConverter,
            ObjectProvider<ActiveUserAuthenticationFilter> activeUserAuthenticationFilter,
            ObjectProvider<RateLimitFilter> rateLimitFilter)
            throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) ->
                                apiErrorResponseWriter.write(request, response, HttpStatus.UNAUTHORIZED))
                        .accessDeniedHandler((request, response, exception) ->
                                apiErrorResponseWriter.write(request, response, HttpStatus.FORBIDDEN)))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/api/v1/health", "/error").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/register").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/refresh").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/logout").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/search/trips").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/locations").permitAll()
                        .requestMatchers("/api/v1/trips/*/seat-availability").permitAll()
                        // Public hold APIs: optional JWT on create persists seat_holds.user_id for booking auth.
                        .requestMatchers(HttpMethod.POST, "/api/v1/trips/*/holds").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/holds/*").permitAll()
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/holds/*").permitAll()
                        // Provider identity is established by the registered adapter's signature verifier.
                        .requestMatchers(HttpMethod.POST, "/api/v1/payments/webhooks/*").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .authenticationEntryPoint((request, response, exception) ->
                                apiErrorResponseWriter.write(request, response, HttpStatus.UNAUTHORIZED))
                        .accessDeniedHandler((request, response, exception) ->
                                apiErrorResponseWriter.write(request, response, HttpStatus.FORBIDDEN))
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)));
        RateLimitFilter limiter = rateLimitFilter.getIfAvailable();
        if (limiter != null) {
            http.addFilterBefore(limiter, BearerTokenAuthenticationFilter.class);
        }
        ActiveUserAuthenticationFilter activeUserFilter = activeUserAuthenticationFilter.getIfAvailable();
        if (activeUserFilter != null) {
            http.addFilterAfter(activeUserFilter, BearerTokenAuthenticationFilter.class);
        }
        return http.build();
    }

    /**
     * Empty allow-list returns no CORS configuration so browsers only talk same-origin.
     * Credentials are never enabled, and wildcards are rejected in {@link CorsProperties}.
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource(CorsProperties properties) {
        List<String> origins = properties.getAllowedOrigins();
        if (origins.isEmpty()) {
            return request -> null;
        }
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(origins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key"));
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }
}
