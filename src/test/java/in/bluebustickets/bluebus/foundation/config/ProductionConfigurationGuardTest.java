package in.bluebustickets.bluebus.foundation.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionConfigurationGuardTest {

    @Test
    void allowsDemoDataOutsideProduction() {
        MockEnvironment environment = localEnvironment();
        environment.setProperty("blue-bus.demo-data.enabled", "true");
        environment.setProperty("blue-bus.payments.default-provider", "UNCONFIGURED");

        assertThatCode(() -> ProductionConfigurationGuard.validate(environment)).doesNotThrowAnyException();
    }

    @Test
    void allowsUnconfiguredPaymentsOutsideProduction() {
        assertThatCode(() -> ProductionConfigurationGuard.validate(localEnvironment())).doesNotThrowAnyException();
    }

    @Test
    void allowsTestKillSwitchWhenOutboxProcessorIsAlsoDisabled() {
        MockEnvironment environment = localEnvironment();
        environment.setProperty("blue-bus.admin-master-data.enabled", "false");
        environment.setProperty("blue-bus.outbox.enabled", "false");
        environment.setProperty("blue-bus.outbox.processor.enabled", "false");

        assertThatCode(() -> ProductionConfigurationGuard.validate(environment)).doesNotThrowAnyException();
    }

    @Test
    void rejectsOutboxProcessorWithoutCoreApi() {
        MockEnvironment environment = localEnvironment();
        environment.setProperty("blue-bus.admin-master-data.enabled", "false");
        environment.setProperty("blue-bus.outbox.enabled", "true");
        environment.setProperty("blue-bus.outbox.processor.enabled", "true");

        assertThatThrownBy(() -> ProductionConfigurationGuard.validate(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(ProductionConfigurationGuard.OUTBOX_WITHOUT_TICKET_HANDLER);
    }

    @Test
    void productionPlusDemoDataFailsFast() {
        MockEnvironment environment = productionEnvironment();
        environment.setProperty("blue-bus.demo-data.enabled", "true");

        assertThatThrownBy(() -> ProductionConfigurationGuard.validate(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(ProductionConfigurationGuard.DEMO_DATA_IN_PROD)
                .hasMessageNotContaining("password")
                .hasMessageNotContaining("DemoPass");
    }

    @Test
    void productionPlusDisabledCoreApiFailsFast() {
        MockEnvironment environment = productionEnvironment();
        environment.setProperty("blue-bus.admin-master-data.enabled", "false");
        environment.setProperty("blue-bus.outbox.processor.enabled", "false");
        environment.setProperty("blue-bus.outbox.enabled", "false");

        assertThatThrownBy(() -> ProductionConfigurationGuard.validate(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(ProductionConfigurationGuard.API_DISABLED_IN_PROD);
    }

    @Test
    void productionPlusUnconfiguredPaymentsFailsFast() {
        MockEnvironment environment = productionEnvironment();
        environment.setProperty("blue-bus.payments.default-provider", "UNCONFIGURED");

        assertThatThrownBy(() -> ProductionConfigurationGuard.validate(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(ProductionConfigurationGuard.PAYMENTS_UNCONFIGURED_IN_PROD);
    }

    @Test
    void productionPlusRazorpayWithoutSecretsFailsFastWithoutLeakingValues() {
        MockEnvironment environment = productionEnvironment();
        environment.setProperty("blue-bus.payments.default-provider", "RAZORPAY");
        environment.setProperty("blue-bus.payments.razorpay.key-id", "rzp_live_not_a_real_key");
        environment.setProperty("blue-bus.payments.razorpay.key-secret", "");
        environment.setProperty("blue-bus.payments.razorpay.webhook-secret", "whsec-not-real");

        assertThatThrownBy(() -> ProductionConfigurationGuard.validate(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(ProductionConfigurationGuard.RAZORPAY_SECRETS_MISSING_IN_PROD)
                .hasMessageNotContaining("rzp_live_not_a_real_key")
                .hasMessageNotContaining("whsec-not-real");
    }

    @Test
    void productionWithRazorpaySecretsAndSameOriginCorsSucceeds() {
        assertThatCode(() -> ProductionConfigurationGuard.validate(productionEnvironment()))
                .doesNotThrowAnyException();
    }

    @Test
    void splitOriginFlagRequiresExplicitAllowList() {
        MockEnvironment environment = productionEnvironment();
        environment.setProperty("blue-bus.cors.require-allowed-origins", "true");

        assertThatThrownBy(() -> ProductionConfigurationGuard.validate(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(ProductionConfigurationGuard.CORS_ORIGINS_REQUIRED);
    }

    @Test
    void splitOriginFlagAcceptsIndexedOrigin() {
        MockEnvironment environment = productionEnvironment();
        environment.setProperty("blue-bus.cors.require-allowed-origins", "true");
        environment.setProperty("blue-bus.cors.allowed-origins[0]", "https://tickets.example.com");

        assertThatCode(() -> ProductionConfigurationGuard.validate(environment)).doesNotThrowAnyException();
    }

    private static MockEnvironment localEnvironment() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("test");
        environment.setProperty("blue-bus.admin-master-data.enabled", "true");
        environment.setProperty("blue-bus.outbox.enabled", "true");
        environment.setProperty("blue-bus.outbox.processor.enabled", "false");
        environment.setProperty("blue-bus.demo-data.enabled", "false");
        environment.setProperty("blue-bus.payments.default-provider", "UNCONFIGURED");
        environment.setProperty("blue-bus.cors.require-allowed-origins", "false");
        return environment;
    }

    private static MockEnvironment productionEnvironment() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        environment.setProperty("blue-bus.admin-master-data.enabled", "true");
        environment.setProperty("blue-bus.outbox.enabled", "true");
        environment.setProperty("blue-bus.outbox.processor.enabled", "true");
        environment.setProperty("blue-bus.demo-data.enabled", "false");
        environment.setProperty("blue-bus.payments.default-provider", "RAZORPAY");
        environment.setProperty("blue-bus.payments.razorpay.key-id", "rzp_test_placeholder");
        environment.setProperty("blue-bus.payments.razorpay.key-secret", "test-secret-value");
        environment.setProperty("blue-bus.payments.razorpay.webhook-secret", "test-webhook-value");
        environment.setProperty("blue-bus.cors.require-allowed-origins", "false");
        return environment;
    }
}
