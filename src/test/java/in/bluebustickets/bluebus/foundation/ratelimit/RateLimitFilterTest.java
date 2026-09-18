package in.bluebustickets.bluebus.foundation.ratelimit;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import in.bluebustickets.bluebus.foundation.api.error.ApiErrorResponseWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitFilterTest {

    private RateLimitFilter filter;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        RateLimitProperties properties = new RateLimitProperties();
        properties.getAuth().setLimit(2);
        properties.getAuth().setWindowSeconds(60);
        properties.getPublicApi().setLimit(2);
        properties.getPublicApi().setWindowSeconds(60);
        properties.getHolds().setLimit(2);
        properties.getHolds().setWindowSeconds(60);
        properties.getWebhook().setLimit(2);
        properties.getWebhook().setWindowSeconds(60);
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        filter = new RateLimitFilter(
                new InMemoryRateLimiter(properties, Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)),
                new ApiErrorResponseWriter(objectMapper));
    }

    @Test
    void underLimitLoginStillReachesTheFilterChain() throws Exception {
        MockFilterChain first = new MockFilterChain();
        filter.doFilter(login("10.0.0.1", "secret-password"), new MockHttpServletResponse(), first);
        MockFilterChain second = new MockFilterChain();
        filter.doFilter(login("10.0.0.1", "secret-password"), new MockHttpServletResponse(), second);

        assertThat(first.getRequest()).isNotNull();
        assertThat(second.getRequest()).isNotNull();
    }

    @Test
    void exceededLimitDoesNotInvokeTheChainAndHidesSensitiveData() throws Exception {
        filter.doFilter(login("10.0.0.8", "secret-password"), new MockHttpServletResponse(), new MockFilterChain());
        filter.doFilter(login("10.0.0.8", "secret-password"), new MockHttpServletResponse(), new MockFilterChain());

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletRequest request = login("10.0.0.8", "secret-password");
        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNull();
        assertThat(response.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(response.getHeader(RateLimitFilter.RETRY_AFTER)).isEqualTo("60");
        String body = response.getContentAsString();
        JsonNode json = objectMapper.readTree(body);
        assertThat(json.get("status").intValue()).isEqualTo(429);
        assertThat(json.get("error").asText()).isEqualTo("Too Many Requests");
        assertThat(json.get("message").asText()).isEqualTo(RateLimitFilter.TOO_MANY_REQUESTS_MESSAGE);
        assertThat(json.get("path").asText()).isEqualTo("/api/v1/auth/login");
        assertThat(body).doesNotContain("secret-password");
        assertThat(body).doesNotContain("exists@example.test");
        assertThat(body).doesNotContain("whsec");
        assertThat(body).doesNotContain("Bearer");
    }

    @Test
    void unmatchedHealthAndAuthenticatedPathsAreNotCounted() throws Exception {
        for (int i = 0; i < 5; i++) {
            MockFilterChain chain = new MockFilterChain();
            MockHttpServletRequest health = new MockHttpServletRequest("GET", "/api/v1/health");
            health.setRemoteAddr("10.0.0.3");
            health.setServletPath("/api/v1/health");
            filter.doFilter(health, new MockHttpServletResponse(), chain);
            assertThat(chain.getRequest()).isNotNull();

            MockFilterChain meChain = new MockFilterChain();
            MockHttpServletRequest me = new MockHttpServletRequest("GET", "/api/v1/auth/me");
            me.setRemoteAddr("10.0.0.3");
            me.setServletPath("/api/v1/auth/me");
            me.addHeader("Authorization", "Bearer test-token");
            filter.doFilter(me, new MockHttpServletResponse(), meChain);
            assertThat(meChain.getRequest()).isNotNull();
        }
    }

    private static MockHttpServletRequest login(String ip, String password) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setServletPath("/api/v1/auth/login");
        request.setRemoteAddr(ip);
        request.setContentType("application/json");
        request.setContent(("{\"email\":\"exists@example.test\",\"password\":\"" + password + "\"}").getBytes());
        return request;
    }
}
