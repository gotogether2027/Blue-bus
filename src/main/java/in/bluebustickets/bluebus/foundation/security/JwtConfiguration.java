package in.bluebustickets.bluebus.foundation.security;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class JwtConfiguration {

    public static final String ROLES_CLAIM = "roles";
    public static final String EMAIL_CLAIM = "email";

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    JwtEncoder jwtEncoder(JwtProperties properties) {
        SecretKey key = secretKey(properties);
        return new NimbusJwtEncoder(new ImmutableSecret<>(key));
    }

    @Bean
    JwtDecoder jwtDecoder(JwtProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(secretKey(properties))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.issuer()));
        return decoder;
    }

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            List<String> roles = jwt.getClaimAsStringList(ROLES_CLAIM);
            if (roles == null || roles.isEmpty()) {
                return List.of();
            }
            return roles.stream()
                    .filter(role -> role != null && !role.isBlank())
                    .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role.trim()))
                    .toList();
        });
        return converter;
    }

    @Bean
    JwtTokenService jwtTokenService(JwtEncoder jwtEncoder, JwtProperties properties, Clock clock) {
        return new JwtTokenService(jwtEncoder, properties, clock);
    }

    private static SecretKey secretKey(JwtProperties properties) {
        return new SecretKeySpec(
                properties.secret().getBytes(StandardCharsets.UTF_8),
                "HmacSHA256");
    }

    /**
     * Issues short-lived HS256 access tokens for authenticated users.
     */
    public static final class JwtTokenService {

        private final JwtEncoder jwtEncoder;
        private final JwtProperties properties;
        private final Clock clock;

        JwtTokenService(JwtEncoder jwtEncoder, JwtProperties properties, Clock clock) {
            this.jwtEncoder = jwtEncoder;
            this.properties = properties;
            this.clock = clock;
        }

        public IssuedAccessToken issueAccessToken(
                UUID userId,
                String email,
                Collection<String> roleCodes) {
            Instant issuedAt = clock.instant();
            Instant expiresAt = issuedAt.plusSeconds(properties.accessTokenTtlSeconds());
            List<String> roles = roleCodes == null
                    ? List.of()
                    : roleCodes.stream().distinct().sorted().collect(Collectors.toList());

            JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                    .issuer(properties.issuer())
                    .issuedAt(issuedAt)
                    .expiresAt(expiresAt)
                    .subject(userId.toString())
                    .claim(ROLES_CLAIM, roles);
            if (email != null && !email.isBlank()) {
                claims.claim(EMAIL_CLAIM, email);
            }

            JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
            String token = jwtEncoder
                    .encode(JwtEncoderParameters.from(header, claims.build()))
                    .getTokenValue();
            return new IssuedAccessToken(token, properties.accessTokenTtlSeconds(), expiresAt);
        }
    }

    public record IssuedAccessToken(String tokenValue, long expiresInSeconds, Instant expiresAt) {
    }
}
