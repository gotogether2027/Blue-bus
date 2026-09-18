package in.bluebustickets.bluebus.foundation.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Per-instance in-memory rate limits for high-risk public endpoints.
 * Not cluster-wide. Production should also enforce limits at the reverse proxy.
 */
@ConfigurationProperties(prefix = "blue-bus.rate-limit")
public class RateLimitProperties {

    private boolean enabled = true;
    private Bucket auth = new Bucket(20, 60);
    private Bucket publicApi = new Bucket(120, 60);
    private Bucket holds = new Bucket(30, 60);
    private Bucket webhook = new Bucket(120, 60);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Bucket getAuth() {
        return auth;
    }

    public void setAuth(Bucket auth) {
        this.auth = auth == null ? new Bucket(20, 60) : auth;
    }

    public Bucket getPublicApi() {
        return publicApi;
    }

    public void setPublicApi(Bucket publicApi) {
        this.publicApi = publicApi == null ? new Bucket(120, 60) : publicApi;
    }

    public Bucket getHolds() {
        return holds;
    }

    public void setHolds(Bucket holds) {
        this.holds = holds == null ? new Bucket(30, 60) : holds;
    }

    public Bucket getWebhook() {
        return webhook;
    }

    public void setWebhook(Bucket webhook) {
        this.webhook = webhook == null ? new Bucket(120, 60) : webhook;
    }

    public Bucket bucket(RateLimitCategory category) {
        return switch (category) {
            case AUTH -> getAuth();
            case PUBLIC_API -> getPublicApi();
            case HOLDS -> getHolds();
            case WEBHOOK -> getWebhook();
        };
    }

    public static class Bucket {
        private int limit = 1;
        private int windowSeconds = 60;

        public Bucket() {
        }

        public Bucket(int limit, int windowSeconds) {
            setLimit(limit);
            setWindowSeconds(windowSeconds);
        }

        public int getLimit() {
            return limit;
        }

        public void setLimit(int limit) {
            if (limit < 1) {
                throw new IllegalArgumentException("blue-bus.rate-limit.*.limit must be >= 1");
            }
            this.limit = limit;
        }

        public int getWindowSeconds() {
            return windowSeconds;
        }

        public void setWindowSeconds(int windowSeconds) {
            if (windowSeconds < 1) {
                throw new IllegalArgumentException("blue-bus.rate-limit.*.window-seconds must be >= 1");
            }
            this.windowSeconds = windowSeconds;
        }
    }
}
