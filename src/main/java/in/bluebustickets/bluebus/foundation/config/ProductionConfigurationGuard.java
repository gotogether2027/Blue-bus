package in.bluebustickets.bluebus.foundation.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Fail-fast production configuration checks. Local and {@code test} remain usable
 * with an unconfigured payment provider and optional demo data.
 * <p>
 * Production is active when Spring profile {@code prod} is active <em>or</em>
 * {@code blue-bus.environment=production} ({@code BLUE_BUS_ENVIRONMENT}).
 * The default environment is {@code local}; omitting the prod profile is not treated
 * as production.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ProductionConfigurationGuard implements InitializingBean {

    public static final String PROD_PROFILE = "prod";
    public static final String ENVIRONMENT_PROPERTY = "blue-bus.environment";
    public static final String PRODUCTION_ENVIRONMENT = "production";
    public static final String DEFAULT_ENVIRONMENT = "local";

    public static final String DEMO_DATA_IN_PROD =
            "blue-bus.demo-data.enabled cannot be true when production is active.";
    public static final String API_DISABLED_IN_PROD =
            "blue-bus.admin-master-data.enabled cannot be false when production is active. "
                    + "That flag gates the core business API, not only admin master data.";
    public static final String OUTBOX_WITHOUT_TICKET_HANDLER =
            "blue-bus.outbox.processor cannot be enabled while blue-bus.admin-master-data.enabled=false "
                    + "because BOOKING_CONFIRMED ticket handling would be missing.";
    public static final String PAYMENTS_UNCONFIGURED_IN_PROD =
            "PAYMENT_PROVIDER must be RAZORPAY when production is active.";
    public static final String RAZORPAY_SECRETS_MISSING_IN_PROD =
            "Razorpay is the configured payment provider but required credentials are missing.";
    public static final String CORS_ORIGINS_REQUIRED =
            "blue-bus.cors.require-allowed-origins=true requires at least one explicit allowed origin. "
                    + "Same-origin /api/v1 deployments should leave this false and the allow-list empty.";
    public static final String DEMO_DATA_WITH_RAZORPAY =
            "blue-bus.demo-data.enabled cannot be true when PAYMENT_PROVIDER is RAZORPAY.";

    private final Environment environment;

    public ProductionConfigurationGuard(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        validate(environment);
    }

    public static boolean isProduction(Environment environment) {
        if (environment.acceptsProfiles(Profiles.of(PROD_PROFILE))) {
            return true;
        }
        String marker = environment.getProperty(ENVIRONMENT_PROPERTY);
        if (marker == null || marker.isBlank()) {
            return false;
        }
        return PRODUCTION_ENVIRONMENT.equalsIgnoreCase(marker.trim());
    }

    static void validate(Environment environment) {
        boolean production = isProduction(environment);
        boolean apiEnabled = environment.getProperty(
                "blue-bus.admin-master-data.enabled", Boolean.class, Boolean.TRUE);
        boolean outboxEnabled = environment.getProperty("blue-bus.outbox.enabled", Boolean.class, Boolean.TRUE);
        boolean outboxProcessorEnabled = environment.getProperty(
                "blue-bus.outbox.processor.enabled", Boolean.class, Boolean.TRUE);
        boolean demoDataEnabled =
                environment.getProperty("blue-bus.demo-data.enabled", Boolean.class, Boolean.FALSE);
        boolean requireCorsOrigins = environment.getProperty(
                "blue-bus.cors.require-allowed-origins", Boolean.class, Boolean.FALSE);
        String provider = paymentProvider(environment);

        if (production && !apiEnabled) {
            throw new IllegalStateException(API_DISABLED_IN_PROD);
        }
        if (outboxEnabled && outboxProcessorEnabled && !apiEnabled) {
            throw new IllegalStateException(OUTBOX_WITHOUT_TICKET_HANDLER);
        }
        if (production && demoDataEnabled) {
            throw new IllegalStateException(DEMO_DATA_IN_PROD);
        }
        if (demoDataEnabled && isRazorpay(provider)) {
            throw new IllegalStateException(DEMO_DATA_WITH_RAZORPAY);
        }
        if (requireCorsOrigins && !hasConfiguredCorsOrigin(environment)) {
            throw new IllegalStateException(CORS_ORIGINS_REQUIRED);
        }
        if (!production) {
            return;
        }

        if (provider.isEmpty() || "UNCONFIGURED".equalsIgnoreCase(provider)) {
            throw new IllegalStateException(PAYMENTS_UNCONFIGURED_IN_PROD);
        }
        if (isRazorpay(provider) && !razorpayConfigured(environment)) {
            throw new IllegalStateException(RAZORPAY_SECRETS_MISSING_IN_PROD);
        }
        if (!isRazorpay(provider)) {
            throw new IllegalStateException(PAYMENTS_UNCONFIGURED_IN_PROD);
        }
    }

    private static String paymentProvider(Environment environment) {
        String provider = environment.getProperty("blue-bus.payments.default-provider", "UNCONFIGURED");
        return provider == null ? "" : provider.trim();
    }

    private static boolean isRazorpay(String provider) {
        return "RAZORPAY".equalsIgnoreCase(provider);
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
