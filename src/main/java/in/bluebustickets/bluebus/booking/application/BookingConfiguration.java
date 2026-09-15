package in.bluebustickets.bluebus.booking.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers unpaid-booking TTL and expiry properties whenever booking services are enabled,
 * even if the scheduled reaper itself is disabled (e.g. tests).
 */
@Configuration
@EnableConfigurationProperties({BookingUnpaidProperties.class, BookingExpiryProperties.class})
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class BookingConfiguration {
}
