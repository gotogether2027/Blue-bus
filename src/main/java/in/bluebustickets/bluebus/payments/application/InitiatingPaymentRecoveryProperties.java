package in.bluebustickets.bluebus.payments.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Durable INITIATING payment recovery worker settings. Scheduler is separate from provider HTTP.
 */
@ConfigurationProperties(prefix = "blue-bus.payments.initiating-recovery")
public class InitiatingPaymentRecoveryProperties {

    /**
     * When false, {@link InitiatingPaymentRecoveryScheduler} is not registered.
     * {@link InitiatingPaymentRecoveryService} remains callable for tests and ops.
     */
    private boolean enabled = true;

    /** Fixed delay between worker passes, in milliseconds. */
    private long pollIntervalMs = 5_000L;

    /** Max due INITIATING attempts claimed per discovery page. */
    private int batchSize = 50;

    /** Claim lease so another instance skips an in-flight order creation. */
    private long leaseMs = 45_000L;

    /**
     * Attempt must be at least this old before recovery will claim it.
     * Must exceed the Razorpay Orders read timeout so an in-flight customer request
     * is not raced by the worker.
     */
    private long staleThresholdMs = 30_000L;

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

    public long getPollIntervalMs() {
        return pollIntervalMs;
    }

    public void setPollIntervalMs(long pollIntervalMs) {
        if (pollIntervalMs < 1_000L) {
            throw new IllegalArgumentException("payments.initiating-recovery.poll-interval-ms must be >= 1000");
        }
        this.pollIntervalMs = pollIntervalMs;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("payments.initiating-recovery.batch-size must be >= 1");
        }
        this.batchSize = batchSize;
    }

    public long getLeaseMs() {
        return leaseMs;
    }

    public void setLeaseMs(long leaseMs) {
        if (leaseMs < 1_000L) {
            throw new IllegalArgumentException("payments.initiating-recovery.lease-ms must be >= 1000");
        }
        this.leaseMs = leaseMs;
    }

    public long getStaleThresholdMs() {
        return staleThresholdMs;
    }

    public void setStaleThresholdMs(long staleThresholdMs) {
        if (staleThresholdMs < 1_000L) {
            throw new IllegalArgumentException(
                    "payments.initiating-recovery.stale-threshold-ms must be >= 1000");
        }
        this.staleThresholdMs = staleThresholdMs;
    }

    public long getInitialBackoffMs() {
        return initialBackoffMs;
    }

    public void setInitialBackoffMs(long initialBackoffMs) {
        if (initialBackoffMs < 1_000L) {
            throw new IllegalArgumentException(
                    "payments.initiating-recovery.initial-backoff-ms must be >= 1000");
        }
        this.initialBackoffMs = initialBackoffMs;
    }

    public long getMaxBackoffMs() {
        return maxBackoffMs;
    }

    public void setMaxBackoffMs(long maxBackoffMs) {
        if (maxBackoffMs < 1_000L) {
            throw new IllegalArgumentException("payments.initiating-recovery.max-backoff-ms must be >= 1000");
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
