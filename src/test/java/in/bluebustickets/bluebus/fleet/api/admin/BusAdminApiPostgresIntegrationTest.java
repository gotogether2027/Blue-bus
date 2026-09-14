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
class BusAdminApiPostgresIntegrationTest {

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
    void createGetListUpdateActivateDeactivateAndRejectMissingOrDuplicateData() throws Exception {
        UUID operatorId = createOperator("Fleet Bus Operator Pvt Ltd", "Fleet Bus Co");
        UUID busTypeId = createBusType("AC_SEATER", "AC Seater");
        UUID seatLayoutId = createSeatLayout(operatorId, "2x2 Seater Layout", 1);

        MvcResult created = mockMvc.perform(post("/api/v1/admin/buses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "operatorId":"%s",
                                  "busTypeId":"%s",
                                  "seatLayoutId":"%s",
                                  "registrationNumber":"TS09AB1234",
                                  "displayName":"Hyderabad Express 1"
                                }
                                """.formatted(operatorId, busTypeId, seatLayoutId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.operatorId").value(operatorId.toString()))
                .andExpect(jsonPath("$.busTypeId").value(busTypeId.toString()))
                .andExpect(jsonPath("$.seatLayoutId").value(seatLayoutId.toString()))
                .andExpect(jsonPath("$.registrationNumber").value("TS09AB1234"))
                .andExpect(jsonPath("$.displayName").value("Hyderabad Express 1"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn();

        UUID busId = UUID.fromString(objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText());

        mockMvc.perform(get("/api/v1/admin/buses/{id}", busId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registrationNumber").value("TS09AB1234"))
                .andExpect(jsonPath("$.operatorId").value(operatorId.toString()))
                .andExpect(jsonPath("$.busTypeId").value(busTypeId.toString()))
                .andExpect(jsonPath("$.seatLayoutId").value(seatLayoutId.toString()));

        mockMvc.perform(get("/api/v1/admin/buses")
                        .param("operatorId", operatorId.toString())
                        .param("status", "ACTIVE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='%s')]".formatted(busId)).exists());

        UUID otherBusTypeId = createBusType("NON_AC_SEATER", "Non AC Seater");
        UUID otherLayoutId = createSeatLayout(operatorId, "2x2 Seater Layout", 2);

        mockMvc.perform(put("/api/v1/admin/buses/{id}", busId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "displayName":"Hyderabad Express Updated",
                                  "busTypeId":"%s",
                                  "seatLayoutId":"%s"
                                }
                                """.formatted(otherBusTypeId, otherLayoutId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Hyderabad Express Updated"))
                .andExpect(jsonPath("$.busTypeId").value(otherBusTypeId.toString()))
                .andExpect(jsonPath("$.seatLayoutId").value(otherLayoutId.toString()))
                .andExpect(jsonPath("$.registrationNumber").value("TS09AB1234"))
                .andExpect(jsonPath("$.operatorId").value(operatorId.toString()));

        mockMvc.perform(post("/api/v1/admin/buses/{id}/deactivate", busId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INACTIVE"));

        mockMvc.perform(get("/api/v1/admin/buses")
                        .param("operatorId", operatorId.toString())
                        .param("status", "INACTIVE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='%s')]".formatted(busId)).exists());

        mockMvc.perform(post("/api/v1/admin/buses/{id}/activate", busId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        mockMvc.perform(post("/api/v1/admin/buses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "operatorId":"%s",
                                  "busTypeId":"%s",
                                  "seatLayoutId":"%s",
                                  "registrationNumber":"TS09AB1234"
                                }
                                """.formatted(UUID.randomUUID(), busTypeId, seatLayoutId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Operator was not found."));

        mockMvc.perform(post("/api/v1/admin/buses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "operatorId":"%s",
                                  "busTypeId":"%s",
                                  "seatLayoutId":"%s",
                                  "registrationNumber":"TS09AB9999"
                                }
                                """.formatted(operatorId, UUID.randomUUID(), seatLayoutId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Bus type was not found."));

        mockMvc.perform(post("/api/v1/admin/buses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "operatorId":"%s",
                                  "busTypeId":"%s",
                                  "seatLayoutId":"%s",
                                  "registrationNumber":"TS09AB8888"
                                }
                                """.formatted(operatorId, busTypeId, UUID.randomUUID())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Seat layout was not found."));

        mockMvc.perform(post("/api/v1/admin/buses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "operatorId":"%s",
                                  "busTypeId":"%s",
                                  "seatLayoutId":"%s",
                                  "registrationNumber":"ts09ab1234"
                                }
                                """.formatted(operatorId, busTypeId, seatLayoutId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Bus registration number already exists."));

        mockMvc.perform(get("/api/v1/admin/buses/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Bus was not found."));
    }

    private UUID createOperator(String legalName, String displayName) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/admin/operators")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"legalName":"%s","displayName":"%s"}
                                """.formatted(legalName, displayName)))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private UUID createBusType(String code, String displayName) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/admin/bus-types")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"%s","displayName":"%s"}
                                """.formatted(code, displayName)))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private UUID createSeatLayout(UUID operatorId, String name, int version) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/admin/seat-layouts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "operatorId":"%s",
                                  "name":"%s",
                                  "version":%d,
                                  "deckCount":1,
                                  "rowCount":2,
                                  "columnCount":2,
                                  "seats":[
                                    {"seatNumber":"A1","deckNumber":1,"rowNumber":1,"columnNumber":1,"seatType":"SEATER"},
                                    {"seatNumber":"A2","deckNumber":1,"rowNumber":1,"columnNumber":2,"seatType":"SEATER"}
                                  ]
                                }
                                """.formatted(operatorId, name, version)))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }
}
