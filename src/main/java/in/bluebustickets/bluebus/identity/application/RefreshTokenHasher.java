package in.bluebustickets.bluebus.identity.application;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

import org.springframework.stereotype.Component;

/**
 * Opaque refresh-token generation and SHA-256 digests for storage.
 * Raw tokens must never be logged or persisted.
 */
@Component
public class RefreshTokenHasher {

    private static final int TOKEN_BYTES = 32;
    private final SecureRandom secureRandom = new SecureRandom();

    public GeneratedRefreshToken generate() {
        byte[] raw = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(raw);
        String transport = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        return new GeneratedRefreshToken(transport, sha256(raw));
    }

    public byte[] digestTransportToken(String transportToken) {
        if (transportToken == null || transportToken.isBlank()) {
            throw new IllegalArgumentException("Refresh token is required");
        }
        byte[] raw;
        try {
            raw = Base64.getUrlDecoder().decode(transportToken.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Refresh token is malformed");
        }
        if (raw.length != TOKEN_BYTES) {
            throw new IllegalArgumentException("Refresh token is malformed");
        }
        return sha256(raw);
    }

    private static byte[] sha256(byte[] raw) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(raw);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    public record GeneratedRefreshToken(String transportValue, byte[] hash) {
        public GeneratedRefreshToken {
            if (transportValue == null || transportValue.isBlank()) {
                throw new IllegalArgumentException("transportValue is required");
            }
            if (hash == null || hash.length != 32) {
                throw new IllegalArgumentException("hash must be 32 bytes");
            }
            hash = hash.clone();
        }

        public byte[] hash() {
            return hash.clone();
        }
    }
}
