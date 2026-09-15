package in.bluebustickets.bluebus.scheduling.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Server-owned create-hold settings. Clients never supply expiresAt.
 */
@ConfigurationProperties(prefix = "blue-bus.seat-holds.create")
public class SeatHoldCreateProperties {

    /** Hold lifetime from creation time, in seconds. */
    private long ttlSeconds = 600L;

    public long getTtlSeconds() {
        return ttlSeconds;
    }

    public void setTtlSeconds(long ttlSeconds) {
        if (ttlSeconds < 30L) {
            throw new IllegalArgumentException("seat-holds.create.ttl-seconds must be >= 30");
        }
        this.ttlSeconds = ttlSeconds;
    }
}
