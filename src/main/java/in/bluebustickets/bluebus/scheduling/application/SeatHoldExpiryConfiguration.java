package in.bluebustickets.bluebus.scheduling.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers expiry properties whenever master-data services are enabled,
 * even if the scheduled reaper itself is disabled (e.g. tests).
 */
@Configuration
@EnableConfigurationProperties(SeatHoldExpiryProperties.class)
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class SeatHoldExpiryConfiguration {
}
