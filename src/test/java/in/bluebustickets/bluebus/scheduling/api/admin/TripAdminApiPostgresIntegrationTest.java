package in.bluebustickets.bluebus.scheduling.api.admin;

import java.time.Instant;
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
class TripAdminApiPostgresIntegrationTest {

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
    void createTripWithSnapshotsIsolationLifecycleAndIntegrity() throws Exception {
        Fixture fixture = createFixture("TS09TP1001", "HYD-GNT-T1", 4);

        Instant departure = Instant.parse("2026-11-10T12:30:00Z");
        Instant arrival = departure.plusSeconds(8 * 3600);
        Instant opens = departure.minusSeconds(7 * 24 * 3600);
        Instant closes = departure.minusSeconds(3600);

        MvcResult created = mockMvc.perform(post("/api/v1/admin/trips")
                        .with(adminAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "busId":"%s",
                                  "routeId":"%s",
                                  "scheduledDepartureAt":"%s",
                                  "scheduledArrivalAt":"%s",
                                  "baseFare":900.00,
                                  "bookingOpensAt":"%s",
                                  "bookingClosesAt":"%s",
                                  "timeZone":"Asia/Kolkata"
                                }
                                """.formatted(
                                fixture.busId(),
                                fixture.routeId(),
                                departure,
                                arrival,
                                opens,
                                closes)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.busId").value(fixture.busId().toString()))
                .andExpect(jsonPath("$.routeId").value(fixture.routeId().toString()))
                .andExpect(jsonPath("$.serviceDate").value("2026-11-10"))
                .andExpect(jsonPath("$.timeZone").value("Asia/Kolkata"))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.stops.length()").value(4))
                .andExpect(jsonPath("$.stops[0].sequenceNumber").value(1))
                .andExpect(jsonPath("$.stops[0].locationId").value(fixture.hyderabadId().toString()))
                .andExpect(jsonPath("$.stops[0].points[0].pointType").value("BOARDING"))
                .andExpect(jsonPath("$.stops[1].points[0].pointType").value("BOTH"))
                .andExpect(jsonPath("$.stops[3].points[0].pointType").value("DROPPING"))
                .andExpect(jsonPath("$.seatInventory.length()").value(4))
                .andExpect(jsonPath("$.seatInventory[0].physicalStatus").value("AVAILABLE"))
                .andExpect(jsonPath("$.seatInventory[3].physicalStatus").value("BLOCKED"))
                .andExpect(jsonPath("$.seatInventory[3].blockReason").value("Not sellable"))
                .andReturn();

        JsonNode tripBody = objectMapper.readTree(created.getResponse().getContentAsString());
        UUID tripId = UUID.fromString(tripBody.get("id").asText());
        String firstStopLocation = tripBody.get("stops").get(0).get("locationId").asText();
        String firstPointName = tripBody.get("stops").get(0).get("points").get(0).get("name").asText();
        int inventoryCount = tripBody.get("seatInventory").size();
        String firstSeatNumber = tripBody.get("seatInventory").get(0).get("seatNumber").asText();

        mockMvc.perform(get("/api/v1/admin/trips/{id}", tripId).with(adminAuth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scheduledDepartureAt").value(departure.toString()))
                .andExpect(jsonPath("$.stops.length()").value(4))
                .andExpect(jsonPath("$.seatInventory.length()").value(4));

        mockMvc.perform(get("/api/v1/admin/trips")
                        .with(adminAuth())
                        .param("busId", fixture.busId().toString())
                        .param("routeId", fixture.routeId().toString())
                        .param("serviceDate", "2026-11-10")
                        .param("status", "DRAFT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='%s')]".formatted(tripId)).exists());

        mockMvc.perform(put("/api/v1/admin/trips/{id}", tripId)
                        .with(adminAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "baseFare":950.00,
                                  "bookingOpensAt":"%s",
                                  "bookingClosesAt":"%s"
                                }
                                """.formatted(opens, closes)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baseFare").value(950.00))
                .andExpect(jsonPath("$.busId").value(fixture.busId().toString()))
                .andExpect(jsonPath("$.stops.length()").value(4));

        mockMvc.perform(post("/api/v1/admin/trips/{id}/activate", tripId).with(adminAuth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SCHEDULED"));

        mockMvc.perform(post("/api/v1/admin/trips/{id}/deactivate", tripId).with(adminAuth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        mockMvc.perform(put("/api/v1/admin/routes/{id}", fixture.routeId())
                        .with(adminAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":"Changed Master Route Name",
                                  "sourceLocationId":"%s",
                                  "destinationLocationId":"%s"
                                }
                                """.formatted(fixture.hyderabadId(), fixture.gunturId())))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/admin/routes/{routeId}/stops", fixture.routeId())
                        .with(adminAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "locationId":"%s",
                                  "sequenceNumber":5,
                                  "stopKind":"INTERMEDIATE"
                                }
                                """.formatted(fixture.vijayawadaId())))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/admin/trips/{id}", tripId).with(adminAuth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stops.length()").value(4))
                .andExpect(jsonPath("$.stops[0].locationId").value(firstStopLocation))
                .andExpect(jsonPath("$.stops[0].points[0].name").value(firstPointName));

        mockMvc.perform(put("/api/v1/admin/seat-layouts/{id}", fixture.layoutId())
                        .with(adminAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":"Changed Layout Name",
                                  "deckCount":1,
                                  "rowCount":2,
                                  "columnCount":2
                                }
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/admin/trips/{id}", tripId).with(adminAuth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seatInventory.length()").value(inventoryCount))
                .andExpect(jsonPath("$.seatInventory[0].seatNumber").value(firstSeatNumber));

        mockMvc.perform(post("/api/v1/admin/trips")
                        .with(adminAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "busId":"%s",
                                  "routeId":"%s",
                                  "scheduledDepartureAt":"%s",
                                  "scheduledArrivalAt":"%s",
                                  "baseFare":900.00,
                                  "bookingOpensAt":"%s",
                                  "bookingClosesAt":"%s"
                                }
                                """.formatted(
                                fixture.busId(),
                                fixture.routeId(),
                                departure,
                                arrival,
                                opens,
                                closes)))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/v1/admin/trips")
                        .with(adminAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "busId":"%s",
                                  "routeId":"%s",
                                  "scheduledDepartureAt":"%s",
                                  "scheduledArrivalAt":"%s",
                                  "baseFare":900.00,
                                  "bookingOpensAt":"%s",
                                  "bookingClosesAt":"%s"
                                }
                                """.formatted(
                                UUID.randomUUID(),
                                fixture.routeId(),
                                departure.plusSeconds(3600),
                                arrival.plusSeconds(3600),
                                opens,
                                closes)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Bus was not found."));

        mockMvc.perform(post("/api/v1/admin/trips")
                        .with(adminAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "busId":"%s",
                                  "routeId":"%s",
                                  "scheduledDepartureAt":"%s",
                                  "scheduledArrivalAt":"%s",
                                  "baseFare":900.00,
                                  "bookingOpensAt":"%s",
                                  "bookingClosesAt":"%s"
                                }
                                """.formatted(
                                fixture.busId(),
                                UUID.randomUUID(),
                                departure.plusSeconds(7200),
                                arrival.plusSeconds(7200),
                                opens,
                                closes)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Route was not found."));

        Fixture emptyLayoutFixture = createFixture("TS09TP2002", "HYD-GNT-T2", 0);
        long tripsBefore = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM trips", Long.class);
        long stopsBefore = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM trip_stops", Long.class);
        long inventoryBefore = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM trip_seat_inventory", Long.class);

        mockMvc.perform(post("/api/v1/admin/trips")
                        .with(adminAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "busId":"%s",
                                  "routeId":"%s",
                                  "scheduledDepartureAt":"%s",
                                  "scheduledArrivalAt":"%s",
                                  "baseFare":700.00,
                                  "bookingOpensAt":"%s",
                                  "bookingClosesAt":"%s"
                                }
                                """.formatted(
                                emptyLayoutFixture.busId(),
                                emptyLayoutFixture.routeId(),
                                Instant.parse("2026-12-01T10:00:00Z"),
                                Instant.parse("2026-12-01T18:00:00Z"),
                                Instant.parse("2026-11-01T10:00:00Z"),
                                Instant.parse("2026-12-01T09:00:00Z"))))
                .andExpect(status().isBadRequest());

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM trips", Long.class)).isEqualTo(tripsBefore);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM trip_stops", Long.class)).isEqualTo(stopsBefore);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM trip_seat_inventory", Long.class))
                .isEqualTo(inventoryBefore);

        mockMvc.perform(get("/api/v1/admin/trips/{id}", UUID.randomUUID()).with(adminAuth()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Trip was not found."));
    }

    private Fixture createFixture(String registration, String routeCode, int seatCount) throws Exception {
        UUID operatorId = createOperator("Trip Operator " + registration, "Trip Co " + registration);
        UUID busTypeId = createBusType("TYPE_" + registration.replace(" ", ""), "Type " + registration);
        UUID layoutId = createSeatLayout(operatorId, "Layout " + registration, 1, seatCount);
        UUID busId = createBus(operatorId, busTypeId, layoutId, registration);

        UUID hyderabadId = createLocation("Telangana", "Hyderabad-" + registration);
        UUID suryapetId = createLocation("Telangana", "Suryapet-" + registration);
        UUID vijayawadaId = createLocation("Andhra Pradesh", "Vijayawada-" + registration);
        UUID gunturId = createLocation("Andhra Pradesh", "Guntur-" + registration);
        UUID routeId = createRoute(operatorId, routeCode, hyderabadId, suryapetId, vijayawadaId, gunturId);

        return new Fixture(operatorId, layoutId, busId, routeId, hyderabadId, suryapetId, vijayawadaId, gunturId);
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
        return idOf(result);
    }

    private UUID createBusType(String code, String displayName) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/admin/bus-types")
                        .with(adminAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"%s","displayName":"%s"}
                                """.formatted(code, displayName)))
                .andExpect(status().isCreated())
                .andReturn();
        return idOf(result);
    }

    private UUID createSeatLayout(UUID operatorId, String name, int version, int seatCount) throws Exception {
        String seatsJson;
        if (seatCount <= 0) {
            seatsJson = "[]";
        } else {
            StringBuilder seats = new StringBuilder("[");
            int created = 0;
            for (int row = 1; row <= 2 && created < seatCount; row++) {
                for (int col = 1; col <= 2 && created < seatCount; col++) {
                    if (created > 0) {
                        seats.append(',');
                    }
                    boolean sellable = created < seatCount - 1 || seatCount == 1;
                    if (seatCount >= 4 && created == 3) {
                        sellable = false;
                    }
                    seats.append("""
                            {"seatNumber":"S%d","deckNumber":1,"rowNumber":%d,"columnNumber":%d,"seatType":"SEATER","sellable":%s}
                            """.formatted(created + 1, row, col, sellable));
                    created++;
                }
            }
            seats.append(']');
            seatsJson = seats.toString();
        }

        MvcResult result = mockMvc.perform(post("/api/v1/admin/seat-layouts")
                        .with(adminAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "operatorId":"%s",
                                  "name":"%s",
                                  "version":%d,
                                  "deckCount":1,
                                  "rowCount":2,
                                  "columnCount":2,
                                  "seats":%s
                                }
                                """.formatted(operatorId, name, version, seatsJson)))
                .andExpect(status().isCreated())
                .andReturn();
        return idOf(result);
    }

    private UUID createBus(UUID operatorId, UUID busTypeId, UUID layoutId, String registration) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/admin/buses")
                        .with(adminAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "operatorId":"%s",
                                  "busTypeId":"%s",
                                  "seatLayoutId":"%s",
                                  "registrationNumber":"%s"
                                }
                                """.formatted(operatorId, busTypeId, layoutId, registration)))
                .andExpect(status().isCreated())
                .andReturn();
        return idOf(result);
    }

    private UUID createLocation(String state, String city) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/admin/locations")
                        .with(adminAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"countryCode":"IN","state":"%s","city":"%s","timeZone":"Asia/Kolkata"}
                                """.formatted(state, city)))
                .andExpect(status().isCreated())
                .andReturn();
        return idOf(result);
    }

    private UUID createRoute(
            UUID operatorId,
            String code,
            UUID hyderabadId,
            UUID suryapetId,
            UUID vijayawadaId,
            UUID gunturId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/admin/routes")
                        .with(adminAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "operatorId":"%s",
                                  "code":"%s",
                                  "name":"Hyderabad to Guntur",
                                  "sourceLocationId":"%s",
                                  "destinationLocationId":"%s",
                                  "stops":[
                                    {
                                      "locationId":"%s","sequenceNumber":1,"stopKind":"SOURCE","departureOffsetMinutes":0,"distanceKm":0,
                                      "points":[{"name":"Miyapur Boarding","pointType":"BOARDING"}]
                                    },
                                    {
                                      "locationId":"%s","sequenceNumber":2,"stopKind":"INTERMEDIATE","arrivalOffsetMinutes":90,"departureOffsetMinutes":100,"distanceKm":140.5,
                                      "points":[{"name":"Suryapet Stand","pointType":"BOTH"}]
                                    },
                                    {
                                      "locationId":"%s","sequenceNumber":3,"stopKind":"INTERMEDIATE","arrivalOffsetMinutes":180,"departureOffsetMinutes":190,"distanceKm":260
                                    },
                                    {
                                      "locationId":"%s","sequenceNumber":4,"stopKind":"DESTINATION","arrivalOffsetMinutes":270,"distanceKm":340,
                                      "points":[{"name":"Guntur RTC","pointType":"DROPPING"}]
                                    }
                                  ]
                                }
                                """.formatted(
                                operatorId, code, hyderabadId, gunturId,
                                hyderabadId, suryapetId, vijayawadaId, gunturId)))
                .andExpect(status().isCreated())
                .andReturn();
        return idOf(result);
    }

    private UUID idOf(MvcResult result) throws Exception {
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }


    private org.springframework.test.web.servlet.request.RequestPostProcessor adminAuth() {
        return TestAccessTokenFactory.bearer(adminToken);
    }

    private record Fixture(
            UUID operatorId,
            UUID layoutId,
            UUID busId,
            UUID routeId,
            UUID hyderabadId,
            UUID suryapetId,
            UUID vijayawadaId,
            UUID gunturId) {
    }
}
