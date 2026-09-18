package in.bluebustickets.bluebus.foundation.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryRateLimiterTest {

    private RateLimitProperties properties;
    private AdjustableClock clock;
    private InMemoryRateLimiter limiter;

    @BeforeEach
    void setUp() {
        properties = new RateLimitProperties();
        properties.getAuth().setLimit(2);
        properties.getAuth().setWindowSeconds(60);
        properties.getPublicApi().setLimit(2);
        properties.getPublicApi().setWindowSeconds(60);
        properties.getHolds().setLimit(2);
        properties.getHolds().setWindowSeconds(60);
        properties.getWebhook().setLimit(2);
        properties.getWebhook().setWindowSeconds(60);
        clock = new AdjustableClock(Instant.parse("2026-01-01T00:00:00Z"));
        limiter = new InMemoryRateLimiter(properties, clock);
    }

    @Test
    void allowsRequestsUnderTheLimit() {
        assertThat(limiter.allow(RateLimitCategory.AUTH, "10.0.0.1").allowed()).isTrue();
        assertThat(limiter.allow(RateLimitCategory.AUTH, "10.0.0.1").allowed()).isTrue();
    }

    @Test
    void rejectsTheRequestThatExceedsTheLimit() {
        limiter.allow(RateLimitCategory.AUTH, "10.0.0.1");
        limiter.allow(RateLimitCategory.AUTH, "10.0.0.1");

        InMemoryRateLimiter.Decision decision = limiter.allow(RateLimitCategory.AUTH, "10.0.0.1");

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.retryAfterSeconds()).isEqualTo(60);
    }

    @Test
    void usesIndependentBucketsPerClientIp() {
        limiter.allow(RateLimitCategory.AUTH, "10.0.0.1");
        limiter.allow(RateLimitCategory.AUTH, "10.0.0.1");

        assertThat(limiter.allow(RateLimitCategory.AUTH, "10.0.0.2").allowed()).isTrue();
        assertThat(limiter.allow(RateLimitCategory.AUTH, "10.0.0.1").allowed()).isFalse();
    }

    @Test
    void resetsAfterTheConfiguredWindow() {
        limiter.allow(RateLimitCategory.AUTH, "10.0.0.1");
        limiter.allow(RateLimitCategory.AUTH, "10.0.0.1");
        assertThat(limiter.allow(RateLimitCategory.AUTH, "10.0.0.1").allowed()).isFalse();

        clock.advance(Duration.ofSeconds(60));

        assertThat(limiter.allow(RateLimitCategory.AUTH, "10.0.0.1").allowed()).isTrue();
    }

    @Test
    void retryAfterShrinksAsTheWindowElapses() {
        limiter.allow(RateLimitCategory.WEBHOOK, "10.0.0.1");
        limiter.allow(RateLimitCategory.WEBHOOK, "10.0.0.1");
        clock.advance(Duration.ofSeconds(25));

        InMemoryRateLimiter.Decision decision = limiter.allow(RateLimitCategory.WEBHOOK, "10.0.0.1");

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.retryAfterSeconds()).isEqualTo(35);
    }

    @Test
    void categoriesDoNotShareCounters() {
        limiter.allow(RateLimitCategory.AUTH, "10.0.0.1");
        limiter.allow(RateLimitCategory.AUTH, "10.0.0.1");

        assertThat(limiter.allow(RateLimitCategory.PUBLIC_API, "10.0.0.1").allowed()).isTrue();
        assertThat(limiter.allow(RateLimitCategory.HOLDS, "10.0.0.1").allowed()).isTrue();
        assertThat(limiter.allow(RateLimitCategory.WEBHOOK, "10.0.0.1").allowed()).isTrue();
    }

    private static final class AdjustableClock extends Clock {
        private Instant instant;

        private AdjustableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return Clock.fixed(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
