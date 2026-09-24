package in.bluebustickets.bluebus.notification.domain;

import java.util.Set;

/**
 * Customer-facing notification types. Source outbox types are reused where they
 * already exist. {@link #TRIP_CANCELLED} is derived from {@code BOOKING_CANCELLED}
 * when the cancellation policy is a trip cancellation.
 */
public enum NotificationEventType {
    BOOKING_CONFIRMED,
    TICKET_ISSUED,
    BOOKING_CANCELLED,
    REFUND_REQUESTED,
    REFUND_SUCCEEDED,
    REFUND_FAILED,
    PAYMENT_FAILED,
    TRIP_CANCELLED;

    public static final Set<String> SOURCE_OUTBOX_TYPES = Set.of(
            BOOKING_CONFIRMED.name(),
            TICKET_ISSUED.name(),
            BOOKING_CANCELLED.name(),
            REFUND_REQUESTED.name(),
            REFUND_SUCCEEDED.name(),
            REFUND_FAILED.name(),
            PAYMENT_FAILED.name());

    public static boolean isPublishableOutboxType(String eventType) {
        return eventType != null && SOURCE_OUTBOX_TYPES.contains(eventType);
    }
}
