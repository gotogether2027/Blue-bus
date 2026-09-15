package in.bluebustickets.bluebus.foundation.api.error;

/**
 * Raised when an authenticated, active user lacks the privilege required for the operation.
 */
public class ApplicationForbiddenException extends RuntimeException {

    public ApplicationForbiddenException() {
        super("Access is denied.");
    }

    public ApplicationForbiddenException(String message) {
        super(message);
    }
}
