package in.bluebustickets.bluebus.notification.application;

import in.bluebustickets.bluebus.notification.domain.Notification;
import in.bluebustickets.bluebus.notification.domain.NotificationChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Phase 1 adapter: records a local delivery reference and does not call an
 * external email/SMS/WhatsApp provider.
 */
@Component
public class LoggingNotificationProvider implements NotificationProvider {

    private static final Logger log = LoggerFactory.getLogger(LoggingNotificationProvider.class);

    private final NotificationChannel channel;

    public LoggingNotificationProvider() {
        this(NotificationChannel.EMAIL);
    }

    LoggingNotificationProvider(NotificationChannel channel) {
        this.channel = channel;
    }

    @Component
    static class SmsLoggingNotificationProvider extends LoggingNotificationProvider {
        SmsLoggingNotificationProvider() {
            super(NotificationChannel.SMS);
        }
    }

    @Component
    static class WhatsAppLoggingNotificationProvider extends LoggingNotificationProvider {
        WhatsAppLoggingNotificationProvider() {
            super(NotificationChannel.WHATSAPP);
        }
    }

    @Override
    public NotificationChannel channel() {
        return channel;
    }

    @Override
    public ProviderResult send(Notification notification) {
        String reference = "local-" + notification.getId();
        log.info(
                "Notification local delivery notificationId={} eventType={} channel={} sourceEventId={}",
                notification.getId(),
                notification.getEventType(),
                notification.getChannel(),
                notification.getSourceEventId());
        return ProviderResult.success(reference);
    }
}
