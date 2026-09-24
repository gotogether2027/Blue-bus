package in.bluebustickets.bluebus.notification.repository;

import java.util.UUID;

import in.bluebustickets.bluebus.notification.domain.NotificationPreference;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationPreferenceRepository extends JpaRepository<NotificationPreference, UUID> {
}
