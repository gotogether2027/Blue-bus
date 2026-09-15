package in.bluebustickets.bluebus.scheduling.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
@ConditionalOnProperty(prefix = "blue-bus.seat-holds.expiry", name = "enabled", matchIfMissing = true)
public class SeatHoldExpirySchedulerConfiguration {
}
