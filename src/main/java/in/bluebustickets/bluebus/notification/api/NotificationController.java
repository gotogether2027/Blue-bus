package in.bluebustickets.bluebus.notification.api;

import java.util.UUID;

import in.bluebustickets.bluebus.identity.application.CurrentUserService;
import in.bluebustickets.bluebus.notification.api.dto.NotificationPageResponse;
import in.bluebustickets.bluebus.notification.api.dto.NotificationPreferenceResponse;
import in.bluebustickets.bluebus.notification.application.NotificationApplicationService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class NotificationController {

    private final NotificationApplicationService notificationApplicationService;
    private final CurrentUserService currentUserService;

    public NotificationController(
            NotificationApplicationService notificationApplicationService,
            CurrentUserService currentUserService) {
        this.notificationApplicationService = notificationApplicationService;
        this.currentUserService = currentUserService;
    }

    @GetMapping("/notifications")
    @ResponseStatus(HttpStatus.OK)
    public NotificationPageResponse list(
            Authentication authentication,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        UUID userId = currentUserService.requireAuthenticatedUserId(authentication);
        return notificationApplicationService.listOwned(userId, page, size);
    }

    @GetMapping("/notification-preferences")
    @ResponseStatus(HttpStatus.OK)
    public NotificationPreferenceResponse preferences(Authentication authentication) {
        UUID userId = currentUserService.requireAuthenticatedUserId(authentication);
        return notificationApplicationService.preferencesFor(userId);
    }
}
