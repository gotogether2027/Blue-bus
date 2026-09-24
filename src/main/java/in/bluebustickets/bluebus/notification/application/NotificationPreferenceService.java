package in.bluebustickets.bluebus.notification.application;

import java.util.UUID;

import in.bluebustickets.bluebus.notification.domain.NotificationChannel;
import in.bluebustickets.bluebus.notification.domain.NotificationPreference;
import in.bluebustickets.bluebus.notification.repository.NotificationPreferenceRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class NotificationPreferenceService {

    private final NotificationPreferenceRepository preferenceRepository;

    public NotificationPreferenceService(NotificationPreferenceRepository preferenceRepository) {
        this.preferenceRepository = preferenceRepository;
    }

    @Transactional(readOnly = true)
    public NotificationPreference effectiveFor(UUID userId) {
        return preferenceRepository.findById(userId)
                .orElseGet(() -> NotificationPreference.defaults(userId));
    }

    public boolean isChannelEnabled(UUID userId, NotificationChannel channel) {
        return effectiveFor(userId).isChannelEnabled(channel);
    }
}
