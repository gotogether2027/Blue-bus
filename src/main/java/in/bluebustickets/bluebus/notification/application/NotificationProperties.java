package in.bluebustickets.bluebus.notification.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "blue-bus.notifications")
public class NotificationProperties {

    private final Processor processor = new Processor();
    private final Delivery delivery = new Delivery();

    public Processor getProcessor() {
        return processor;
    }

    public Delivery getDelivery() {
        return delivery;
    }

    public static class Processor {
        private boolean enabled = true;
        private long pollIntervalMs = 5_000L;
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
                throw new IllegalArgumentException(
                        "blue-bus.notifications.processor.poll-interval-ms must be >= 1000");
            }
            this.pollIntervalMs = pollIntervalMs;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            if (batchSize < 1) {
                throw new IllegalArgumentException(
                        "blue-bus.notifications.processor.batch-size must be >= 1");
            }
            this.batchSize = batchSize;
        }
    }

    public static class Delivery {
        private boolean enabled = true;
        private long pollIntervalMs = 5_000L;
        private int batchSize = 50;
        private long leaseMs = 45_000L;
        private long initialBackoffMs = 5_000L;
        private long maxBackoffMs = 900_000L;
        private int maxAttempts = 8;

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
                throw new IllegalArgumentException(
                        "blue-bus.notifications.delivery.poll-interval-ms must be >= 1000");
            }
            this.pollIntervalMs = pollIntervalMs;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            if (batchSize < 1) {
                throw new IllegalArgumentException(
                        "blue-bus.notifications.delivery.batch-size must be >= 1");
            }
            this.batchSize = batchSize;
        }

        public long getLeaseMs() {
            return leaseMs;
        }

        public void setLeaseMs(long leaseMs) {
            if (leaseMs < 1_000L) {
                throw new IllegalArgumentException(
                        "blue-bus.notifications.delivery.lease-ms must be >= 1000");
            }
            this.leaseMs = leaseMs;
        }

        public long getInitialBackoffMs() {
            return initialBackoffMs;
        }

        public void setInitialBackoffMs(long initialBackoffMs) {
            if (initialBackoffMs < 1_000L) {
                throw new IllegalArgumentException(
                        "blue-bus.notifications.delivery.initial-backoff-ms must be >= 1000");
            }
            this.initialBackoffMs = initialBackoffMs;
        }

        public long getMaxBackoffMs() {
            return maxBackoffMs;
        }

        public void setMaxBackoffMs(long maxBackoffMs) {
            if (maxBackoffMs < 1_000L) {
                throw new IllegalArgumentException(
                        "blue-bus.notifications.delivery.max-backoff-ms must be >= 1000");
            }
            this.maxBackoffMs = maxBackoffMs;
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            if (maxAttempts < 1) {
                throw new IllegalArgumentException(
                        "blue-bus.notifications.delivery.max-attempts must be >= 1");
            }
            this.maxAttempts = maxAttempts;
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
}
