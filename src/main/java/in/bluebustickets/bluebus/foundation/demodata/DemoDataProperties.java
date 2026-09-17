package in.bluebustickets.bluebus.foundation.demodata;

import java.util.regex.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Opt-in local demo catalog. Disabled unless {@code blue-bus.demo-data.enabled=true}.
 * The customer password must come from the environment and is never given a production default.
 */
@ConfigurationProperties(prefix = "blue-bus.demo-data")
public class DemoDataProperties {

    private static final Pattern PASSWORD_PATTERN = Pattern.compile("^(?=.*[A-Za-z])(?=.*\\d).+$");

    /**
     * Bound for completeness. The configuration class is only registered when this is true.
     */
    private boolean enabled = false;

    private String customerEmail = DemoDataCatalog.DEFAULT_CUSTOMER_EMAIL;

    private String customerPassword = "";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getCustomerEmail() {
        return customerEmail;
    }

    public void setCustomerEmail(String customerEmail) {
        this.customerEmail = customerEmail == null ? DemoDataCatalog.DEFAULT_CUSTOMER_EMAIL : customerEmail.trim();
    }

    public String getCustomerPassword() {
        return customerPassword;
    }

    public void setCustomerPassword(String customerPassword) {
        this.customerPassword = customerPassword == null ? "" : customerPassword;
    }

    public String requireCustomerPassword() {
        if (customerPassword.isBlank()) {
            throw new IllegalStateException(
                    "blue-bus.demo-data.enabled=true requires DEMO_CUSTOMER_PASSWORD "
                            + "(8–72 characters, at least one letter and one digit).");
        }
        if (customerPassword.length() < 8 || customerPassword.length() > 72) {
            throw new IllegalStateException("DEMO_CUSTOMER_PASSWORD must be between 8 and 72 characters.");
        }
        if (!PASSWORD_PATTERN.matcher(customerPassword).matches()) {
            throw new IllegalStateException(
                    "DEMO_CUSTOMER_PASSWORD must contain at least one letter and one digit.");
        }
        return customerPassword;
    }

    public String requireCustomerEmail() {
        if (customerEmail == null || customerEmail.isBlank()) {
            throw new IllegalStateException("blue-bus.demo-data.customer-email is required when demo data is enabled.");
        }
        return customerEmail.trim().toLowerCase();
    }
}
