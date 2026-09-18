package in.bluebustickets.bluebus.foundation.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Fail-fast production configuration checks. Local and {@code test} profiles remain usable
 * with an unconfigured payment provider and optional demo data.
 */
@Component
public class ProductionConfigurationGuard implements InitializingBean {

    public static final String PROD_PROFILE = "prod";

    public static final String DEMO_DATA_IN_PROD =
            "blue-bus.demo-data.enabled cannot be true when the prod profile is active.";
    public static final String API_DISABLED_IN_PROD =
            "blue-bus.admin-master-data.enabled cannot be false when the prod profile is active. "
                    + "That flag gates the core business API, not only admin master data.";
    public static final String OUTBOX_WITHOUT_TICKET_HANDLER =
            "blue-bus.outbox.processor cannot be enabled while blue-bus.admin-master-data.enabled=false "
                    + "because BOOKING_CONFIRMED ticket handling would be missing.";
    public static final String PAYMENTS_UNCONFIGURED_IN_PROD =
            "PAYMENT_PROVIDER must be RAZORPAY when the prod profile is active.";
    public static final String RAZORPAY_SECRETS_MISSING_IN_PROD =
            "Razorpay is the configured payment provider but required credentials are missing.";
    public static final String CORS_ORIGINS_REQUIRED =
            "blue-bus.cors.require-allowed-origins=true requires at least one explicit allowed origin. "
                    + "Same-origin /api/v1 deployments should leave this false and the allow-list empty.";

    private final Environment environment;

    public ProductionConfigurationGuard(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        validate(environment);
    }

    static void validate(Environment environment) {
        boolean production = environment.acceptsProfiles(Profiles.of(PROD_PROFILE));
        boolean apiEnabled = environment.getProperty(
                "blue-bus.admin-master-data.enabled", Boolean.class, Boolean.TRUE);
        boolean outboxEnabled = environment.getProperty("blue-bus.outbox.enabled", Boolean.class, Boolean.TRUE);
        boolean outboxProcessorEnabled = environment.getProperty(
                "blue-bus.outbox.processor.enabled", Boolean.class, Boolean.TRUE);
        boolean demoDataEnabled =
                environment.getProperty("blue-bus.demo-data.enabled", Boolean.class, Boolean.FALSE);
        boolean requireCorsOrigins = environment.getProperty(
                "blue-bus.cors.require-allowed-origins", Boolean.class, Boolean.FALSE);

        if (production && !apiEnabled) {
            throw new IllegalStateException(API_DISABLED_IN_PROD);
        }
        if (outboxEnabled && outboxProcessorEnabled && !apiEnabled) {
            throw new IllegalStateException(OUTBOX_WITHOUT_TICKET_HANDLER);
        }
        if (production && demoDataEnabled) {
            throw new IllegalStateException(DEMO_DATA_IN_PROD);
        }
        if (requireCorsOrigins && !hasConfiguredCorsOrigin(environment)) {
            throw new IllegalStateException(CORS_ORIGINS_REQUIRED);
        }
        if (!production) {
            return;
        }

        String provider = environment.getProperty("blue-bus.payments.default-provider", "UNCONFIGURED");
        if (provider == null || provider.isBlank() || "UNCONFIGURED".equalsIgnoreCase(provider.trim())) {
            throw new IllegalStateException(PAYMENTS_UNCONFIGURED_IN_PROD);
        }
        if ("RAZORPAY".equalsIgnoreCase(provider.trim()) && !razorpayConfigured(environment)) {
            throw new IllegalStateException(RAZORPAY_SECRETS_MISSING_IN_PROD);
        }
        if (!"RAZORPAY".equalsIgnoreCase(provider.trim())) {
            throw new IllegalStateException(PAYMENTS_UNCONFIGURED_IN_PROD);
        }
    }

    private static boolean razorpayConfigured(Environment environment) {
        return hasText(environment.getProperty("blue-bus.payments.razorpay.key-id"))
                && hasText(environment.getProperty("blue-bus.payments.razorpay.key-secret"))
                && hasText(environment.getProperty("blue-bus.payments.razorpay.webhook-secret"));
    }

    private static boolean hasConfiguredCorsOrigin(Environment environment) {
        String listed = environment.getProperty("blue-bus.cors.allowed-origins[0]");
        if (hasText(listed)) {
            return true;
        }
        String csv = environment.getProperty("blue-bus.cors.allowed-origins");
        return hasText(csv);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
