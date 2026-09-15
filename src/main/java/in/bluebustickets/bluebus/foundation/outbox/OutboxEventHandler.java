package in.bluebustickets.bluebus.foundation.outbox;

/**
 * Handles one supported outbox event type inside the processor transaction.
 */
public interface OutboxEventHandler {

    String eventType();

    /**
     * Process a locked unpublished event. Must be idempotent.
     * Failures should throw so the processor leaves the event unpublished for retry.
     */
    void handle(OutboxEvent event);
}
