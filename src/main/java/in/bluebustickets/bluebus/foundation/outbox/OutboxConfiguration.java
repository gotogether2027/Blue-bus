package in.bluebustickets.bluebus.foundation.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Outbox processor beans are gated by {@code blue-bus.outbox.enabled}, not admin master-data.
 * The scheduler is additionally gated by {@code blue-bus.outbox.processor.enabled}.
 */
@Configuration
@EnableConfigurationProperties(OutboxProcessorProperties.class)
@ConditionalOnProperty(prefix = "blue-bus.outbox", name = "enabled", matchIfMissing = true)
public class OutboxConfiguration {

    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(prefix = "blue-bus.outbox.processor", name = "enabled", matchIfMissing = true)
    static class OutboxSchedulingConfiguration {
    }
}
