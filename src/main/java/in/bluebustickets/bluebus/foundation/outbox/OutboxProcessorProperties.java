package in.bluebustickets.bluebus.foundation.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Database outbox local processor settings (Phase 9.4B).
 * {@code published_at} means local handler completion, not RabbitMQ delivery.
 */
@ConfigurationProperties(prefix = "blue-bus.outbox.processor")
public class OutboxProcessorProperties {

    /**
     * When false, {@link OutboxProcessorScheduler} is not registered.
     * {@link OutboxProcessorService} remains callable for tests and ops.
     */
    private boolean enabled = true;

    /** Fixed delay between processor passes, in milliseconds. */
    private long pollIntervalMs = 5_000L;

    /** Max unpublished events claimed per discovery page. */
    private int batchSize = 50;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getPollIntervalMs() {
        return pollIntervalMs;
    }

    public void setPollIntervalMs(long pollIntervalMs) {
        if (pollIntervalMs < 1_000L) {
            throw new IllegalArgumentException("outbox.processor.poll-interval-ms must be >= 1000");
        }
        this.pollIntervalMs = pollIntervalMs;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("outbox.processor.batch-size must be >= 1");
        }
        this.batchSize = batchSize;
    }
}
