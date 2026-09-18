package in.bluebustickets.bluebus.foundation.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class ClientIpResolverTest {

    @Test
    void usesServletRemoteAddress() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.10");

        assertThat(ClientIpResolver.resolve(request)).isEqualTo("203.0.113.10");
    }

    @Test
    void ignoresForwardedHeadersWhenNoTrustedProxyExists() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.10");
        request.addHeader("X-Forwarded-For", "198.51.100.1, 192.0.2.1");
        request.addHeader("Forwarded", "for=198.51.100.1");
        request.addHeader("X-Real-IP", "198.51.100.1");

        assertThat(ClientIpResolver.resolve(request)).isEqualTo("203.0.113.10");
    }

    @Test
    void fallsBackWhenRemoteAddressIsMissing() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(" ");

        assertThat(ClientIpResolver.resolve(request)).isEqualTo("unknown");
        assertThat(ClientIpResolver.resolve(null)).isEqualTo("unknown");
    }
}
