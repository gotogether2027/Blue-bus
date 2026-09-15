package in.bluebustickets.bluebus.scheduling.api;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import in.bluebustickets.bluebus.foundation.security.TestAccessTokenFactory;
import in.bluebustickets.bluebus.scheduling.application.JourneySeatAvailability;
import in.bluebustickets.bluebus.scheduling.application.TripSeatAllocationService;
import in.bluebustickets.bluebus.scheduling.domain.TripSeatAllocationState;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TripSeatAvailabilityApiPostgresIntegrationTest {

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
    @Autowired private TripSeatAllocationService allocationService;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private TestAccessTokenFactory testAccessTokenFactory;
    private String adminToken;

    @BeforeEach
    void seedPlatformAdmin() {
        adminToken = testAccessTokenFactory.issuePlatformAdmin().accessToken();
    }

    @Test
    @WithMockUser
    void returnsDerivedAvailabilityForValidOriginAndDestination() throws Exception {
        TripFixture trip = createTrip("AVAIL-API-01", "AVAIL-API-RT-01");
        UUID available = trip.availableSeatIds().get(0);
        UUID blocked = trip.blockedSeatId();
        UUID bookedSeat = trip.availableSeatIds().get(1);
        UUID heldSeat = trip.availableSeatIds().get(2);

        allocationService.allocate(trip.tripId(), bookedSeat, 1, 3, TripSeatAllocationState.BOOKED, null);
        allocationService.allocate(
                trip.tripId(), heldSeat, 2, 4, TripSeatAllocationState.HELD, Instant.now().plusSeconds(300));

        long holdsBefore = count("seat_holds");
        long allocationsBefore = count("trip_seat_allocations");
        long inventoryBefore = count("trip_seat_inventory");

        MvcResult result = mockMvc.perform(get("/api/v1/trips/{tripId}/seat-availability", trip.tripId())
                        .param("originStopId", trip.stopId(1).toString())
                        .param("destinationStopId", trip.stopId(3).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tripId").value(trip.tripId().toString()))
                .andExpect(jsonPath("$.originStopId").value(trip.stopId(1).toString()))
                .andExpect(jsonPath("$.destinationStopId").value(trip.stopId(3).toString()))
                .andExpect(jsonPath("$.originSequence").value(1))
                .andExpect(jsonPath("$.destinationSequence").value(3))
                .andExpect(jsonPath("$.seats.length()").value(4))
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("seats")).extracting(node -> node.get("seatNumber").asText()).isSorted();

        assertThat(availabilityOf(body, available)).isEqualTo(JourneySeatAvailability.AVAILABLE.name());
        assertThat(availabilityOf(body, bookedSeat)).isEqualTo(JourneySeatAvailability.UNAVAILABLE.name());
        assertThat(availabilityOf(body, heldSeat)).isEqualTo(JourneySeatAvailability.UNAVAILABLE.name());
        assertThat(availabilityOf(body, blocked)).isEqualTo(JourneySeatAvailability.UNAVAILABLE.name());
        assertThat(physicalStatusOf(body, blocked)).isEqualTo("BLOCKED");

        assertThat(count("seat_holds")).isEqualTo(holdsBefore);
        assertThat(count("trip_seat_allocations")).isEqualTo(allocationsBefore);
        assertThat(count("trip_seat_inventory")).isEqualTo(inventoryBefore);
    }

    @Test
    @WithMockUser
    void adjacentExpiredCancelledReleasedDoNotBlock() throws Exception {
        TripFixture trip = createTrip("AVAIL-API-02", "AVAIL-API-RT-02");
        UUID adjacent = trip.availableSeatIds().get(0);
        UUID expired = trip.availableSeatIds().get(1);
        UUID cancelled = trip.availableSeatIds().get(2);

        allocationService.allocate(trip.tripId(), adjacent, 3, 4, TripSeatAllocationState.BOOKED, null);
        allocationService.allocate(trip.tripId(), expired, 1, 3, TripSeatAllocationState.EXPIRED, null);
        allocationService.allocate(trip.tripId(), cancelled, 1, 3, TripSeatAllocationState.CANCELLED, null);
        allocationService.allocate(
                trip.tripId(), adjacent, 1, 2, TripSeatAllocationState.RELEASED, null);

        MvcResult result = mockMvc.perform(get("/api/v1/trips/{tripId}/seat-availability", trip.tripId())
                        .param("originStopId", trip.stopId(1).toString())
                        .param("destinationStopId", trip.stopId(3).toString()))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(availabilityOf(body, adjacent)).isEqualTo(JourneySeatAvailability.AVAILABLE.name());
        assertThat(availabilityOf(body, expired)).isEqualTo(JourneySeatAvailability.AVAILABLE.name());
        assertThat(availabilityOf(body, cancelled)).isEqualTo(JourneySeatAvailability.AVAILABLE.name());
    }

    @Test
    @WithMockUser
    void validationAndNotFoundErrorsFollowApiErrorEnvelope() throws Exception {
        TripFixture trip = createTrip("AVAIL-API-03", "AVAIL-API-RT-03");
        TripFixture other = createTrip("AVAIL-API-03B", "AVAIL-API-RT-03B");

        mockMvc.perform(get("/api/v1/trips/{tripId}/seat-availability", trip.tripId())
                        .param("destinationStopId", trip.stopId(3).toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Missing required parameter 'originStopId'."));

        mockMvc.perform(get("/api/v1/trips/{tripId}/seat-availability", trip.tripId())
                        .param("originStopId", trip.stopId(1).toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Missing required parameter 'destinationStopId'."));

        mockMvc.perform(get("/api/v1/trips/{tripId}/seat-availability", trip.tripId())
                        .param("originStopId", "not-a-uuid")
                        .param("destinationStopId", trip.stopId(3).toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        mockMvc.perform(get("/api/v1/trips/{tripId}/seat-availability", trip.tripId())
                        .param("originStopId", trip.stopId(1).toString())
                        .param("destinationStopId", "not-a-uuid"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/v1/trips/{tripId}/seat-availability", UUID.randomUUID())
                        .param("originStopId", trip.stopId(1).toString())
                        .param("destinationStopId", trip.stopId(3).toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Trip was not found."));

        mockMvc.perform(get("/api/v1/trips/{tripId}/seat-availability", trip.tripId())
                        .param("originStopId", other.stopId(1).toString())
                        .param("destinationStopId", trip.stopId(3).toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Origin trip stop was not found for this trip."));

        mockMvc.perform(get("/api/v1/trips/{tripId}/seat-availability", trip.tripId())
                        .param("originStopId", trip.stopId(1).toString())
                        .param("destinationStopId", other.stopId(3).toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Destination trip stop was not found for this trip."));

        mockMvc.perform(get("/api/v1/trips/{tripId}/seat-availability", trip.tripId())
                        .param("originStopId", trip.stopId(2).toString())
                        .param("destinationStopId", trip.stopId(2).toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Destination stop must be after the origin stop"));

        mockMvc.perform(get("/api/v1/trips/{tripId}/seat-availability", trip.tripId())
                        .param("originStopId", trip.stopId(3).toString())
                        .param("destinationStopId", trip.stopId(1).toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Destination stop must be after the origin stop"));
    }

    @Test
    @WithMockUser
    void isPubliclyAccessibleWithoutAuthentication() throws Exception {
        TripFixture trip = createTrip("AVAIL-API-04", "AVAIL-API-RT-04");

        mockMvc.perform(get("/api/v1/trips/{tripId}/seat-availability", trip.tripId())
                        .with(anonymous())
                        .param("originStopId", trip.stopId(1).toString())
                        .param("destinationStopId", trip.stopId(4).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.originSequence").value(1))
                .andExpect(jsonPath("$.destinationSequence").value(4));
    }

    @Test
    @WithMockUser
    void usesBoundedQueryCountWithoutPerSeatLookups() throws Exception {
        TripFixture trip = createTrip("AVAIL-API-05", "AVAIL-API-RT-05");
        allocationService.allocate(
                trip.tripId(),
                trip.availableSeatIds().get(0),
                1,
                3,
                TripSeatAllocationState.BOOKED,
                null);

        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        mockMvc.perform(get("/api/v1/trips/{tripId}/seat-availability", trip.tripId())
                        .param("originStopId", trip.stopId(1).toString())
                        .param("destinationStopId", trip.stopId(3).toString()))
                .andExpect(status().isOk());

        assertThat(statistics.getPrepareStatementCount()).isLessThanOrEqualTo(8);
        assertThat(statistics.getPrepareStatementCount()).isGreaterThanOrEqualTo(3);
    }

    private static String availabilityOf(JsonNode body, UUID inventoryId) {
        for (JsonNode seat : body.get("seats")) {
            if (inventoryId.toString().equals(seat.get("inventoryId").asText())) {
                return seat.get("availability").asText();
            }
        }
        throw new AssertionError("Seat not found: " + inventoryId);
    }

    private static String physicalStatusOf(JsonNode body, UUID inventoryId) {
        for (JsonNode seat : body.get("seats")) {
            if (inventoryId.toString().equals(seat.get("inventoryId").asText())) {
                return seat.get("physicalStatus").asText();
            }
        }
        throw new AssertionError("Seat not found: " + inventoryId);
    }

    private long count(String table) {
        Long value = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return value == null ? 0L : value;
    }

    private TripFixture createTrip(String registration, String routeCode) throws Exception {
        Fixture fixture = createFixture(registration, routeCode, 4);
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
                .andReturn();

        JsonNode body = objectMapper.readTree(created.getResponse().getContentAsString());
        UUID tripId = UUID.fromString(body.get("id").asText());
        List<UUID> stopIdsBySequence = new ArrayList<>();
        stopIdsBySequence.add(null); // 1-based
        for (JsonNode stop : body.get("stops")) {
            int sequence = stop.get("sequenceNumber").asInt();
            while (stopIdsBySequence.size() <= sequence) {
                stopIdsBySequence.add(null);
            }
            stopIdsBySequence.set(sequence, UUID.fromString(stop.get("id").asText()));
        }
        List<UUID> available = new ArrayList<>();
        UUID blocked = null;
        for (JsonNode seat : body.get("seatInventory")) {
            UUID id = UUID.fromString(seat.get("id").asText());
            if ("BLOCKED".equals(seat.get("physicalStatus").asText())) {
                blocked = id;
            } else {
                available.add(id);
            }
        }
        assertThat(available).hasSizeGreaterThanOrEqualTo(3);
        assertThat(blocked).isNotNull();
        assertThat(stopIdsBySequence.get(1)).isNotNull();
        assertThat(stopIdsBySequence.get(4)).isNotNull();
        return new TripFixture(tripId, stopIdsBySequence, available, blocked);
    }

    private Fixture createFixture(String registration, String routeCode, int seatCount) throws Exception {
        UUID operatorId = createOperator("Api Op " + registration, "Api Co " + registration);
        UUID busTypeId = createBusType("TYPE_" + registration.replace(" ", ""), "Type " + registration);
        UUID layoutId = createSeatLayout(operatorId, "Layout " + registration, 1, seatCount);
        UUID busId = createBus(operatorId, busTypeId, layoutId, registration);
        UUID hyderabadId = createLocation("Telangana", "Hyderabad-" + registration);
        UUID suryapetId = createLocation("Telangana", "Suryapet-" + registration);
        UUID vijayawadaId = createLocation("Andhra Pradesh", "Vijayawada-" + registration);
        UUID gunturId = createLocation("Andhra Pradesh", "Guntur-" + registration);
        UUID routeId = createRoute(operatorId, routeCode, hyderabadId, suryapetId, vijayawadaId, gunturId);
        return new Fixture(busId, routeId);
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
        StringBuilder seats = new StringBuilder("[");
        int created = 0;
        for (int row = 1; row <= 2 && created < seatCount; row++) {
            for (int col = 1; col <= 2 && created < seatCount; col++) {
                if (created > 0) {
                    seats.append(',');
                }
                boolean sellable = !(seatCount >= 4 && created == 3);
                seats.append("""
                        {"seatNumber":"S%d","deckNumber":1,"rowNumber":%d,"columnNumber":%d,"seatType":"SEATER","sellable":%s}
                        """.formatted(created + 1, row, col, sellable));
                created++;
            }
        }
        seats.append(']');
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
                                """.formatted(operatorId, name, version, seats)))
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

    private record Fixture(UUID busId, UUID routeId) { }

    private record TripFixture(
            UUID tripId,
            List<UUID> stopIdsBySequence,
            List<UUID> availableSeatIds,
            UUID blockedSeatId) {
        UUID stopId(int sequence) {
            return stopIdsBySequence.get(sequence);
        }
    }
}
