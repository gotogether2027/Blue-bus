package in.bluebustickets.bluebus.scheduling.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.StreamSupport;

import in.bluebustickets.bluebus.foundation.security.TestAccessTokenFactory;
import in.bluebustickets.bluebus.booking.repository.BookingCancellationRepository;
import in.bluebustickets.bluebus.booking.repository.BookingRepository;
import in.bluebustickets.bluebus.identity.domain.Role;
import in.bluebustickets.bluebus.identity.domain.RoleCode;
import in.bluebustickets.bluebus.identity.domain.User;
import in.bluebustickets.bluebus.identity.domain.UserRole;
import in.bluebustickets.bluebus.identity.repository.RoleRepository;
import in.bluebustickets.bluebus.identity.repository.UserRepository;
import in.bluebustickets.bluebus.identity.repository.UserRoleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
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
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TripSearchApiPostgresIntegrationTest {

    private static final String PASSWORD = "CorrectHorseBatteryStaple!";
    private static final String CUSTOMER_EMAIL = "search-a@example.test";
    private static final LocalDate SERVICE_DATE = LocalDate.parse("2026-12-01");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("blue-bus.seat-holds.expiry.enabled", () -> "false");
        registry.add("blue-bus.bookings.expiry.enabled", () -> "false");
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRoleRepository userRoleRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private BookingRepository bookingRepository;
    @Autowired private BookingCancellationRepository cancellationRepository;
    @Autowired private TestAccessTokenFactory testAccessTokenFactory;
    private String adminToken;

    private String customerToken;

    @BeforeEach
    void seedCustomer() throws Exception {
        cancellationRepository.deleteAll();
        bookingRepository.deleteAll();
        userRoleRepository.deleteAll();
        userRepository.deleteAll();

        Role customerRole = roleRepository.findByCode(RoleCode.CUSTOMER).orElseThrow();
        User user = new User(CUSTOMER_EMAIL, "+919955500001", "Search", "Customer");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user = userRepository.saveAndFlush(user);
        userRoleRepository.saveAndFlush(new UserRole(user, customerRole));
        customerToken = loginToken();
        adminToken = testAccessTokenFactory.issuePlatformAdmin().accessToken();
    }

    @Test
    @WithMockUser
    void searchesOnlyForwardStopSequencesAndActivatedTrips() throws Exception {
        NetworkFixture network = createNetwork("SEARCH-NET-01");
        TripFixture scheduledA = createTrip("SEARCH-01", "SEARCH-RT-01", network, true);
        TripFixture scheduledB = createTrip("SEARCH-02", "SEARCH-RT-02", network, true);
        TripFixture draft = createTrip("SEARCH-03", "SEARCH-RT-03", network, false);

        JsonNode forward = search(network.hyderabadId(), network.gunturId(), SERVICE_DATE);
        assertThat(idsOf(forward)).containsExactlyInAnyOrder(scheduledA.tripId(), scheduledB.tripId());
        assertThat(idsOf(forward)).doesNotContain(draft.tripId());
        for (JsonNode trip : forward) {
            assertThat(trip.get("origin").get("sequenceNumber").asInt())
                    .isLessThan(trip.get("destination").get("sequenceNumber").asInt());
            assertThat(trip.get("origin").get("locationId").asText())
                    .isEqualTo(network.hyderabadId().toString());
            assertThat(trip.get("destination").get("locationId").asText())
                    .isEqualTo(network.gunturId().toString());
            assertThat(trip.get("origin").get("points").get(0).get("pointType").asText())
                    .isEqualTo("BOARDING");
            assertThat(trip.get("destination").get("points").get(0).get("pointType").asText())
                    .isEqualTo("DROPPING");
            assertThat(trip.get("status").asText()).isEqualTo("SCHEDULED");
        }

        JsonNode reverse = search(network.gunturId(), network.hyderabadId(), SERVICE_DATE);
        assertThat(reverse.size()).isZero();

        JsonNode mid = search(network.hyderabadId(), network.vijayawadaId(), SERVICE_DATE);
        assertThat(idsOf(mid)).containsExactlyInAnyOrder(scheduledA.tripId(), scheduledB.tripId());
        assertThat(mid.get(0).get("origin").get("sequenceNumber").asInt()).isEqualTo(1);
        assertThat(mid.get(0).get("destination").get("sequenceNumber").asInt()).isEqualTo(3);
    }

    @Test
    @WithMockUser
    void searchAvailabilityIsSegmentAwareNotWholeTripOccupancy() throws Exception {
        NetworkFixture network = createNetwork("SEARCH-NET-02");
        TripFixture trip = createTrip("SEARCH-04", "SEARCH-RT-04", network, true);
        long sellable = trip.availableSeatIds().size();

        JsonNode before = search(network.hyderabadId(), network.vijayawadaId(), SERVICE_DATE);
        JsonNode tripBefore = findTrip(before, trip.tripId());
        assertThat(tripBefore.get("availableSeatCount").asLong()).isEqualTo(sellable);

        UUID seat = trip.availableSeatIds().get(0);
        JsonNode hold = createHold(trip, seat);
        mockMvc.perform(post("/api/v1/bookings")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingBody(UUID.fromString(hold.get("holdId").asText()), trip, seat)))
                .andExpect(status().isCreated());

        JsonNode overlapping = search(network.hyderabadId(), network.vijayawadaId(), SERVICE_DATE);
        assertThat(findTrip(overlapping, trip.tripId()).get("availableSeatCount").asLong())
                .isEqualTo(sellable - 1);

        JsonNode adjacent = search(network.vijayawadaId(), network.gunturId(), SERVICE_DATE);
        assertThat(findTrip(adjacent, trip.tripId()).get("availableSeatCount").asLong())
                .isEqualTo(sellable);

        JsonNode containing = search(network.hyderabadId(), network.gunturId(), SERVICE_DATE);
        assertThat(findTrip(containing, trip.tripId()).get("availableSeatCount").asLong())
                .isEqualTo(sellable - 1);
    }

    @Test
    @WithMockUser
    void searchIsPublicAndValidatesLocations() throws Exception {
        NetworkFixture network = createNetwork("SEARCH-NET-03");
        createTrip("SEARCH-05", "SEARCH-RT-05", network, true);

        mockMvc.perform(get("/api/v1/search/trips")
                        .with(anonymous())
                        .param("originLocationId", network.hyderabadId().toString())
                        .param("destinationLocationId", network.gunturId().toString())
                        .param("serviceDate", SERVICE_DATE.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        mockMvc.perform(get("/api/v1/search/trips")
                        .with(anonymous())
                        .param("originLocationId", network.hyderabadId().toString())
                        .param("destinationLocationId", network.hyderabadId().toString())
                        .param("serviceDate", SERVICE_DATE.toString()))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/v1/search/trips")
                        .with(anonymous())
                        .param("originLocationId", UUID.randomUUID().toString())
                        .param("destinationLocationId", network.gunturId().toString())
                        .param("serviceDate", SERVICE_DATE.toString()))
                .andExpect(status().isNotFound());
    }

    private JsonNode search(UUID originLocationId, UUID destinationLocationId, LocalDate serviceDate)
            throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/search/trips")
                        .with(anonymous())
                        .param("originLocationId", originLocationId.toString())
                        .param("destinationLocationId", destinationLocationId.toString())
                        .param("serviceDate", serviceDate.toString()))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static List<UUID> idsOf(JsonNode trips) {
        return StreamSupport.stream(trips.spliterator(), false)
                .map(trip -> UUID.fromString(trip.get("tripId").asText()))
                .toList();
    }

    private static JsonNode findTrip(JsonNode trips, UUID tripId) {
        return StreamSupport.stream(trips.spliterator(), false)
                .filter(trip -> tripId.toString().equals(trip.get("tripId").asText()))
                .findFirst()
                .orElseThrow();
    }

    private String loginToken() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}
                                """.formatted(CUSTOMER_EMAIL, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private JsonNode createHold(TripFixture trip, UUID seat) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/trips/{tripId}/holds", trip.tripId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "originStopId":"%s",
                                  "destinationStopId":"%s",
                                  "seatInventoryIds":["%s"]
                                }
                                """.formatted(trip.stopId(1), trip.stopId(3), seat)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String bookingBody(UUID holdId, TripFixture trip, UUID seat) {
        return """
                {
                  "holdId":"%s",
                  "originStopId":"%s",
                  "destinationStopId":"%s",
                  "idempotencyKey":"search-book-1",
                  "passengers":[{"seatInventoryId":"%s","fullName":"Search Rider","age":31}]
                }
                """.formatted(holdId, trip.stopId(1), trip.stopId(3), seat);
    }

    private NetworkFixture createNetwork(String suffix) throws Exception {
        UUID hyderabadId = createLocation("Telangana", "Hyderabad-" + suffix);
        UUID suryapetId = createLocation("Telangana", "Suryapet-" + suffix);
        UUID vijayawadaId = createLocation("Andhra Pradesh", "Vijayawada-" + suffix);
        UUID gunturId = createLocation("Andhra Pradesh", "Guntur-" + suffix);
        return new NetworkFixture(hyderabadId, suryapetId, vijayawadaId, gunturId);
    }

    private TripFixture createTrip(
            String registration,
            String routeCode,
            NetworkFixture network,
            boolean activate) throws Exception {
        UUID operatorId = createOperator("Search Op " + registration, "Search Co " + registration);
        UUID busTypeId = createBusType("STYPE_" + registration.replace(" ", ""), "Type " + registration);
        UUID layoutId = createSeatLayout(operatorId, "Layout " + registration, 1, 4);
        UUID busId = createBus(operatorId, busTypeId, layoutId, registration);
        UUID routeId = createRoute(operatorId, routeCode, network);

        Instant departure = Instant.parse("2026-12-01T10:00:00Z");
        Instant arrival = departure.plusSeconds(6 * 3600);
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
                                """.formatted(busId, routeId, departure, arrival, opens, closes)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode body = objectMapper.readTree(created.getResponse().getContentAsString());
        UUID tripId = UUID.fromString(body.get("id").asText());
        if (activate) {
            mockMvc.perform(post("/api/v1/admin/trips/{id}/activate", tripId).with(adminAuth()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("SCHEDULED"));
        }

        List<UUID> stopIdsBySequence = new ArrayList<>();
        stopIdsBySequence.add(null);
        for (JsonNode stop : body.get("stops")) {
            int sequence = stop.get("sequenceNumber").asInt();
            while (stopIdsBySequence.size() <= sequence) {
                stopIdsBySequence.add(null);
            }
            stopIdsBySequence.set(sequence, UUID.fromString(stop.get("id").asText()));
        }
        List<UUID> available = new ArrayList<>();
        for (JsonNode seat : body.get("seatInventory")) {
            if ("AVAILABLE".equals(seat.get("physicalStatus").asText())) {
                available.add(UUID.fromString(seat.get("id").asText()));
            }
        }
        assertThat(available).hasSizeGreaterThanOrEqualTo(3);
        return new TripFixture(tripId, stopIdsBySequence, available);
    }

    private UUID createOperator(String legal, String display) throws Exception {
        return idOf(mockMvc.perform(post("/api/v1/admin/operators")
                        .with(adminAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"legalName":"%s","displayName":"%s"}
                                """.formatted(legal, display)))
                .andExpect(status().isCreated())
                .andReturn());
    }

    private UUID createBusType(String code, String name) throws Exception {
        return idOf(mockMvc.perform(post("/api/v1/admin/bus-types")
                        .with(adminAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"%s","displayName":"%s"}
                                """.formatted(code, name)))
                .andExpect(status().isCreated())
                .andReturn());
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
        return idOf(mockMvc.perform(post("/api/v1/admin/seat-layouts")
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
                .andReturn());
    }

    private UUID createBus(UUID operatorId, UUID busTypeId, UUID layoutId, String registration) throws Exception {
        return idOf(mockMvc.perform(post("/api/v1/admin/buses")
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
                .andReturn());
    }

    private UUID createLocation(String state, String city) throws Exception {
        return idOf(mockMvc.perform(post("/api/v1/admin/locations")
                        .with(adminAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "countryCode":"IN",
                                  "state":"%s",
                                  "city":"%s",
                                  "timeZone":"Asia/Kolkata"
                                }
                                """.formatted(state, city)))
                .andExpect(status().isCreated())
                .andReturn());
    }

    private UUID createRoute(UUID operatorId, String code, NetworkFixture network) throws Exception {
        return idOf(mockMvc.perform(post("/api/v1/admin/routes")
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
                                operatorId, code, network.hyderabadId(), network.gunturId(),
                                network.hyderabadId(), network.suryapetId(),
                                network.vijayawadaId(), network.gunturId())))
                .andExpect(status().isCreated())
                .andReturn());
    }

    private UUID idOf(MvcResult result) throws Exception {
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }


    private org.springframework.test.web.servlet.request.RequestPostProcessor adminAuth() {
        return TestAccessTokenFactory.bearer(adminToken);
    }

    private record NetworkFixture(UUID hyderabadId, UUID suryapetId, UUID vijayawadaId, UUID gunturId) {
    }

    private record TripFixture(UUID tripId, List<UUID> stopIdsBySequence, List<UUID> availableSeatIds) {
        UUID stopId(int sequence) {
            return stopIdsBySequence.get(sequence);
        }
    }
}
