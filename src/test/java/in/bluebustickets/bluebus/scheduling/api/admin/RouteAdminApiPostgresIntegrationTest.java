package in.bluebustickets.bluebus.scheduling.api.admin;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
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
@WithMockUser
class RouteAdminApiPostgresIntegrationTest {

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

    @Test
    void manageRoutesStopsAndPointsWithValidation() throws Exception {
        UUID operatorId = createOperator("Route Master Operator Pvt Ltd", "Route Master Co");
        UUID hyderabadId = createLocation("Telangana", "Hyderabad");
        UUID suryapetId = createLocation("Telangana", "Suryapet");
        UUID vijayawadaId = createLocation("Andhra Pradesh", "Vijayawada");
        UUID gunturId = createLocation("Andhra Pradesh", "Guntur");

        MvcResult created = mockMvc.perform(post("/api/v1/admin/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "operatorId":"%s",
                                  "code":"HYD-GNT",
                                  "name":"Hyderabad to Guntur",
                                  "sourceLocationId":"%s",
                                  "destinationLocationId":"%s",
                                  "stops":[
                                    {
                                      "locationId":"%s",
                                      "sequenceNumber":1,
                                      "stopKind":"SOURCE",
                                      "departureOffsetMinutes":0,
                                      "distanceKm":0,
                                      "points":[
                                        {"name":"Miyapur Metro","pointType":"BOARDING","address":"Miyapur"}
                                      ]
                                    },
                                    {
                                      "locationId":"%s",
                                      "sequenceNumber":2,
                                      "stopKind":"INTERMEDIATE",
                                      "arrivalOffsetMinutes":90,
                                      "departureOffsetMinutes":100,
                                      "distanceKm":140.50,
                                      "points":[
                                        {"name":"Suryapet Bus Stand","pointType":"BOTH"}
                                      ]
                                    },
                                    {
                                      "locationId":"%s",
                                      "sequenceNumber":3,
                                      "stopKind":"INTERMEDIATE",
                                      "arrivalOffsetMinutes":180,
                                      "departureOffsetMinutes":190,
                                      "distanceKm":260.00
                                    },
                                    {
                                      "locationId":"%s",
                                      "sequenceNumber":4,
                                      "stopKind":"DESTINATION",
                                      "arrivalOffsetMinutes":270,
                                      "distanceKm":340.00,
                                      "points":[
                                        {"name":"Guntur RTC","pointType":"DROPPING"}
                                      ]
                                    }
                                  ]
                                }
                                """.formatted(
                                operatorId,
                                hyderabadId,
                                gunturId,
                                hyderabadId,
                                suryapetId,
                                vijayawadaId,
                                gunturId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("HYD-GNT"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.stops.length()").value(4))
                .andExpect(jsonPath("$.stops[0].sequenceNumber").value(1))
                .andExpect(jsonPath("$.stops[0].locationId").value(hyderabadId.toString()))
                .andExpect(jsonPath("$.stops[0].stopKind").value("SOURCE"))
                .andExpect(jsonPath("$.stops[0].points[0].pointType").value("BOARDING"))
                .andExpect(jsonPath("$.stops[1].points[0].pointType").value("BOTH"))
                .andExpect(jsonPath("$.stops[3].points[0].pointType").value("DROPPING"))
                .andReturn();

        JsonNode routeBody = objectMapper.readTree(created.getResponse().getContentAsString());
        UUID routeId = UUID.fromString(routeBody.get("id").asText());
        UUID sourceStopId = UUID.fromString(routeBody.get("stops").get(0).get("id").asText());
        UUID intermediateStopId = UUID.fromString(routeBody.get("stops").get(2).get("id").asText());

        Integer stopCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM route_stops WHERE route_id = ?", Integer.class, routeId);
        assertThat(stopCount).isEqualTo(4);
        Integer pointCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM route_points p
                JOIN route_stops s ON s.id = p.route_stop_id
                WHERE s.route_id = ?
                """,
                Integer.class,
                routeId);
        assertThat(pointCount).isEqualTo(3);

        mockMvc.perform(get("/api/v1/admin/routes/{id}", routeId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stops[1].locationId").value(suryapetId.toString()))
                .andExpect(jsonPath("$.stops[2].locationId").value(vijayawadaId.toString()));

        mockMvc.perform(get("/api/v1/admin/routes")
                        .param("operatorId", operatorId.toString())
                        .param("status", "ACTIVE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='%s')]".formatted(routeId)).exists());

        mockMvc.perform(put("/api/v1/admin/routes/{id}", routeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":"Hyderabad to Guntur Express",
                                  "sourceLocationId":"%s",
                                  "destinationLocationId":"%s"
                                }
                                """.formatted(hyderabadId, gunturId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Hyderabad to Guntur Express"))
                .andExpect(jsonPath("$.code").value("HYD-GNT"))
                .andExpect(jsonPath("$.stops.length()").value(4));

        mockMvc.perform(post("/api/v1/admin/routes/{id}/deactivate", routeId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INACTIVE"));

        mockMvc.perform(post("/api/v1/admin/routes/{id}/activate", routeId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        mockMvc.perform(post("/api/v1/admin/routes/{routeId}/stops/{stopId}/points", routeId, intermediateStopId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":"Vijayawada Benz Circle",
                                  "pointType":"BOARDING",
                                  "address":"Benz Circle"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.routeStopId").value(intermediateStopId.toString()))
                .andExpect(jsonPath("$.pointType").value("BOARDING"));

        mockMvc.perform(post("/api/v1/admin/routes/{routeId}/stops/{stopId}/points", routeId, intermediateStopId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Vijayawada Drop","pointType":"DROPPING"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.pointType").value("DROPPING"));

        UUID otherOperatorId = createOperator("Other Route Operator", "Other Route Co");
        UUID otherSource = createLocation("Karnataka", "Bengaluru");
        UUID otherDest = createLocation("Tamil Nadu", "Chennai");
        MvcResult otherRoute = mockMvc.perform(post("/api/v1/admin/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "operatorId":"%s",
                                  "code":"BLR-MAA",
                                  "name":"Bengaluru to Chennai",
                                  "sourceLocationId":"%s",
                                  "destinationLocationId":"%s",
                                  "stops":[
                                    {"locationId":"%s","sequenceNumber":1,"stopKind":"SOURCE"},
                                    {"locationId":"%s","sequenceNumber":2,"stopKind":"DESTINATION"}
                                  ]
                                }
                                """.formatted(otherOperatorId, otherSource, otherDest, otherSource, otherDest)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID otherRouteId = UUID.fromString(
                objectMapper.readTree(otherRoute.getResponse().getContentAsString()).get("id").asText());

        mockMvc.perform(post("/api/v1/admin/routes/{routeId}/stops/{stopId}/points", otherRouteId, sourceStopId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Cross Route Point","pointType":"BOARDING"}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Route stop was not found."));

        mockMvc.perform(post("/api/v1/admin/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "operatorId":"%s",
                                  "code":"MISSING-LOC",
                                  "name":"Missing location",
                                  "sourceLocationId":"%s",
                                  "destinationLocationId":"%s"
                                }
                                """.formatted(operatorId, UUID.randomUUID(), gunturId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Location was not found."));

        mockMvc.perform(post("/api/v1/admin/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "operatorId":"%s",
                                  "code":"DUP-SEQ",
                                  "name":"Duplicate sequence",
                                  "sourceLocationId":"%s",
                                  "destinationLocationId":"%s",
                                  "stops":[
                                    {"locationId":"%s","sequenceNumber":1,"stopKind":"SOURCE"},
                                    {"locationId":"%s","sequenceNumber":1,"stopKind":"DESTINATION"}
                                  ]
                                }
                                """.formatted(operatorId, hyderabadId, gunturId, hyderabadId, gunturId)))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/v1/admin/routes/{routeId}/stops", routeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "locationId":"%s",
                                  "sequenceNumber":2,
                                  "stopKind":"INTERMEDIATE"
                                }
                                """.formatted(suryapetId)))
                .andExpect(status().isConflict());

        mockMvc.perform(get("/api/v1/admin/routes/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Route was not found."));

        mockMvc.perform(get("/api/v1/admin/routes/{routeId}/stops/{stopId}", routeId, UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Route stop was not found."));

        mockMvc.perform(post("/api/v1/admin/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "operatorId":"%s",
                                  "code":"",
                                  "name":"Blank code",
                                  "sourceLocationId":"%s",
                                  "destinationLocationId":"%s"
                                }
                                """.formatted(operatorId, hyderabadId, gunturId)))
                .andExpect(status().isBadRequest());
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

    private UUID createLocation(String state, String city) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/admin/locations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"countryCode":"IN","state":"%s","city":"%s","timeZone":"Asia/Kolkata"}
                                """.formatted(state, city)))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }
}
