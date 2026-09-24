package in.bluebustickets.bluebus.foundation.outbox.rabbit;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Optional RabbitMQ outbox bridge. Disabled by default so local development
 * does not require a broker. {@code published_at} is never used as the broker
 * delivery marker; that is {@code rabbit_published_at}.
 */
@ConfigurationProperties(prefix = "blue-bus.rabbitmq")
public class RabbitMqProperties {

    public static final String BOOKING_CONFIRMED_CONSUMER = "booking-confirmed-ticket";
    public static final String NOTIFICATION_CONSUMER = "notification-dispatcher";

    /** When false, no AMQP beans or connections are created. */
    private boolean enabled = false;

    /**
     * When true and {@link #enabled} is true, the BOOKING_CONFIRMED consumer
     * issues tickets and the local outbox processor skips that event type.
     */
    private boolean consumerEnabled = true;

    private String host = "";

    private String exchange = "blue-bus.events";

    private String queue = "blue-bus.booking-confirmed";

    private String routingKey = "booking.confirmed";

    /**
     * When true and {@link #enabled} is true, the notification consumer creates
     * notification rows and the local notification processor skips those events.
     * When RabbitMQ is enabled and this is false, the local processor stays
     * authoritative and the publisher may run in shadow mode.
     */
    private boolean notificationConsumerEnabled = true;

    private String notificationQueue = "blue-bus.notifications";

    private int prefetch = 10;

    private long publisherConfirmTimeoutMs = 5_000L;

    private boolean publisherReturns = true;

    private boolean declareTopologyOnStartup = true;

    private final Publisher publisher = new Publisher();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isConsumerEnabled() {
        return consumerEnabled;
    }

    public void setConsumerEnabled(boolean consumerEnabled) {
        this.consumerEnabled = consumerEnabled;
    }

    public boolean isBookingConfirmedConsumerAuthoritative() {
        return enabled && consumerEnabled;
    }

    public boolean isNotificationConsumerEnabled() {
        return notificationConsumerEnabled;
    }

    public void setNotificationConsumerEnabled(boolean notificationConsumerEnabled) {
        this.notificationConsumerEnabled = notificationConsumerEnabled;
    }

    public boolean isNotificationConsumerAuthoritative() {
        return enabled && notificationConsumerEnabled;
    }

    public String getNotificationQueue() {
        return notificationQueue;
    }

    public void setNotificationQueue(String notificationQueue) {
        this.notificationQueue = requireName(notificationQueue, "blue-bus.rabbitmq.notification-queue");
    }

    public String routingKeyFor(String eventType) {
        if (eventType == null || eventType.isBlank()) {
            return routingKey;
        }
        return switch (eventType) {
            case "BOOKING_CONFIRMED" -> routingKey;
            case "TICKET_ISSUED" -> "ticket.issued";
            case "BOOKING_CANCELLED" -> "booking.cancelled";
            case "REFUND_REQUESTED" -> "refund.requested";
            case "REFUND_SUCCEEDED" -> "refund.succeeded";
            case "REFUND_FAILED" -> "refund.failed";
            case "PAYMENT_FAILED" -> "payment.failed";
            default -> eventType.toLowerCase().replace('_', '.');
        };
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host == null ? "" : host.trim();
    }

    public String getExchange() {
        return exchange;
    }

    public void setExchange(String exchange) {
        this.exchange = requireName(exchange, "blue-bus.rabbitmq.exchange");
    }

    public String getQueue() {
        return queue;
    }

    public void setQueue(String queue) {
        this.queue = requireName(queue, "blue-bus.rabbitmq.queue");
    }

    public String getRoutingKey() {
        return routingKey;
    }

    public void setRoutingKey(String routingKey) {
        this.routingKey = requireName(routingKey, "blue-bus.rabbitmq.routing-key");
    }

    public int getPrefetch() {
        return prefetch;
    }

    public void setPrefetch(int prefetch) {
        if (prefetch < 1) {
            throw new IllegalArgumentException("blue-bus.rabbitmq.prefetch must be >= 1");
        }
        this.prefetch = prefetch;
    }

    public long getPublisherConfirmTimeoutMs() {
        return publisherConfirmTimeoutMs;
    }

    public void setPublisherConfirmTimeoutMs(long publisherConfirmTimeoutMs) {
        if (publisherConfirmTimeoutMs < 1_000L) {
            throw new IllegalArgumentException(
                    "blue-bus.rabbitmq.publisher-confirm-timeout-ms must be >= 1000");
        }
        this.publisherConfirmTimeoutMs = publisherConfirmTimeoutMs;
    }

    public boolean isPublisherReturns() {
        return publisherReturns;
    }

    public void setPublisherReturns(boolean publisherReturns) {
        this.publisherReturns = publisherReturns;
    }

    public boolean isDeclareTopologyOnStartup() {
        return declareTopologyOnStartup;
    }

    public void setDeclareTopologyOnStartup(boolean declareTopologyOnStartup) {
        this.declareTopologyOnStartup = declareTopologyOnStartup;
    }

    public Publisher getPublisher() {
        return publisher;
    }

    private static String requireName(String value, String property) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(property + " is required");
        }
        return value.trim();
    }

    public static class Publisher {

        /**
         * When false, {@link OutboxRabbitPublisherScheduler} is not registered.
         * {@link OutboxRabbitPublisherService} remains callable for tests.
         */
        private boolean enabled = true;

        private long pollIntervalMs = 5_000L;

        private int batchSize = 50;

        private long leaseMs = 45_000L;

        private long initialBackoffMs = 5_000L;

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
                throw new IllegalArgumentException(
                        "blue-bus.rabbitmq.publisher.poll-interval-ms must be >= 1000");
            }
            this.pollIntervalMs = pollIntervalMs;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            if (batchSize < 1) {
                throw new IllegalArgumentException(
                        "blue-bus.rabbitmq.publisher.batch-size must be >= 1");
            }
            this.batchSize = batchSize;
        }

        public long getLeaseMs() {
            return leaseMs;
        }

        public void setLeaseMs(long leaseMs) {
            if (leaseMs < 1_000L) {
                throw new IllegalArgumentException(
                        "blue-bus.rabbitmq.publisher.lease-ms must be >= 1000");
            }
            this.leaseMs = leaseMs;
        }

        public long getInitialBackoffMs() {
            return initialBackoffMs;
        }

        public void setInitialBackoffMs(long initialBackoffMs) {
            if (initialBackoffMs < 1_000L) {
                throw new IllegalArgumentException(
                        "blue-bus.rabbitmq.publisher.initial-backoff-ms must be >= 1000");
            }
            this.initialBackoffMs = initialBackoffMs;
        }

        public long getMaxBackoffMs() {
            return maxBackoffMs;
        }

        public void setMaxBackoffMs(long maxBackoffMs) {
            if (maxBackoffMs < 1_000L) {
                throw new IllegalArgumentException(
                        "blue-bus.rabbitmq.publisher.max-backoff-ms must be >= 1000");
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
}
