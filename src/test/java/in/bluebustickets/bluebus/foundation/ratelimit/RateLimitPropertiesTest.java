package in.bluebustickets.bluebus.foundation.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RateLimitPropertiesTest {

    @Test
    void documentedDefaultsMatchASingleInstanceBaseline() {
        RateLimitProperties properties = new RateLimitProperties();

        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getAuth().getLimit()).isEqualTo(20);
        assertThat(properties.getAuth().getWindowSeconds()).isEqualTo(60);
        assertThat(properties.getPublicApi().getLimit()).isEqualTo(120);
        assertThat(properties.getPublicApi().getWindowSeconds()).isEqualTo(60);
        assertThat(properties.getHolds().getLimit()).isEqualTo(30);
        assertThat(properties.getHolds().getWindowSeconds()).isEqualTo(60);
        assertThat(properties.getWebhook().getLimit()).isEqualTo(120);
        assertThat(properties.getWebhook().getWindowSeconds()).isEqualTo(60);
    }

    @Test
    void bindsEnvironmentAndRelaxedPropertyOverrides() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("blue-bus.rate-limit.enabled", "true");
        environment.setProperty("blue-bus.rate-limit.auth.limit", "7");
        environment.setProperty("blue-bus.rate-limit.auth.window-seconds", "15");
        environment.setProperty("blue-bus.rate-limit.public-api.limit", "9");
        environment.setProperty("blue-bus.rate-limit.public-api.window-seconds", "30");
        environment.setProperty("blue-bus.rate-limit.holds.limit", "4");
        environment.setProperty("blue-bus.rate-limit.holds.window-seconds", "10");
        environment.setProperty("blue-bus.rate-limit.webhook.limit", "11");
        environment.setProperty("blue-bus.rate-limit.webhook.window-seconds", "12");

        RateLimitProperties properties = Binder.get(environment)
                .bind("blue-bus.rate-limit", RateLimitProperties.class)
                .orElseThrow(() -> new IllegalStateException("Failed to bind blue-bus.rate-limit"));

        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getAuth().getLimit()).isEqualTo(7);
        assertThat(properties.getAuth().getWindowSeconds()).isEqualTo(15);
        assertThat(properties.getPublicApi().getLimit()).isEqualTo(9);
        assertThat(properties.getPublicApi().getWindowSeconds()).isEqualTo(30);
        assertThat(properties.getHolds().getLimit()).isEqualTo(4);
        assertThat(properties.getHolds().getWindowSeconds()).isEqualTo(10);
        assertThat(properties.getWebhook().getLimit()).isEqualTo(11);
        assertThat(properties.getWebhook().getWindowSeconds()).isEqualTo(12);
    }

    @Test
    void rejectsNonPositiveLimitsAndWindows() {
        RateLimitProperties.Bucket bucket = new RateLimitProperties.Bucket();
        assertThatThrownBy(() -> bucket.setLimit(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit");
        assertThatThrownBy(() -> bucket.setWindowSeconds(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("window-seconds");
    }
}
