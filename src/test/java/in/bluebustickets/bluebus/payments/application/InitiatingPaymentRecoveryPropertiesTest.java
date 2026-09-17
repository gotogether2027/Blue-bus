package in.bluebustickets.bluebus.payments.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InitiatingPaymentRecoveryPropertiesTest {

    @Test
    void backoffDoublesUntilTheFifteenMinuteCapAndHasNoAttemptLimit() {
        InitiatingPaymentRecoveryProperties properties = new InitiatingPaymentRecoveryProperties();

        assertThat(properties.backoffDelayMs(1)).isEqualTo(5_000L);
        assertThat(properties.backoffDelayMs(2)).isEqualTo(10_000L);
        assertThat(properties.backoffDelayMs(3)).isEqualTo(20_000L);
        assertThat(properties.backoffDelayMs(4)).isEqualTo(40_000L);
        assertThat(properties.backoffDelayMs(8)).isEqualTo(640_000L);
        assertThat(properties.backoffDelayMs(9)).isEqualTo(900_000L);
        assertThat(properties.backoffDelayMs(10)).isEqualTo(900_000L);
        assertThat(properties.backoffDelayMs(10_000)).isEqualTo(900_000L);
    }

    @Test
    void defaultsMatchTheRecoverySpecification() {
        InitiatingPaymentRecoveryProperties properties = new InitiatingPaymentRecoveryProperties();
        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getPollIntervalMs()).isEqualTo(5_000L);
        assertThat(properties.getBatchSize()).isEqualTo(50);
        assertThat(properties.getLeaseMs()).isEqualTo(45_000L);
        assertThat(properties.getStaleThresholdMs()).isEqualTo(30_000L);
        assertThat(properties.getInitialBackoffMs()).isEqualTo(5_000L);
        assertThat(properties.getMaxBackoffMs()).isEqualTo(900_000L);
    }

    @Test
    void rejectsSubSecondIntervals() {
        InitiatingPaymentRecoveryProperties properties = new InitiatingPaymentRecoveryProperties();
        assertThatThrownBy(() -> properties.setStaleThresholdMs(999L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties.setLeaseMs(0L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
