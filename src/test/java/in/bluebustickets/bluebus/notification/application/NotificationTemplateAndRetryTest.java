package in.bluebustickets.bluebus.notification.application;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import in.bluebustickets.bluebus.foundation.outbox.OutboxEvent;
import in.bluebustickets.bluebus.foundation.outbox.rabbit.RabbitMqProperties;
import in.bluebustickets.bluebus.notification.domain.Notification;
import in.bluebustickets.bluebus.notification.domain.NotificationChannel;
import in.bluebustickets.bluebus.notification.domain.NotificationEventType;
import in.bluebustickets.bluebus.notification.domain.NotificationPreference;
import in.bluebustickets.bluebus.notification.domain.NotificationStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationTemplateAndRetryTest {

    @Test
    void templatesUseStableEventTypeCodes() {
        NotificationTemplateRegistry registry = new NotificationTemplateRegistry();
        for (NotificationEventType type : NotificationEventType.values()) {
            NotificationTemplate template = registry.require(type, NotificationChannel.EMAIL);
            assertThat(template.code()).isEqualTo(type.name());
            assertThat(template.renderSubject(Map.of("bookingReference", "BB1")))
                    .doesNotContain("{bookingReference}");
        }
    }

    @Test
    void deliveryBackoffDoublesUntilCap() {
        NotificationProperties.Delivery delivery = new NotificationProperties.Delivery();
        delivery.setInitialBackoffMs(5_000L);
        delivery.setMaxBackoffMs(40_000L);
        assertThat(delivery.backoffDelayMs(1)).isEqualTo(5_000L);
        assertThat(delivery.backoffDelayMs(2)).isEqualTo(10_000L);
        assertThat(delivery.backoffDelayMs(3)).isEqualTo(20_000L);
        assertThat(delivery.backoffDelayMs(4)).isEqualTo(40_000L);
        assertThat(delivery.backoffDelayMs(8)).isEqualTo(40_000L);
    }

    @Test
    void loggingProviderDoesNotCallExternalHttp() {
        LoggingNotificationProvider provider = new LoggingNotificationProvider();
        Notification notification = new Notification(
                UUID.randomUUID(),
                UUID.randomUUID(),
                NotificationEventType.BOOKING_CONFIRMED,
                NotificationChannel.EMAIL,
                "BOOKING_CONFIRMED",
                "BOOKING_CONFIRMED:" + UUID.randomUUID(),
                "subject",
                "body",
                "{}",
                Instant.parse("2026-09-24T00:00:00Z"));
        NotificationProvider.ProviderResult result = provider.send(notification);
        assertThat(result.outcome()).isEqualTo(NotificationProvider.ProviderResult.Outcome.SUCCESS);
        assertThat(result.providerMessageId()).isEqualTo("local-" + notification.getId());
    }

    @Test
    void defaultPreferencesEnableEmailOnly() {
        NotificationPreference preference = NotificationPreference.defaults(UUID.randomUUID());
        assertThat(preference.isEmailEnabled()).isTrue();
        assertThat(preference.isSmsEnabled()).isFalse();
        assertThat(preference.isWhatsappEnabled()).isFalse();
        assertThat(preference.isChannelEnabled(NotificationChannel.EMAIL)).isTrue();
        assertThat(preference.isChannelEnabled(NotificationChannel.SMS)).isFalse();
    }

    @Test
    void retryableFailureSchedulesBackoffAndNonRetryableFails() {
        Notification notification = new Notification(
                UUID.randomUUID(),
                UUID.randomUUID(),
                NotificationEventType.PAYMENT_FAILED,
                NotificationChannel.EMAIL,
                "PAYMENT_FAILED",
                "PAYMENT_FAILED:" + UUID.randomUUID(),
                "subject",
                "body",
                "{}",
                Instant.parse("2026-09-24T00:00:00Z"));
        Instant now = Instant.parse("2026-09-24T01:00:00Z");
        notification.claimForDelivery(now.plusSeconds(45));
        notification.scheduleRetry(now.plusSeconds(5), "PROVIDER_TIMEOUT", "retry later");
        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(notification.getNextRetryAt()).isEqualTo(now.plusSeconds(5));
        assertThat(notification.getFailureCode()).isEqualTo("PROVIDER_TIMEOUT");

        notification.claimForDelivery(now.plusSeconds(90));
        notification.markFailed("INVALID_RECIPIENT", "do not retry");
        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(notification.getNextRetryAt()).isNull();
        assertThat(notification.getFailureCode()).isEqualTo("INVALID_RECIPIENT");
    }

    @Test
    void notificationConsumerAuthorityMatchesTicketStrategy() {
        RabbitMqProperties properties = new RabbitMqProperties();
        assertThat(properties.isNotificationConsumerAuthoritative()).isFalse();

        properties.setEnabled(true);
        properties.setNotificationConsumerEnabled(false);
        assertThat(properties.isNotificationConsumerAuthoritative()).isFalse();

        properties.setNotificationConsumerEnabled(true);
        assertThat(properties.isNotificationConsumerAuthoritative()).isTrue();

        properties.setEnabled(false);
        assertThat(properties.isNotificationConsumerAuthoritative()).isFalse();
    }

    @Test
    void routingKeysStayOnExistingTopicConvention() {
        RabbitMqProperties properties = new RabbitMqProperties();
        assertThat(properties.routingKeyFor("BOOKING_CONFIRMED")).isEqualTo("booking.confirmed");
        assertThat(properties.routingKeyFor("TICKET_ISSUED")).isEqualTo("ticket.issued");
        assertThat(properties.routingKeyFor("REFUND_REQUESTED")).isEqualTo("refund.requested");
        assertThat(properties.routingKeyFor("PAYMENT_FAILED")).isEqualTo("payment.failed");
    }

    @Test
    void outboxViewKeepsPublishedEventIdentity() {
        UUID eventId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        OutboxEvent event = new OutboxEvent(
                eventId,
                "BOOKING_CONFIRMED",
                "BOOKING",
                UUID.randomUUID(),
                "{}",
                Instant.parse("2026-09-24T00:00:00Z"),
                null,
                null);
        assertThat(event.getId()).isEqualTo(eventId);
        assertThat(NotificationEventType.isPublishableOutboxType(event.getEventType())).isTrue();
        assertThat(NotificationEventType.isPublishableOutboxType("TRIP_CANCELLED")).isFalse();
    }
}
