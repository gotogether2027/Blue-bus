package in.bluebustickets.bluebus.scheduling.api.admin;

import java.util.UUID;

import in.bluebustickets.bluebus.foundation.security.TestAccessTokenFactory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LocationAdminApiPostgresIntegrationTest {

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
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TestAccessTokenFactory testAccessTokenFactory;
    private String adminToken;

    @BeforeEach
    void seedPlatformAdmin() {
        adminToken = testAccessTokenFactory.issuePlatformAdmin().accessToken();
    }

    @Test
    void createGetUpdateSearchActivateDeactivateAndPersistChar2CountryCode() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/admin/locations")
                        .with(adminAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "countryCode":"IN",
                                  "state":"Telangana",
                                  "district":"Hyderabad",
                                  "city":"Hyderabad",
                                  "locality":"Miyapur",
                                  "latitude":17.496700,
                                  "longitude":78.391700,
                                  "timeZone":"Asia/Kolkata"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.countryCode").value("IN"))
                .andExpect(jsonPath("$.city").value("Hyderabad"))
                .andExpect(jsonPath("$.active").value(true))
                .andReturn();

        UUID id = UUID.fromString(objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText());

        String storedCountryCode = jdbcTemplate.queryForObject(
                "SELECT country_code FROM locations WHERE id = ?",
                String.class,
                id);
        assertThat(storedCountryCode).isEqualTo("IN");
        Integer storedLength = jdbcTemplate.queryForObject(
                "SELECT char_length(country_code) FROM locations WHERE id = ?",
                Integer.class,
                id);
        assertThat(storedLength).isEqualTo(2);

        mockMvc.perform(get("/api/v1/admin/locations/{id}", id).with(adminAuth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("Telangana"));

        mockMvc.perform(put("/api/v1/admin/locations/{id}", id)
                        .with(adminAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "countryCode":"IN",
                                  "state":"Telangana",
                                  "city":"Secunderabad",
                                  "timeZone":"Asia/Kolkata"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.city").value("Secunderabad"));

        mockMvc.perform(get("/api/v1/admin/locations")
                        .with(adminAuth())
                        .param("state", "Telangana")
                        .param("city", "Secunderabad"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id.toString()));

        mockMvc.perform(post("/api/v1/admin/locations/{id}/deactivate", id).with(adminAuth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        mockMvc.perform(get("/api/v1/admin/locations").with(adminAuth()).param("active", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='%s')]".formatted(id)).exists());

        mockMvc.perform(post("/api/v1/admin/locations/{id}/activate", id).with(adminAuth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));

        mockMvc.perform(post("/api/v1/admin/locations")
                        .with(adminAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"countryCode":"IND","state":"Telangana","city":"Warangal"}
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/admin/locations")
                        .with(adminAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"countryCode":"IN","state":"","city":"Warangal"}
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/v1/admin/locations/{id}", UUID.randomUUID()).with(adminAuth()))
                .andExpect(status().isNotFound());
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor adminAuth() {
        return TestAccessTokenFactory.bearer(adminToken);
    }
}
