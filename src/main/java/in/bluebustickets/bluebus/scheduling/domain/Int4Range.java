package in.bluebustickets.bluebus.scheduling.domain;

/**
 * Half-open stop-sequence range {@code [origin, destination)} matching PostgreSQL {@code int4range}.
 */
public record Int4Range(int lower, int upper) implements java.io.Serializable {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    public Int4Range {
        if (lower < 1 || upper <= lower) {
            throw new IllegalArgumentException("int4range requires 1 <= lower < upper");
        }
    }

    public static Int4Range halfOpen(int originSequence, int destinationSequence) {
        return new Int4Range(originSequence, destinationSequence);
    }

    /** Canonical PostgreSQL text form, e.g. {@code [1,3)}. */
    public String toPostgresLiteral() {
        return "[" + lower + "," + upper + ")";
    }

    public static Int4Range parsePostgresLiteral(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("int4range literal is required");
        }
        String normalized = value.trim();
        if (!normalized.startsWith("[") || !normalized.endsWith(")")) {
            throw new IllegalArgumentException("Unsupported int4range literal: " + value);
        }
        String body = normalized.substring(1, normalized.length() - 1);
        String[] parts = body.split(",", 2);
        if (parts.length != 2) {
            throw new IllegalArgumentException("Unsupported int4range literal: " + value);
        }
        return new Int4Range(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()));
    }
}
