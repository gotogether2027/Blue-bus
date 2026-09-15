package in.bluebustickets.bluebus.booking.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Server-owned unpaid booking payment window. Clients never supply {@code paymentExpiresAt}.
 */
@ConfigurationProperties(prefix = "blue-bus.bookings.unpaid")
public class BookingUnpaidProperties {

    /** Payment deadline from booking creation, in seconds. Default 15 minutes. */
    private long ttlSeconds = 900L;

    public long getTtlSeconds() {
        return ttlSeconds;
    }

    public void setTtlSeconds(long ttlSeconds) {
        if (ttlSeconds < 1L) {
            throw new IllegalArgumentException("bookings.unpaid.ttl-seconds must be >= 1");
        }
        this.ttlSeconds = ttlSeconds;
    }
}
