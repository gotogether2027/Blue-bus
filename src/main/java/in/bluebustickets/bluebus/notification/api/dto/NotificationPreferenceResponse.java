package in.bluebustickets.bluebus.notification.api.dto;

import java.util.UUID;

public record NotificationPreferenceResponse(
        UUID userId,
        boolean emailEnabled,
        boolean smsEnabled,
        boolean whatsappEnabled) {
}
