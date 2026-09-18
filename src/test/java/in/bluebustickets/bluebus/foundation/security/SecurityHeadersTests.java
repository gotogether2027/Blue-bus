package in.bluebustickets.bluebus.foundation.security;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
        "blue-bus.admin-master-data.enabled=false",
        "blue-bus.outbox.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityHeadersTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void publicApiResponseIncludesExplicitSecurityHeaders() throws Exception {
        mockMvc.perform(get("/api/v1/health").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(explicitSecurityHeaders())
                .andExpect(header().doesNotExist("Content-Security-Policy"))
                .andExpect(header().doesNotExist("Content-Security-Policy-Report-Only"))
                .andExpect(header().doesNotExist("Strict-Transport-Security"));
    }

    @Test
    void unauthorizedApiErrorIncludesExplicitSecurityHeaders() throws Exception {
        mockMvc.perform(get("/api/v1/internal-placeholder").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(explicitSecurityHeaders())
                .andExpect(header().doesNotExist("Content-Security-Policy"))
                .andExpect(header().doesNotExist("Strict-Transport-Security"));
    }

    @Test
    @WithMockUser(roles = "CUSTOMER")
    void forbiddenApiErrorIncludesExplicitSecurityHeaders() throws Exception {
        mockMvc.perform(get("/api/v1/security-header-probe").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(explicitSecurityHeaders())
                .andExpect(header().doesNotExist("Content-Security-Policy"))
                .andExpect(header().doesNotExist("Strict-Transport-Security"));
    }

    @Test
    void csrfRemainsDisabledForPublicApiPosts() throws Exception {
        mockMvc.perform(post("/api/v1/health").accept(MediaType.APPLICATION_JSON))
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                        .isNotEqualTo(HttpStatus.FORBIDDEN.value()));
    }

    @Test
    void configuredCorsAllowListStillApplies() throws Exception {
        mockMvc.perform(get("/api/v1/health")
                        .accept(MediaType.APPLICATION_JSON)
                        .header("Origin", "http://localhost:4200"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:4200"))
                .andExpect(explicitSecurityHeaders());
    }

    static ResultMatcher explicitSecurityHeaders() {
        return result -> {
            header().string("X-Content-Type-Options", "nosniff").match(result);
            header().string("X-Frame-Options", "DENY").match(result);
            header().string("Cache-Control", containsString("no-cache")).match(result);
            header().string("Cache-Control", containsString("no-store")).match(result);
            header().string("Cache-Control", containsString("max-age=0")).match(result);
            header().string("Cache-Control", containsString("must-revalidate")).match(result);
            header().string("Pragma", "no-cache").match(result);
            header().string("Expires", "0").match(result);
            header().string("Referrer-Policy", "strict-origin-when-cross-origin").match(result);
        };
    }

    @TestConfiguration
    static class ForbiddenProbeConfiguration {
        @RestController
        static class ForbiddenProbeController {
            @GetMapping("/api/v1/security-header-probe")
            @PreAuthorize("hasRole('ADMIN')")
            public Map<String, String> probe() {
                return Map.of("ok", "true");
            }
        }
    }
}
