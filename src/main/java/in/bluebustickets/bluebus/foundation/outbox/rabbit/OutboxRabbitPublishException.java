package in.bluebustickets.bluebus.foundation.outbox.rabbit;

/**
 * Transient broker publish failure. The outbox row stays unpublished for retry.
 */
public class OutboxRabbitPublishException extends RuntimeException {

    public OutboxRabbitPublishException(String message) {
        super(message);
    }

    public OutboxRabbitPublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
