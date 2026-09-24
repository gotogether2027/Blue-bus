package in.bluebustickets.bluebus.notification.api.dto;

import java.time.Instant;
import java.util.UUID;

public record NotificationResponse(
        UUID id,
        String eventType,
        String channel,
        String templateCode,
        String subject,
        String body,
        String status,
        Instant createdAt,
        Instant sentAt) {
}
