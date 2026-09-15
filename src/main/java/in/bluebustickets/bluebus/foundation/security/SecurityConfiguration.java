package in.bluebustickets.bluebus.foundation.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

import in.bluebustickets.bluebus.foundation.api.error.ApiErrorResponseWriter;

@Configuration
@EnableWebSecurity
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
            JwtAuthenticationConverter jwtAuthenticationConverter)
            throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // CORS is intentionally deferred until Angular has a known, strict origin allow-list.
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
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)))
                .build();
    }
}
