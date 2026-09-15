package in.bluebustickets.bluebus.ticket.application;

import java.security.SecureRandom;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Customer-facing ticket numbers: {@code BB} + 8 unambiguous uppercase alphanumerics.
 * Alphabet omits I/O/0/1. Uses {@link SecureRandom}; uniqueness is enforced by the database.
 */
@Component
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class TicketNumberGenerator {

    static final String PREFIX = "BB";
    static final int BODY_LENGTH = 8;
    /** Unambiguous characters: no I, O, 0, or 1. */
    private static final char[] ALPHABET =
            "23456789ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();

    private final SecureRandom secureRandom = new SecureRandom();

    public String next() {
        char[] body = new char[BODY_LENGTH];
        for (int i = 0; i < BODY_LENGTH; i++) {
            body[i] = ALPHABET[secureRandom.nextInt(ALPHABET.length)];
        }
        return PREFIX + new String(body);
    }
}
