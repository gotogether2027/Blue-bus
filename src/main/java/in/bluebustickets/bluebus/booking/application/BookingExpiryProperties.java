package in.bluebustickets.bluebus.booking.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Unpaid-booking expiry reaper settings. Scheduler is separate from domain transition logic.
 */
@ConfigurationProperties(prefix = "blue-bus.bookings.expiry")
public class BookingExpiryProperties {

    /**
     * When false, {@link BookingExpiryScheduler} is not registered.
     * The application service remains callable for tests and ops.
     */
    private boolean enabled = true;

    /** Fixed delay between reaper passes, in milliseconds. */
    private long reaperIntervalMs = 30_000L;

    /** Max PENDING_PAYMENT due bookings claimed per discovery page. */
    private int batchSize = 100;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getReaperIntervalMs() {
        return reaperIntervalMs;
    }

    public void setReaperIntervalMs(long reaperIntervalMs) {
        if (reaperIntervalMs < 1_000L) {
            throw new IllegalArgumentException("bookings.expiry.reaper-interval-ms must be >= 1000");
        }
        this.reaperIntervalMs = reaperIntervalMs;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("bookings.expiry.batch-size must be >= 1");
        }
        this.batchSize = batchSize;
    }
}
