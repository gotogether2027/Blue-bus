package in.bluebustickets.bluebus.foundation.outbox;

public record OutboxProcessingResult(int processed, int failures) {

    public static OutboxProcessingResult empty() {
        return new OutboxProcessingResult(0, 0);
    }

    public OutboxProcessingResult plus(OutboxProcessingResult other) {
        return new OutboxProcessingResult(
                this.processed + other.processed,
                this.failures + other.failures);
    }
}
