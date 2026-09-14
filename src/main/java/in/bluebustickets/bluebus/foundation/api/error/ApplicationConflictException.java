package in.bluebustickets.bluebus.foundation.api.error;

/**
 * Base exception for expected application-state conflicts. Domain modules may use it in later
 * phases without exposing implementation details to API clients.
 */
public class ApplicationConflictException extends RuntimeException {

    public ApplicationConflictException() {
        super();
    }
}
