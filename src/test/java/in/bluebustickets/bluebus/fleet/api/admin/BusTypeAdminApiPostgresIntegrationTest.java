package in.bluebustickets.bluebus.fleet.api.admin;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
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
@WithMockUser
class BusTypeAdminApiPostgresIntegrationTest {

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

    @Test
    void createGetUpdateListActivateDeactivateAndRejectConflicts() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/admin/bus-types")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"AC_SLEEPER","displayName":"AC Sleeper"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("AC_SLEEPER"))
                .andExpect(jsonPath("$.displayName").value("AC Sleeper"))
                .andExpect(jsonPath("$.active").value(true))
                .andReturn();

        UUID id = UUID.fromString(objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText());

        mockMvc.perform(get("/api/v1/admin/bus-types/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("AC_SLEEPER"));

        mockMvc.perform(put("/api/v1/admin/bus-types/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"displayName":"Premium AC Sleeper"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Premium AC Sleeper"));

        mockMvc.perform(get("/api/v1/admin/bus-types"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code=='AC_SLEEPER')]").exists());

        mockMvc.perform(post("/api/v1/admin/bus-types/{id}/deactivate", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        mockMvc.perform(get("/api/v1/admin/bus-types").param("active", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='%s')]".formatted(id)).exists());

        mockMvc.perform(post("/api/v1/admin/bus-types/{id}/activate", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));

        mockMvc.perform(post("/api/v1/admin/bus-types")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"ac_sleeper","displayName":"Duplicate"}
                                """))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/v1/admin/bus-types")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"","displayName":"Missing code"}
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/v1/admin/bus-types/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }
}
