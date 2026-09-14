package in.bluebustickets.bluebus.foundation.api.error;

import java.time.Instant;
import java.util.List;

public record ApiError(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        List<FieldViolation> fieldViolations) {

    public record FieldViolation(String field, String message) {
    }
}
