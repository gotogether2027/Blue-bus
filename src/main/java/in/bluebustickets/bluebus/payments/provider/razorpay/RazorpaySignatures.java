package in.bluebustickets.bluebus.payments.provider.razorpay;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

final class RazorpaySignatures {

    private RazorpaySignatures() {
    }

    static boolean matches(String payload, String secret, String presentedHexSignature) {
        if (payload == null) {
            return false;
        }
        return matches(payload.getBytes(StandardCharsets.UTF_8), secret, presentedHexSignature);
    }

    static boolean matches(byte[] payload, String secret, String presentedHexSignature) {
        if (payload == null || secret == null || presentedHexSignature == null || presentedHexSignature.isBlank()) {
            return false;
        }
        String expected = hmacSha256Hex(payload, secret);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                presentedHexSignature.trim().toLowerCase().getBytes(StandardCharsets.UTF_8));
    }

    static String hmacSha256Hex(String payload, String secret) {
        return hmacSha256Hex(payload.getBytes(StandardCharsets.UTF_8), secret);
    }

    static String hmacSha256Hex(byte[] payload, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to compute Razorpay signature.");
        }
    }
}
