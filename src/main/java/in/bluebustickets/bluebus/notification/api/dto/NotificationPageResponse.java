package in.bluebustickets.bluebus.notification.api.dto;

import java.util.List;

public record NotificationPageResponse(
        List<NotificationResponse> items,
        int page,
        int size,
        long totalElements) {
}
