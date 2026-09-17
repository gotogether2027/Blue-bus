package in.bluebustickets.bluebus.payments.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Durable refund retry worker settings. Scheduler is separate from provider HTTP.
 */
@ConfigurationProperties(prefix = "blue-bus.payments.refund-retry")
public class RefundRetryProperties {

    /**
     * When false, {@link RefundRetryScheduler} is not registered.
     * {@link RefundRetryService} remains callable for tests and ops.
     */
    private boolean enabled = true;

    /**
     * Latency optimization after confirmed cancellation commit.
     * Not required for correctness; the retry worker recovers REQUESTED rows.
     */
    private boolean afterCommitEnabled = true;

    /** Fixed delay between worker passes, in milliseconds. */
    private long pollIntervalMs = 5_000L;

    /** Max due refunds claimed per discovery page. */
    private int batchSize = 50;

    /** Claim lease so another instance skips an in-flight refund. */
    private long leaseMs = 45_000L;

    /** First backoff after a failed provider attempt. */
    private long initialBackoffMs = 5_000L;

    /** Maximum delay between retries. */
    private long maxBackoffMs = 900_000L;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isAfterCommitEnabled() {
        return afterCommitEnabled;
    }

    public void setAfterCommitEnabled(boolean afterCommitEnabled) {
        this.afterCommitEnabled = afterCommitEnabled;
    }

    public long getPollIntervalMs() {
        return pollIntervalMs;
    }

    public void setPollIntervalMs(long pollIntervalMs) {
        if (pollIntervalMs < 1_000L) {
            throw new IllegalArgumentException("payments.refund-retry.poll-interval-ms must be >= 1000");
        }
        this.pollIntervalMs = pollIntervalMs;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("payments.refund-retry.batch-size must be >= 1");
        }
        this.batchSize = batchSize;
    }

    public long getLeaseMs() {
        return leaseMs;
    }

    public void setLeaseMs(long leaseMs) {
        if (leaseMs < 1_000L) {
            throw new IllegalArgumentException("payments.refund-retry.lease-ms must be >= 1000");
        }
        this.leaseMs = leaseMs;
    }

    public long getInitialBackoffMs() {
        return initialBackoffMs;
    }

    public void setInitialBackoffMs(long initialBackoffMs) {
        if (initialBackoffMs < 1_000L) {
            throw new IllegalArgumentException("payments.refund-retry.initial-backoff-ms must be >= 1000");
        }
        this.initialBackoffMs = initialBackoffMs;
    }

    public long getMaxBackoffMs() {
        return maxBackoffMs;
    }

    public void setMaxBackoffMs(long maxBackoffMs) {
        if (maxBackoffMs < 1_000L) {
            throw new IllegalArgumentException("payments.refund-retry.max-backoff-ms must be >= 1000");
        }
        this.maxBackoffMs = maxBackoffMs;
    }

    public long backoffDelayMs(int attemptCount) {
        int safeAttempts = Math.max(attemptCount, 1);
        int shift = Math.min(safeAttempts - 1, 20);
        long delay = initialBackoffMs;
        for (int i = 0; i < shift; i++) {
            if (delay > maxBackoffMs / 2L) {
                return maxBackoffMs;
            }
            delay *= 2L;
        }
        return Math.min(delay, maxBackoffMs);
    }
}
