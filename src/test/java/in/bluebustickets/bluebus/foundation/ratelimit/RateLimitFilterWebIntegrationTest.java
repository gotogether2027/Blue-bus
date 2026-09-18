package in.bluebustickets.bluebus.foundation.ratelimit;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
        "blue-bus.admin-master-data.enabled=false",
        "blue-bus.outbox.enabled=false",
        "blue-bus.rate-limit.enabled=true",
        "blue-bus.rate-limit.auth.limit=2",
        "blue-bus.rate-limit.auth.window-seconds=60",
        "blue-bus.rate-limit.public-api.limit=2",
        "blue-bus.rate-limit.public-api.window-seconds=60",
        "blue-bus.rate-limit.holds.limit=2",
        "blue-bus.rate-limit.holds.window-seconds=60",
        "blue-bus.rate-limit.webhook.limit=2",
        "blue-bus.rate-limit.webhook.window-seconds=60"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RateLimitFilterWebIntegrationTest {

    private static final AtomicInteger IP_SEQUENCE = new AtomicInteger(1);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RateLimitProperties rateLimitProperties;

    @Test
    void springPropertiesOverrideTheTestProfileLimits() {
        assertThat(rateLimitProperties.isEnabled()).isTrue();
        assertThat(rateLimitProperties.getAuth().getLimit()).isEqualTo(2);
        assertThat(rateLimitProperties.getPublicApi().getLimit()).isEqualTo(2);
        assertThat(rateLimitProperties.getHolds().getLimit()).isEqualTo(2);
        assertThat(rateLimitProperties.getWebhook().getLimit()).isEqualTo(2);
        assertThat(rateLimitProperties.getAuth().getWindowSeconds()).isEqualTo(60);
    }

    @Test
    void loginRegisterAndRefreshAreProtected() throws Exception {
        String ip = nextIp();
        login(ip, "exists@example.test", "super-secret-password").andExpect(status().isOk());
        login(ip, "exists@example.test", "super-secret-password").andExpect(status().isOk());
        login(ip, "exists@example.test", "super-secret-password")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", matchesPattern("[1-9][0-9]*")))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.error").value("Too Many Requests"))
                .andExpect(jsonPath("$.message").value(RateLimitFilter.TOO_MANY_REQUESTS_MESSAGE))
                .andExpect(jsonPath("$.path").value("/api/v1/auth/login"))
                .andExpect(jsonPath("$.fieldViolations").isEmpty())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"))
                .andExpect(header().doesNotExist("Content-Security-Policy"))
                .andExpect(header().doesNotExist("Strict-Transport-Security"))
                .andExpect(content().string(not(containsString("super-secret-password"))))
                .andExpect(content().string(not(containsString("exists@example.test"))));

        String registerIp = nextIp();
        register(registerIp).andExpect(status().isOk());
        register(registerIp).andExpect(status().isOk());
        register(registerIp).andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));

        String refreshIp = nextIp();
        refresh(refreshIp).andExpect(status().isOk());
        refresh(refreshIp).andExpect(status().isOk());
        refresh(refreshIp).andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
    }

    @Test
    void differentClientIpsHaveIndependentBuckets() throws Exception {
        String first = nextIp();
        String second = nextIp();
        login(first, "a@example.test", "password-one").andExpect(status().isOk());
        login(first, "a@example.test", "password-one").andExpect(status().isOk());
        login(first, "a@example.test", "password-one").andExpect(status().isTooManyRequests());
        login(second, "b@example.test", "password-two").andExpect(status().isOk());
    }

    @Test
    void forwardedHeadersDoNotCreateASeparateBucket() throws Exception {
        String ip = nextIp();
        login(ip, "a@example.test", "pw").andExpect(status().isOk());
        login(ip, "a@example.test", "pw").andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/auth/login")
                        .with(remoteAddr(ip))
                        .header("X-Forwarded-For", "198.51.100.20")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@example.test\",\"password\":\"pw\"}"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void searchLocationsAndAvailabilityAreProtected() throws Exception {
        String ip = nextIp();
        mockMvc.perform(get("/api/v1/search/trips")
                        .with(remoteAddr(ip))
                        .param("originLocationId", UUID.randomUUID().toString())
                        .param("destinationLocationId", UUID.randomUUID().toString())
                        .param("serviceDate", "2026-09-18"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/search/trips")
                        .with(remoteAddr(ip))
                        .param("originLocationId", UUID.randomUUID().toString())
                        .param("destinationLocationId", UUID.randomUUID().toString())
                        .param("serviceDate", "2026-09-18"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/search/trips")
                        .with(remoteAddr(ip))
                        .param("originLocationId", UUID.randomUUID().toString())
                        .param("destinationLocationId", UUID.randomUUID().toString())
                        .param("serviceDate", "2026-09-18"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));

        String locationsIp = nextIp();
        mockMvc.perform(get("/api/v1/locations").with(remoteAddr(locationsIp))).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/locations").with(remoteAddr(locationsIp))).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/locations").with(remoteAddr(locationsIp)))
                .andExpect(status().isTooManyRequests());

        String availabilityIp = nextIp();
        String availabilityPath = "/api/v1/trips/" + UUID.randomUUID() + "/seat-availability";
        mockMvc.perform(get(availabilityPath).with(remoteAddr(availabilityIp)).param("originStopId", "1")
                        .param("destinationStopId", "2"))
                .andExpect(status().isOk());
        mockMvc.perform(get(availabilityPath).with(remoteAddr(availabilityIp)).param("originStopId", "1")
                        .param("destinationStopId", "2"))
                .andExpect(status().isOk());
        mockMvc.perform(get(availabilityPath).with(remoteAddr(availabilityIp)).param("originStopId", "1")
                        .param("destinationStopId", "2"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void holdCreateAndCancelAreProtectedWhileHoldReadsAreNot() throws Exception {
        String ip = nextIp();
        UUID tripId = UUID.randomUUID();
        mockMvc.perform(post("/api/v1/trips/" + tripId + "/holds")
                        .with(remoteAddr(ip))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/trips/" + tripId + "/holds")
                        .with(remoteAddr(ip))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/trips/" + tripId + "/holds")
                        .with(remoteAddr(ip))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));

        String cancelIp = nextIp();
        UUID holdId = UUID.randomUUID();
        mockMvc.perform(delete("/api/v1/holds/" + holdId).with(remoteAddr(cancelIp))).andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/v1/holds/" + holdId).with(remoteAddr(cancelIp))).andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/v1/holds/" + holdId).with(remoteAddr(cancelIp)))
                .andExpect(status().isTooManyRequests());

        String readIp = nextIp();
        for (int i = 0; i < 4; i++) {
            mockMvc.perform(get("/api/v1/holds/" + holdId).with(remoteAddr(readIp)))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void razorpayWebhookIsProtectedWithoutReplacingSignatureChecks() throws Exception {
        String ip = nextIp();
        String body = "{\"event\":\"payment.captured\",\"payload\":{\"secret\":\"whsec-not-real\"}}";
        webhook(ip, body).andExpect(status().isOk());
        webhook(ip, body).andExpect(status().isOk());
        webhook(ip, body)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.message").value(RateLimitFilter.TOO_MANY_REQUESTS_MESSAGE))
                .andExpect(content().string(not(containsString("whsec-not-real"))));
    }

    @Test
    void healthAndAuthenticatedBusinessApisAreNotGloballyLimited() throws Exception {
        String ip = nextIp();
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(get("/api/v1/health").with(remoteAddr(ip)).accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("UP"));
            mockMvc.perform(get("/api/v1/auth/me").with(remoteAddr(ip)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.status").value(401));
            mockMvc.perform(get("/index.html").with(remoteAddr(ip))).andExpect(status().isUnauthorized());
            mockMvc.perform(post("/api/v1/auth/logout")
                            .with(remoteAddr(ip))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"refreshToken\":\"opaque-refresh\"}"))
                    .andExpect(status().isNoContent());
        }
    }

    private ResultActions login(String ip, String email, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                .with(remoteAddr(ip))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"));
    }

    private ResultActions register(String ip) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/register")
                .with(remoteAddr(ip))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"new@example.test","password":"NewPass123","firstName":"New","lastName":"User",\
                        "phone":"+919911100099"}
                        """));
    }

    private ResultActions refresh(String ip) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/refresh")
                .with(remoteAddr(ip))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"opaque-refresh-token\"}"));
    }

    private ResultActions webhook(String ip, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/payments/webhooks/RAZORPAY")
                .with(remoteAddr(ip))
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Razorpay-Signature", "not-a-real-signature")
                .content(body));
    }

    private static RequestPostProcessor remoteAddr(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    private static String nextIp() {
        int n = IP_SEQUENCE.getAndIncrement();
        return "203.0.113." + n;
    }

    @TestConfiguration
    static class StubPublicEndpoints {
        @RestController
        static class Endpoints {
            @PostMapping("/api/v1/auth/login")
            ResponseEntity<Void> login() {
                return ResponseEntity.ok().build();
            }

            @PostMapping("/api/v1/auth/register")
            ResponseEntity<Void> register() {
                return ResponseEntity.ok().build();
            }

            @PostMapping("/api/v1/auth/refresh")
            ResponseEntity<Void> refresh() {
                return ResponseEntity.ok().build();
            }

            @PostMapping("/api/v1/auth/logout")
            ResponseEntity<Void> logout() {
                return ResponseEntity.noContent().build();
            }

            @GetMapping("/api/v1/search/trips")
            ResponseEntity<Void> search() {
                return ResponseEntity.ok().build();
            }

            @GetMapping("/api/v1/locations")
            ResponseEntity<Void> locations() {
                return ResponseEntity.ok().build();
            }

            @GetMapping("/api/v1/trips/{tripId}/seat-availability")
            ResponseEntity<Void> availability(@PathVariable String tripId) {
                return ResponseEntity.ok().build();
            }

            @PostMapping("/api/v1/trips/{tripId}/holds")
            ResponseEntity<Void> createHold(@PathVariable String tripId) {
                return ResponseEntity.ok().build();
            }

            @GetMapping("/api/v1/holds/{holdId}")
            ResponseEntity<Void> readHold(@PathVariable String holdId) {
                return ResponseEntity.ok().build();
            }

            @DeleteMapping("/api/v1/holds/{holdId}")
            ResponseEntity<Void> cancelHold(@PathVariable String holdId) {
                return ResponseEntity.noContent().build();
            }

            @PostMapping("/api/v1/payments/webhooks/{provider}")
            ResponseEntity<Void> webhook(@PathVariable String provider) {
                return ResponseEntity.ok().build();
            }
        }
    }
}
