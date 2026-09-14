package in.bluebustickets.bluebus.foundation.api.error;

/**
 * Base exception for expected application-state conflicts. Domain modules may use it without
 * exposing implementation details to API clients.
 */
public class ApplicationConflictException extends RuntimeException {

    public ApplicationConflictException() {
        super("Request conflicts with the current state.");
    }

    public ApplicationConflictException(String message) {
        super(message);
    }
}
