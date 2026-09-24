package in.bluebustickets.bluebus.foundation.outbox.rabbit;

/**
 * Permanent envelope problem. The listener must not create business effects
 * and must not requeue the message.
 */
public class MalformedOutboxMessageException extends RuntimeException {

    public MalformedOutboxMessageException(String message) {
        super(message);
    }

    public MalformedOutboxMessageException(String message, Throwable cause) {
        super(message, cause);
    }
}
