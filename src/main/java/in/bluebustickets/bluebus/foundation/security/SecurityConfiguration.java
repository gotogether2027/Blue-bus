package in.bluebustickets.bluebus.foundation.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

import in.bluebustickets.bluebus.foundation.api.error.ApiErrorResponseWriter;

@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfiguration {

    /**
     * Prevents Spring Security from creating a generated in-memory user. Credential lookup is
     * deliberately deferred until the Identity & Access phase.
     */
    @Bean
    UserDetailsService deferredUserDetailsService() {
        return username -> {
            throw new UsernameNotFoundException("Authentication is not available yet.");
        };
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ApiErrorResponseWriter apiErrorResponseWriter)
            throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // CORS is intentionally deferred until Angular has a known, strict origin allow-list.
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) ->
                                apiErrorResponseWriter.write(request, response, HttpStatus.UNAUTHORIZED))
                        .accessDeniedHandler((request, response, exception) ->
                                apiErrorResponseWriter.write(request, response, HttpStatus.FORBIDDEN)))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/api/v1/health", "/error").permitAll()
                        .anyRequest().authenticated())
                .build();
    }
}
