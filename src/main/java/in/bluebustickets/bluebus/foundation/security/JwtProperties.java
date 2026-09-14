package in.bluebustickets.bluebus.foundation.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration reserved for the JWT authentication phase. No token issuing or validation is
 * implemented in the foundation phase.
 */
@ConfigurationProperties(prefix = "blue-bus.security.jwt")
public record JwtProperties(String issuer, String secret) {
}
