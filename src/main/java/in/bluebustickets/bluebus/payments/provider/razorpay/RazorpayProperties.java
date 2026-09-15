package in.bluebustickets.bluebus.payments.provider.razorpay;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Razorpay credentials and endpoints. Secrets come from the environment, never from source or the database.
 */
@ConfigurationProperties(prefix = "blue-bus.payments.razorpay")
public class RazorpayProperties {

    private String keyId = "";
    private String keySecret = "";
    private String webhookSecret = "";
    private String baseUrl = "https://api.razorpay.com";
    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration readTimeout = Duration.ofSeconds(10);

    public void requireConfigured() {
        if (isBlank(keyId) || isBlank(keySecret) || isBlank(webhookSecret) || isBlank(baseUrl)) {
            throw new IllegalStateException(
                    "Razorpay is the configured payment provider but required credentials are missing.");
        }
    }

    public String getKeyId() {
        return keyId;
    }

    public void setKeyId(String keyId) {
        this.keyId = trim(keyId);
    }

    public String getKeySecret() {
        return keySecret;
    }

    public void setKeySecret(String keySecret) {
        this.keySecret = trim(keySecret);
    }

    public String getWebhookSecret() {
        return webhookSecret;
    }

    public void setWebhookSecret(String webhookSecret) {
        this.webhookSecret = trim(webhookSecret);
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = trim(baseUrl);
        if (this.baseUrl.endsWith("/")) {
            this.baseUrl = this.baseUrl.substring(0, this.baseUrl.length() - 1);
        }
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout == null ? Duration.ofSeconds(5) : connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout == null ? Duration.ofSeconds(10) : readTimeout;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    @Override
    public String toString() {
        return "RazorpayProperties[keyIdConfigured=" + !isBlank(keyId)
                + ", keySecretConfigured=" + !isBlank(keySecret)
                + ", webhookSecretConfigured=" + !isBlank(webhookSecret)
                + ", baseUrl=" + baseUrl + "]";
    }
}
