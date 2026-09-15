package in.bluebustickets.bluebus.identity.application;

import java.util.Locale;

/**
 * Shared email normalization for registration and login.
 * Trim + lower-case only; no provider-specific canonicalization.
 */
public final class EmailNormalizer {

    private EmailNormalizer() {
    }

    public static String normalize(String email) {
        if (email == null) {
            return null;
        }
        String trimmed = email.trim();
        if (trimmed.isEmpty()) {
            return trimmed;
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }
}
