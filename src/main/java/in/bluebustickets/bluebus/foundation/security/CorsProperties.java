package in.bluebustickets.bluebus.foundation.security;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Fail-closed browser CORS allow-list. Empty means no origin is granted CORS headers
 * (same-origin /api/v1 deployments do not need CORS). Wildcards are rejected.
 * Split-origin production must set {@code requireAllowedOrigins=true} and at least one origin.
 */
@ConfigurationProperties(prefix = "blue-bus.cors")
public class CorsProperties {

    private List<String> allowedOrigins = new ArrayList<>();

    /**
     * When true, startup fails unless {@code allowed-origins} contains at least one origin.
     * Default false preserves same-origin production.
     */
    private boolean requireAllowedOrigins = false;

    public List<String> getAllowedOrigins() {
        return List.copyOf(allowedOrigins);
    }

    public boolean isRequireAllowedOrigins() {
        return requireAllowedOrigins;
    }

    public void setRequireAllowedOrigins(boolean requireAllowedOrigins) {
        this.requireAllowedOrigins = requireAllowedOrigins;
    }

    public void setAllowedOrigins(List<String> allowedOrigins) {
        List<String> normalized = new ArrayList<>();
        if (allowedOrigins != null) {
            for (String origin : allowedOrigins) {
                if (origin == null || origin.isBlank()) {
                    continue;
                }
                String trimmed = origin.trim();
                if ("*".equals(trimmed) || trimmed.contains("*")) {
                    throw new IllegalArgumentException(
                            "blue-bus.cors.allowed-origins must not contain wildcards");
                }
                normalized.add(trimmed);
            }
        }
        this.allowedOrigins = List.copyOf(normalized);
    }
}
