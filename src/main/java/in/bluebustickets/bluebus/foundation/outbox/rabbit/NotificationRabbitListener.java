package in.bluebustickets.bluebus.foundation.outbox.rabbit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * Manual-ack notification listener. Ack only after the consumer transaction
 * commits. Transient failures are requeued. Malformed envelopes are not.
 */
@Component
@ConditionalOnBean(NotificationRabbitConsumer.class)
@ConditionalOnProperty(prefix = "blue-bus.rabbitmq", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "blue-bus.rabbitmq", name = "notification-consumer-enabled", matchIfMissing = true)
public class NotificationRabbitListener {

    private static final Logger log = LoggerFactory.getLogger(NotificationRabbitListener.class);

    private final NotificationRabbitConsumer consumer;
    private final ObjectMapper objectMapper;

    public NotificationRabbitListener(
            NotificationRabbitConsumer consumer,
            ObjectMapper objectMapper) {
        this.consumer = consumer;
        this.objectMapper = objectMapper;
    }

    @RabbitListener(queues = "${blue-bus.rabbitmq.notification-queue}", ackMode = "MANUAL")
    public void onMessage(
            Message message,
            Channel channel,
            @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        String messageId = message.getMessageProperties().getMessageId();
        try {
            OutboxMessageEnvelope envelope = readEnvelope(message);
            consumer.process(envelope);
            channel.basicAck(deliveryTag, false);
        } catch (MalformedOutboxMessageException exception) {
            log.warn(
                    "RabbitMQ notification consumer malformed envelope messageId={}: {}",
                    messageId,
                    exception.getMessage());
            nack(channel, deliveryTag, false, messageId);
        } catch (RuntimeException exception) {
            log.warn(
                    "RabbitMQ notification consumer failure messageId={}: {}",
                    messageId,
                    exception.getMessage());
            nack(channel, deliveryTag, true, messageId);
        } catch (Exception exception) {
            log.warn(
                    "RabbitMQ notification consumer failure messageId={}: {}",
                    messageId,
                    exception.getMessage());
            nack(channel, deliveryTag, true, messageId);
        }
    }

    private OutboxMessageEnvelope readEnvelope(Message message) {
        byte[] body = message.getBody();
        if (body == null || body.length == 0) {
            throw new MalformedOutboxMessageException("message body is required");
        }
        try {
            return BookingConfirmedRabbitConsumer.validate(
                    objectMapper.readValue(body, OutboxMessageEnvelope.class));
        } catch (MalformedOutboxMessageException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new MalformedOutboxMessageException("message body is not a valid outbox envelope", exception);
        }
    }

    private static void nack(Channel channel, long deliveryTag, boolean requeue, String messageId) {
        try {
            channel.basicNack(deliveryTag, false, requeue);
        } catch (Exception nackFailure) {
            log.warn(
                    "RabbitMQ notification consumer nack failed messageId={} requeue={}: {}",
                    messageId,
                    requeue,
                    nackFailure.getMessage());
        }
    }
}
