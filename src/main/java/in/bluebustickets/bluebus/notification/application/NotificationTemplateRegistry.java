package in.bluebustickets.bluebus.notification.application;

import java.util.EnumMap;
import java.util.Map;

import in.bluebustickets.bluebus.notification.domain.NotificationChannel;
import in.bluebustickets.bluebus.notification.domain.NotificationEventType;
import org.springframework.stereotype.Component;

@Component
public class NotificationTemplateRegistry {

    private final Map<NotificationEventType, NotificationTemplate> templates =
            new EnumMap<>(NotificationEventType.class);

    public NotificationTemplateRegistry() {
        register(NotificationEventType.BOOKING_CONFIRMED,
                "Your Blue Bus booking is confirmed",
                "Booking {bookingReference} is confirmed. Booking ID: {bookingId}.");
        register(NotificationEventType.TICKET_ISSUED,
                "Your Blue Bus e-ticket is ready",
                "Ticket {ticketNumber} is ready for booking {bookingId}.");
        register(NotificationEventType.BOOKING_CANCELLED,
                "Your Blue Bus booking was cancelled",
                "Booking {bookingReference} was cancelled. Booking ID: {bookingId}.");
        register(NotificationEventType.REFUND_REQUESTED,
                "Blue Bus refund requested",
                "A refund has been requested for booking {bookingId}.");
        register(NotificationEventType.REFUND_SUCCEEDED,
                "Blue Bus refund completed",
                "A refund for booking {bookingId} has been completed.");
        register(NotificationEventType.REFUND_FAILED,
                "Blue Bus refund failed",
                "A refund for booking {bookingId} could not be completed. We will retry if possible.");
        register(NotificationEventType.PAYMENT_FAILED,
                "Blue Bus payment failed",
                "Payment for booking {bookingId} did not succeed.");
        register(NotificationEventType.TRIP_CANCELLED,
                "Your Blue Bus trip was cancelled",
                "The trip for booking {bookingReference} was cancelled. Booking ID: {bookingId}.");
    }

    public NotificationTemplate require(NotificationEventType eventType, NotificationChannel channel) {
        NotificationTemplate base = templates.get(eventType);
        if (base == null) {
            throw new IllegalArgumentException("No notification template for " + eventType);
        }
        return new NotificationTemplate(
                base.eventType(),
                channel,
                base.code(),
                base.subject(),
                base.body());
    }

    private void register(NotificationEventType eventType, String subject, String body) {
        templates.put(eventType, new NotificationTemplate(eventType, NotificationChannel.EMAIL, eventType.name(), subject, body));
    }
}
