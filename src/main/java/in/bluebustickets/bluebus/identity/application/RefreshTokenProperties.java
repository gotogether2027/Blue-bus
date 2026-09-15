package in.bluebustickets.bluebus.identity.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "blue-bus.security.refresh")
public record RefreshTokenProperties(
        @DefaultValue("1209600") long ttlSeconds,
        /**
         * When a just-rotated predecessor is presented again while its replacement is still the
         * active family token, treat it as a concurrent refresh collision (HTTP 401, no family
         * revoke) if the rotation happened within this many seconds. Later replay still revokes
         * the family. Keep short — not a long-lived grace period.
         */
        @DefaultValue("5") long concurrentReuseGraceSeconds) {

    public RefreshTokenProperties {
        if (ttlSeconds < 3600L) {
            throw new IllegalArgumentException(
                    "blue-bus.security.refresh.ttl-seconds must be >= 3600");
        }
        if (concurrentReuseGraceSeconds < 1L || concurrentReuseGraceSeconds > 30L) {
            throw new IllegalArgumentException(
                    "blue-bus.security.refresh.concurrent-reuse-grace-seconds must be between 1 and 30");
        }
    }
}
