package in.bluebustickets.bluebus.foundation.outbox.rabbit;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Always bind RabbitMQ properties so the local outbox processor can see whether
 * the consumer is authoritative. AMQP beans remain conditional.
 */
@Configuration
@EnableConfigurationProperties(RabbitMqProperties.class)
public class RabbitMqPropertiesConfiguration {
}
