package in.bluebustickets.bluebus.payments.provider.razorpay;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RazorpayMoneyAndSignatureTest {

    @Test
    void convertsInrToPaiseWithoutFloatingPoint() {
        assertThat(RazorpayMoney.toMinorUnits(new BigDecimal("850.00"), "INR")).isEqualTo(85000L);
        assertThat(RazorpayMoney.toMinorUnits(new BigDecimal("900.00"), "inr")).isEqualTo(90000L);
        assertThat(RazorpayMoney.fromMinorUnits(85000L, "INR")).isEqualByComparingTo("850.00");
    }

    @Test
    void rejectsNonInrAndExcessScale() {
        assertThatThrownBy(() -> RazorpayMoney.toMinorUnits(new BigDecimal("850.00"), "USD"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RazorpayMoney.toMinorUnits(new BigDecimal("850.001"), "INR"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RazorpayMoney.toMinorUnits(new BigDecimal("-1.00"), "INR"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void checkoutAndWebhookSignaturesUseHmacSha256() {
        String checkoutPayload = "order_abc|pay_xyz";
        String expected = RazorpaySignatures.hmacSha256Hex(checkoutPayload, "key_secret");
        assertThat(RazorpaySignatures.matches(checkoutPayload, "key_secret", expected)).isTrue();
        assertThat(RazorpaySignatures.matches(checkoutPayload, "key_secret", "deadbeef")).isFalse();
        assertThat(RazorpaySignatures.matches(checkoutPayload, "key_secret", expected.toUpperCase())).isTrue();

        byte[] raw = "{\"event\":\"payment.captured\"}".getBytes(StandardCharsets.UTF_8);
        String webhook = RazorpaySignatures.hmacSha256Hex(raw, "whsec");
        assertThat(RazorpaySignatures.matches(raw, "whsec", webhook)).isTrue();
        assertThat(RazorpaySignatures.matches(
                "{\"event\":\"payment.captured\" }".getBytes(StandardCharsets.UTF_8), "whsec", webhook))
                .isFalse();
    }

    @Test
    void propertiesFailClosedAndNeverPrintSecrets() {
        RazorpayProperties properties = new RazorpayProperties();
        assertThatThrownBy(properties::requireConfigured)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Razorpay is the configured payment provider");

        properties.setKeyId("rzp_test_key");
        properties.setKeySecret("super-secret-key");
        properties.setWebhookSecret("super-secret-webhook");
        properties.setBaseUrl("https://api.razorpay.com");
        properties.requireConfigured();

        String rendered = properties.toString();
        assertThat(rendered).doesNotContain("super-secret-key");
        assertThat(rendered).doesNotContain("super-secret-webhook");
        assertThat(rendered).contains("keyIdConfigured=true");
    }
}
