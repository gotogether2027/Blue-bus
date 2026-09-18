package in.bluebustickets.bluebus.foundation.ratelimit;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fixed-window counter keyed by category + client IP. Fail-open if the map is saturated
 * so a burst of distinct IPs cannot turn the limiter into a denial of service.
 */
public class InMemoryRateLimiter {

    static final int MAX_KEYS = 100_000;

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final RateLimitProperties properties;
    private final Clock clock;

    public InMemoryRateLimiter(RateLimitProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public Decision allow(RateLimitCategory category, String clientKey) {
        RateLimitProperties.Bucket bucket = properties.bucket(category);
        String key = category.name() + ":" + clientKey;
        if (windows.size() >= MAX_KEYS && !windows.containsKey(key)) {
            return Decision.permit();
        }
        long now = clock.millis();
        long windowMillis = bucket.getWindowSeconds() * 1000L;
        int limit = bucket.getLimit();
        Decision[] decision = new Decision[1];
        windows.compute(key, (ignored, current) -> {
            if (current == null || now - current.windowStartMillis >= windowMillis) {
                decision[0] = Decision.permit();
                return new Window(now, 1);
            }
            if (current.count >= limit) {
                long remainingMillis = Math.max(0L, current.windowStartMillis + windowMillis - now);
                int retryAfterSeconds = (int) Math.max(1L, (remainingMillis + 999L) / 1000L);
                decision[0] = Decision.reject(retryAfterSeconds);
                return current;
            }
            current.count++;
            decision[0] = Decision.permit();
            return current;
        });
        return decision[0] == null ? Decision.permit() : decision[0];
    }

    public record Decision(boolean allowed, int retryAfterSeconds) {
        static Decision permit() {
            return new Decision(true, 0);
        }

        static Decision reject(int retryAfterSeconds) {
            return new Decision(false, retryAfterSeconds);
        }
    }

    private static final class Window {
        private final long windowStartMillis;
        private int count;

        private Window(long windowStartMillis, int count) {
            this.windowStartMillis = windowStartMillis;
            this.count = count;
        }
    }
}
