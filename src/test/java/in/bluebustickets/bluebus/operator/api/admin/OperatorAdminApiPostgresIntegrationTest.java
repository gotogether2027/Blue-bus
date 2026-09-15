package in.bluebustickets.bluebus.operator.api.admin;

import java.util.UUID;

import in.bluebustickets.bluebus.foundation.security.TestAccessTokenFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.ObjectMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OperatorAdminApiPostgresIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TestAccessTokenFactory testAccessTokenFactory;
    private String adminToken;

    @BeforeEach
    void seedPlatformAdmin() {
        adminToken = testAccessTokenFactory.issuePlatformAdmin().accessToken();
    }

    @Test
    void createGetUpdateListActivateDeactivateAndRejectInvalidInput() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/admin/operators")
                        .with(adminAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "legalName":"Fictional Blue Travels Pvt Ltd",
                                  "displayName":"Blue Travels",
                                  "supportEmail":"ops@example.test",
                                  "supportPhoneE164":"+919900000001"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.legalName").value("Fictional Blue Travels Pvt Ltd"))
                .andExpect(jsonPath("$.displayName").value("Blue Travels"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn();

        UUID id = UUID.fromString(objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText());

        mockMvc.perform(get("/api/v1/admin/operators/{id}", id).with(adminAuth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.supportEmail").value("ops@example.test"));

        mockMvc.perform(put("/api/v1/admin/operators/{id}", id)
                        .with(adminAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "legalName":"Fictional Blue Travels Pvt Ltd",
                                  "displayName":"Blue Travels Express",
                                  "supportEmail":"support@example.test",
                                  "supportPhoneE164":"+919900000002"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Blue Travels Express"));

        mockMvc.perform(get("/api/v1/admin/operators").with(adminAuth()).param("status", "PENDING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='%s')]".formatted(id)).exists());

        mockMvc.perform(post("/api/v1/admin/operators/{id}/activate", id).with(adminAuth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        mockMvc.perform(post("/api/v1/admin/operators/{id}/deactivate", id).with(adminAuth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INACTIVE"));

        mockMvc.perform(post("/api/v1/admin/operators/{id}/activate", id).with(adminAuth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        mockMvc.perform(post("/api/v1/admin/operators")
                        .with(adminAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"legalName":"","displayName":"Missing legal"}
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/admin/operators")
                        .with(adminAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "legalName":"Bad Phone Co",
                                  "displayName":"Bad Phone",
                                  "supportPhoneE164":"9900000001"
                                }
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/v1/admin/operators").with(adminAuth()).param("status", "NOT_A_STATUS"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/v1/admin/operators/{id}", UUID.randomUUID()).with(adminAuth()))
                .andExpect(status().isNotFound());
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor adminAuth() {
        return TestAccessTokenFactory.bearer(adminToken);
    }
}
