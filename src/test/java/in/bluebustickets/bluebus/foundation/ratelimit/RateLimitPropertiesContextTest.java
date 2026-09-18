package in.bluebustickets.bluebus.foundation.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
        "blue-bus.admin-master-data.enabled=false",
        "blue-bus.outbox.enabled=false"
})
@ActiveProfiles("test")
class RateLimitPropertiesContextTest {

    @Autowired
    private RateLimitProperties rateLimitProperties;

    @Test
    void testProfileKeepsHighLimitsSoExistingSuitesAreNotTripped() {
        assertThat(rateLimitProperties.isEnabled()).isTrue();
        assertThat(rateLimitProperties.getAuth().getLimit()).isEqualTo(10000);
        assertThat(rateLimitProperties.getPublicApi().getLimit()).isEqualTo(10000);
        assertThat(rateLimitProperties.getHolds().getLimit()).isEqualTo(10000);
        assertThat(rateLimitProperties.getWebhook().getLimit()).isEqualTo(10000);
        assertThat(rateLimitProperties.getAuth().getWindowSeconds()).isEqualTo(60);
    }
}
