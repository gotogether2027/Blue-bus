package in.bluebustickets.bluebus.foundation.api.error;

/**
 * Raised when an admin or application request targets a missing resource.
 */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
