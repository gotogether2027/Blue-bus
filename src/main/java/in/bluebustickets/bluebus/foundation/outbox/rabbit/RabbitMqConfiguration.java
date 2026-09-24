package in.bluebustickets.bluebus.foundation.outbox.rabbit;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory.ConfirmType;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.amqp.RabbitProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.util.StringUtils;

/**
 * AMQP beans exist only when {@code blue-bus.rabbitmq.enabled=true}.
 * Spring Boot {@code RabbitAutoConfiguration} is excluded so local startup
 * does not require a broker.
 * <p>
 * Topology (durable, not auto-delete):
 * <ul>
 *   <li>topic exchange {@code blue-bus.events}</li>
 *   <li>queue {@code blue-bus.booking-confirmed} bound to {@code booking.confirmed}</li>
 *   <li>queue {@code blue-bus.notifications} bound to {@code booking.*},
 *       {@code ticket.*}, {@code refund.*}, and {@code payment.*}</li>
 * </ul>
 */
@Configuration
@EnableRabbit
@EnableConfigurationProperties({RabbitMqProperties.class, RabbitProperties.class})
@ConditionalOnProperty(prefix = "blue-bus.rabbitmq", name = "enabled", havingValue = "true")
public class RabbitMqConfiguration {

    @Bean
    CachingConnectionFactory rabbitConnectionFactory(
            RabbitMqProperties properties,
            RabbitProperties springRabbit) {
        CachingConnectionFactory factory = new CachingConnectionFactory();
        factory.setHost(resolveHost(properties, springRabbit));
        factory.setPort(springRabbit.determinePort());
        factory.setUsername(springRabbit.determineUsername());
        factory.setPassword(springRabbit.determinePassword());
        if (StringUtils.hasText(springRabbit.determineVirtualHost())) {
            factory.setVirtualHost(springRabbit.determineVirtualHost());
        }
        factory.setPublisherConfirmType(ConfirmType.CORRELATED);
        factory.setPublisherReturns(properties.isPublisherReturns());
        factory.getRabbitConnectionFactory().setConnectionTimeout(3_000);
        factory.getRabbitConnectionFactory().setAutomaticRecoveryEnabled(true);
        return factory;
    }

    @Bean
    RabbitTemplate rabbitTemplate(
            ConnectionFactory connectionFactory,
            RabbitMqProperties properties) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMandatory(properties.isPublisherReturns());
        template.setExchange(properties.getExchange());
        return template;
    }

    @Bean
    RabbitAdmin rabbitAdmin(ConnectionFactory connectionFactory, RabbitMqProperties properties) {
        RabbitAdmin admin = new RabbitAdmin(connectionFactory);
        admin.setAutoStartup(properties.isDeclareTopologyOnStartup());
        return admin;
    }

    @Bean
    TopicExchange blueBusEventsExchange(RabbitMqProperties properties) {
        return new TopicExchange(properties.getExchange(), true, false);
    }

    @Bean
    Queue bookingConfirmedQueue(RabbitMqProperties properties) {
        return QueueBuilder.durable(properties.getQueue()).build();
    }

    @Bean
    Binding bookingConfirmedBinding(
            Queue bookingConfirmedQueue,
            TopicExchange blueBusEventsExchange,
            RabbitMqProperties properties) {
        return BindingBuilder.bind(bookingConfirmedQueue)
                .to(blueBusEventsExchange)
                .with(properties.getRoutingKey());
    }

    @Bean
    SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            RabbitMqProperties properties) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setPrefetchCount(properties.getPrefetch());
        factory.setDefaultRequeueRejected(true);
        factory.setAutoStartup(
                properties.isConsumerEnabled() || properties.isNotificationConsumerEnabled());
        return factory;
    }

    @Bean
    Queue notificationsQueue(RabbitMqProperties properties) {
        return QueueBuilder.durable(properties.getNotificationQueue()).build();
    }

    @Bean
    Binding notificationBookingBinding(
            Queue notificationsQueue,
            TopicExchange blueBusEventsExchange) {
        return BindingBuilder.bind(notificationsQueue).to(blueBusEventsExchange).with("booking.*");
    }

    @Bean
    Binding notificationTicketBinding(
            Queue notificationsQueue,
            TopicExchange blueBusEventsExchange) {
        return BindingBuilder.bind(notificationsQueue).to(blueBusEventsExchange).with("ticket.*");
    }

    @Bean
    Binding notificationRefundBinding(
            Queue notificationsQueue,
            TopicExchange blueBusEventsExchange) {
        return BindingBuilder.bind(notificationsQueue).to(blueBusEventsExchange).with("refund.*");
    }

    @Bean
    Binding notificationPaymentBinding(
            Queue notificationsQueue,
            TopicExchange blueBusEventsExchange) {
        return BindingBuilder.bind(notificationsQueue).to(blueBusEventsExchange).with("payment.*");
    }

    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(prefix = "blue-bus.rabbitmq.publisher", name = "enabled", matchIfMissing = true)
    static class RabbitPublisherSchedulingConfiguration {
    }

    private static String resolveHost(RabbitMqProperties properties, RabbitProperties springRabbit) {
        if (StringUtils.hasText(properties.getHost())) {
            return properties.getHost();
        }
        return springRabbit.determineHost();
    }
}
