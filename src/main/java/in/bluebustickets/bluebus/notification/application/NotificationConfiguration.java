package in.bluebustickets.bluebus.notification.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableConfigurationProperties(NotificationProperties.class)
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class NotificationConfiguration {

    @Configuration
    @EnableScheduling
    static class NotificationSchedulingConfiguration {
    }
}
