package in.bluebustickets.bluebus.foundation.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * JWT access-token settings. Secret and issuer come from environment/config — never hardcode
 * production secrets in source.
 */
@ConfigurationProperties(prefix = "blue-bus.security.jwt")
public record JwtProperties(
        String issuer,
        String secret,
        @DefaultValue("3600") long accessTokenTtlSeconds) {

    public JwtProperties {
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalArgumentException("blue-bus.security.jwt.issuer is required");
        }
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("blue-bus.security.jwt.secret is required");
        }
        if (secret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException(
                    "blue-bus.security.jwt.secret must be at least 32 bytes for HS256");
        }
        if (accessTokenTtlSeconds < 60L) {
            throw new IllegalArgumentException(
                    "blue-bus.security.jwt.access-token-ttl-seconds must be >= 60");
        }
    }
}
