package in.bluebustickets.bluebus.payments.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Provider selection only. Provider credentials belong to provider-specific configuration and
 * environment-backed secrets, never this shared contract.
 */
@ConfigurationProperties(prefix = "blue-bus.payments")
public class PaymentProperties {

    private String defaultProvider = "UNCONFIGURED";

    public String getDefaultProvider() {
        return defaultProvider;
    }

    public void setDefaultProvider(String defaultProvider) {
        if (defaultProvider == null || defaultProvider.isBlank()) {
            throw new IllegalArgumentException("payments.default-provider is required");
        }
        this.defaultProvider = defaultProvider.trim().toUpperCase();
    }
}
