package in.bluebustickets.bluebus.foundation.security;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CorsPropertiesTest {

    @Test
    void emptyAllowListIsTheFailClosedDefault() {
        CorsProperties properties = new CorsProperties();
        assertThat(properties.getAllowedOrigins()).isEmpty();
        assertThat(properties.isRequireAllowedOrigins()).isFalse();
    }

    @Test
    void rejectsWildcardOrigins() {
        CorsProperties properties = new CorsProperties();
        assertThatThrownBy(() -> properties.setAllowedOrigins(List.of("*")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties.setAllowedOrigins(List.of("https://*.example.com")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requireAllowedOriginsDefaultsFalseAndCanBeEnabled() {
        CorsProperties properties = new CorsProperties();
        assertThat(properties.isRequireAllowedOrigins()).isFalse();
        properties.setRequireAllowedOrigins(true);
        assertThat(properties.isRequireAllowedOrigins()).isTrue();
    }

    @Test
    void trimsAndCopiesConfiguredOrigins() {
        CorsProperties properties = new CorsProperties();
        properties.setAllowedOrigins(List.of(" http://localhost:4200 ", "", "https://app.bluebustickets.in"));
        assertThat(properties.getAllowedOrigins())
                .containsExactly("http://localhost:4200", "https://app.bluebustickets.in");
    }
}
