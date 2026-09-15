package in.bluebustickets.bluebus.fleet.api.admin;

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

import com.fasterxml.jackson.databind.JsonNode;
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
class SeatLayoutAdminApiPostgresIntegrationTest {

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
    void createListGetUpdateActivateDeactivateWithSeatsAndRejectInvalidData() throws Exception {
        UUID operatorId = createOperator("Seat Layout Operator Pvt Ltd", "Seat Layout Co");

        MvcResult created = mockMvc.perform(post("/api/v1/admin/seat-layouts")
                        .with(adminAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "operatorId":"%s",
                                  "name":"2x2 Sleeper Layout",
                                  "version":1,
                                  "deckCount":1,
                                  "rowCount":2,
                                  "columnCount":2,
                                  "seats":[
                                    {"seatNumber":"L1","deckNumber":1,"rowNumber":1,"columnNumber":1,"seatType":"SLEEPER"},
                                    {"seatNumber":"L2","deckNumber":1,"rowNumber":1,"columnNumber":2,"seatType":"SLEEPER","sellable":false},
                                    {"seatNumber":"L3","deckNumber":1,"rowNumber":2,"columnNumber":1,"seatType":"SLEEPER"},
                                    {"seatNumber":"L4","deckNumber":1,"rowNumber":2,"columnNumber":2,"seatType":"SLEEPER"}
                                  ]
                                }
                                """.formatted(operatorId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("2x2 Sleeper Layout"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.seats.length()").value(4))
                .andExpect(jsonPath("$.seats[1].seatNumber").value("L2"))
                .andExpect(jsonPath("$.seats[1].sellable").value(false))
                .andReturn();

        JsonNode body = objectMapper.readTree(created.getResponse().getContentAsString());
        UUID layoutId = UUID.fromString(body.get("id").asText());

        Integer seatCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM seats WHERE seat_layout_id = ?",
                Integer.class,
                layoutId);
        assertThat(seatCount).isEqualTo(4);

        mockMvc.perform(get("/api/v1/admin/seat-layouts/{id}", layoutId).with(adminAuth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operatorId").value(operatorId.toString()))
                .andExpect(jsonPath("$.seats.length()").value(4));

        mockMvc.perform(get("/api/v1/admin/seat-layouts")
                        .with(adminAuth())
                        .param("operatorId", operatorId.toString())
                        .param("status", "DRAFT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='%s')]".formatted(layoutId)).exists());

        mockMvc.perform(put("/api/v1/admin/seat-layouts/{id}", layoutId)
                        .with(adminAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":"2x2 Premium Sleeper",
                                  "deckCount":1,
                                  "rowCount":2,
                                  "columnCount":2
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("2x2 Premium Sleeper"))
                .andExpect(jsonPath("$.seats.length()").value(4));

        mockMvc.perform(post("/api/v1/admin/seat-layouts/{id}/activate", layoutId).with(adminAuth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"));

        mockMvc.perform(post("/api/v1/admin/seat-layouts/{id}/deactivate", layoutId).with(adminAuth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARCHIVED"));

        mockMvc.perform(put("/api/v1/admin/seat-layouts/{id}", layoutId)
                        .with(adminAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":"Should Fail",
                                  "deckCount":1,
                                  "rowCount":2,
                                  "columnCount":2
                                }
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/admin/seat-layouts/{id}/activate", layoutId).with(adminAuth()))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/admin/seat-layouts")
                        .with(adminAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "operatorId":"%s",
                                  "name":"2x2 Premium Sleeper",
                                  "version":1,
                                  "deckCount":1,
                                  "rowCount":1,
                                  "columnCount":1,
                                  "seats":[{"seatNumber":"A1","deckNumber":1,"rowNumber":1,"columnNumber":1,"seatType":"SEATER"}]
                                }
                                """.formatted(operatorId)))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/v1/admin/seat-layouts")
                        .with(adminAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "operatorId":"%s",
                                  "name":"Bad Seats",
                                  "version":2,
                                  "deckCount":1,
                                  "rowCount":1,
                                  "columnCount":1,
                                  "seats":[
                                    {"seatNumber":"A1","deckNumber":1,"rowNumber":1,"columnNumber":1,"seatType":"SEATER"},
                                    {"seatNumber":"A1","deckNumber":1,"rowNumber":1,"columnNumber":1,"seatType":"SEATER"}
                                  ]
                                }
                                """.formatted(operatorId)))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/v1/admin/seat-layouts")
                        .with(adminAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "operatorId":"%s",
                                  "name":"Out Of Bounds",
                                  "version":3,
                                  "deckCount":1,
                                  "rowCount":1,
                                  "columnCount":1,
                                  "seats":[{"seatNumber":"Z9","deckNumber":1,"rowNumber":2,"columnNumber":1,"seatType":"SEATER"}]
                                }
                                """.formatted(operatorId)))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/v1/admin/seat-layouts/{id}", UUID.randomUUID()).with(adminAuth()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Seat layout was not found."));
    }

    private UUID createOperator(String legalName, String displayName) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/admin/operators")
                        .with(adminAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"legalName":"%s","displayName":"%s"}
                                """.formatted(legalName, displayName)))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor adminAuth() {
        return TestAccessTokenFactory.bearer(adminToken);
    }
}
