package in.bluebustickets.bluebus.operator.api;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import in.bluebustickets.bluebus.foundation.security.TestAccessTokenFactory;
import in.bluebustickets.bluebus.foundation.security.TestAccessTokenFactory.IssuedOperatorMember;
import in.bluebustickets.bluebus.foundation.security.TestAccessTokenFactory.IssuedUser;
import in.bluebustickets.bluebus.identity.domain.RoleCode;
import in.bluebustickets.bluebus.operator.domain.Operator;
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

import static in.bluebustickets.bluebus.foundation.security.TestAccessTokenFactory.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OperatorPortalResourcePostgresIntegrationTest {

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
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TestAccessTokenFactory tokens;

    private String platformAdminToken;

    @BeforeEach
    void seedPlatformAdmin() {
        platformAdminToken = tokens.issuePlatformAdmin().accessToken();
    }

    @Test
    void operatorSeesOnlyOwnBusesAndCannotCrossRead() throws Exception {
        IssuedOperatorMember operatorA = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_ADMIN, List.of("OPERATOR_ADMIN"));
        IssuedOperatorMember operatorB = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_STAFF, List.of("OPERATOR_STAFF"));
        UUID busA = createBus(operatorA.operator().getId(), unique("BA"));
        UUID busB = createBus(operatorB.operator().getId(), unique("BB"));

        mockMvc.perform(get("/api/v1/operator/{id}/buses", operatorA.operator().getId())
                        .with(bearer(operatorA.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='%s')]".formatted(busA)).exists())
                .andExpect(jsonPath("$[?(@.id=='%s')]".formatted(busB)).isEmpty());

        mockMvc.perform(get("/api/v1/operator/{id}/buses/{busId}", operatorA.operator().getId(), busA)
                        .with(bearer(operatorA.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(busA.toString()))
                .andExpect(jsonPath("$.operatorId").value(operatorA.operator().getId().toString()));

        mockMvc.perform(get("/api/v1/operator/{id}/buses/{busId}", operatorA.operator().getId(), busB)
                        .with(bearer(operatorA.accessToken())))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/operator/{id}/buses/{busId}", operatorB.operator().getId(), busA)
                        .with(bearer(operatorA.accessToken())))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/operator/{id}/buses/{busId}", operatorB.operator().getId(), busA)
                        .with(bearer(operatorB.accessToken())))
                .andExpect(status().isNotFound());
    }

    @Test
    void operatorSeesOnlyOwnTripsAndCannotCrossRead() throws Exception {
        IssuedOperatorMember operatorA = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_ADMIN, List.of("OPERATOR_ADMIN"));
        IssuedOperatorMember operatorB = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_STAFF, List.of("OPERATOR_STAFF"));
        TripFixture tripA = createTrip(operatorA.operator().getId(), unique("TA"));
        TripFixture tripB = createTrip(operatorB.operator().getId(), unique("TB"));

        mockMvc.perform(get("/api/v1/operator/{id}/trips", operatorA.operator().getId())
                        .with(bearer(operatorA.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='%s')]".formatted(tripA.tripId())).exists())
                .andExpect(jsonPath("$[?(@.id=='%s')]".formatted(tripB.tripId())).isEmpty());

        mockMvc.perform(get("/api/v1/operator/{id}/trips/{tripId}", operatorA.operator().getId(), tripA.tripId())
                        .with(bearer(operatorA.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(tripA.tripId().toString()))
                .andExpect(jsonPath("$.operatorId").value(operatorA.operator().getId().toString()));

        mockMvc.perform(get("/api/v1/operator/{id}/trips/{tripId}", operatorA.operator().getId(), tripB.tripId())
                        .with(bearer(operatorA.accessToken())))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/operator/{id}/trips/{tripId}", operatorB.operator().getId(), tripA.tripId())
                        .with(bearer(operatorA.accessToken())))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/operator/{id}/trips/{tripId}", operatorB.operator().getId(), tripA.tripId())
                        .with(bearer(operatorB.accessToken())))
                .andExpect(status().isNotFound());
    }

    @Test
    void multiOperatorMemberCannotReadOperatorAResourceThroughOperatorB() throws Exception {
        IssuedOperatorMember member = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_ADMIN, List.of("OPERATOR_ADMIN"));
        Operator operatorB = tokens.persistActiveOperator();
        tokens.attachMembership(operatorB, member.user(), RoleCode.OPERATOR_ADMIN);
        UUID busA = createBus(member.operator().getId(), unique("MAB"));
        TripFixture tripA = createTrip(member.operator().getId(), unique("MAT"));

        mockMvc.perform(get("/api/v1/operator/{id}/buses/{busId}", operatorB.getId(), busA)
                        .with(bearer(member.accessToken())))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/operator/{id}/trips/{tripId}", operatorB.getId(), tripA.tripId())
                        .with(bearer(member.accessToken())))
                .andExpect(status().isNotFound());
    }

    @Test
    void operatorSeesOnlyOwnTripBookingsAndPassengerManifestIsScoped() throws Exception {
        IssuedOperatorMember operatorA = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_ADMIN, List.of("OPERATOR_ADMIN"));
        IssuedOperatorMember operatorB = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_STAFF, List.of("OPERATOR_STAFF"));
        IssuedUser customerA = tokens.issueCustomer();
        IssuedUser customerB = tokens.issueCustomer();

        TripFixture tripA1 = createTrip(operatorA.operator().getId(), unique("A1"));
        TripFixture tripA2 = createTrip(operatorA.operator().getId(), unique("A2"));
        TripFixture tripB = createTrip(operatorB.operator().getId(), unique("B1"));

        UUID bookingA = bookSeat(tripA1, tripA1.availableSeatIds().get(0), customerA.accessToken(), unique("idem-a"));
        UUID bookingB = bookSeat(tripB, tripB.availableSeatIds().get(0), customerB.accessToken(), unique("idem-b"));

        mockMvc.perform(get(
                        "/api/v1/operator/{id}/trips/{tripId}/bookings",
                        operatorA.operator().getId(),
                        tripA1.tripId())
                        .with(bearer(operatorA.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].bookingId").value(bookingA.toString()))
                .andExpect(jsonPath("$[0].passengers[0].fullName").value("Passenger"))
                .andExpect(jsonPath("$[0].holdId").doesNotExist())
                .andExpect(jsonPath("$[0].userId").doesNotExist())
                .andExpect(jsonPath("$[0].idempotencyKey").doesNotExist())
                .andExpect(jsonPath("$[0].paymentExpiresAt").doesNotExist())
                .andExpect(jsonPath("$[0].taxAmount").doesNotExist())
                .andExpect(jsonPath("$[0].checkoutUrl").doesNotExist())
                .andExpect(jsonPath("$[0].paymentAttemptId").doesNotExist())
                .andExpect(jsonPath("$[0].refunds").doesNotExist())
                .andExpect(jsonPath("$[0].email").doesNotExist())
                .andExpect(jsonPath("$[0].phoneE164").doesNotExist())
                .andExpect(jsonPath("$[0].items[0].inventoryId").doesNotExist())
                .andExpect(jsonPath("$[0].items[0].seatInventoryId").doesNotExist())
                .andExpect(jsonPath("$[0].passengers[0].email").doesNotExist());

        mockMvc.perform(get(
                        "/api/v1/operator/{id}/trips/{tripId}/bookings/{bookingId}",
                        operatorA.operator().getId(),
                        tripA1.tripId(),
                        bookingA)
                        .with(bearer(operatorA.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingReference").exists())
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.totalAmount").exists())
                .andExpect(jsonPath("$.trip.tripId").value(tripA1.tripId().toString()))
                .andExpect(jsonPath("$.items[0].seatNumber").exists())
                .andExpect(jsonPath("$.passengers[0].age").value(30));

        mockMvc.perform(get(
                        "/api/v1/operator/{id}/trips/{tripId}/bookings",
                        operatorA.operator().getId(),
                        tripB.tripId())
                        .with(bearer(operatorA.accessToken())))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(
                        "/api/v1/operator/{id}/trips/{tripId}/bookings/{bookingId}",
                        operatorA.operator().getId(),
                        tripA1.tripId(),
                        bookingB)
                        .with(bearer(operatorA.accessToken())))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(
                        "/api/v1/operator/{id}/trips/{tripId}/bookings/{bookingId}",
                        operatorB.operator().getId(),
                        tripB.tripId(),
                        bookingA)
                        .with(bearer(operatorA.accessToken())))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(
                        "/api/v1/operator/{id}/trips/{tripId}/bookings/{bookingId}",
                        operatorB.operator().getId(),
                        tripA1.tripId(),
                        bookingA)
                        .with(bearer(operatorB.accessToken())))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(
                        "/api/v1/operator/{id}/trips/{tripId}/bookings/{bookingId}",
                        operatorA.operator().getId(),
                        tripA2.tripId(),
                        bookingA)
                        .with(bearer(operatorA.accessToken())))
                .andExpect(status().isNotFound());

        String denied = mockMvc.perform(get(
                        "/api/v1/operator/{id}/trips/{tripId}/bookings/{bookingId}",
                        operatorB.operator().getId(),
                        tripB.tripId(),
                        bookingA)
                        .with(bearer(operatorB.accessToken())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Resource was not found."))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(denied).doesNotContain("Passenger");
        assertThat(denied).doesNotContain(customerA.user().getEmail());

        mockMvc.perform(get("/api/v1/bookings/{id}", bookingA).with(bearer(customerB.accessToken())))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/bookings/{id}", bookingA).with(bearer(customerA.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingId").value(bookingA.toString()));
        mockMvc.perform(get("/api/v1/payments/{id}", UUID.randomUUID()).with(bearer(customerB.accessToken())))
                .andExpect(status().isNotFound());
    }

    @Test
    void bookingWithDisagreeingOperatorIdsIsNotExposed() throws Exception {
        IssuedOperatorMember operatorA = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_ADMIN, List.of("OPERATOR_ADMIN"));
        IssuedOperatorMember operatorB = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_STAFF, List.of("OPERATOR_STAFF"));
        IssuedUser customer = tokens.issueCustomer();
        TripFixture tripA = createTrip(operatorA.operator().getId(), unique("MX"));
        TripFixture tripB = createTrip(operatorB.operator().getId(), unique("MY"));
        UUID bookingId = bookSeat(tripA, tripA.availableSeatIds().get(0), customer.accessToken(), unique("idem-mx"));

        jdbcTemplate.update(
                "UPDATE bookings SET operator_id = ? WHERE id = ?",
                operatorB.operator().getId(),
                bookingId);

        mockMvc.perform(get(
                        "/api/v1/operator/{id}/trips/{tripId}/bookings",
                        operatorA.operator().getId(),
                        tripA.tripId())
                        .with(bearer(operatorA.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(get(
                        "/api/v1/operator/{id}/trips/{tripId}/bookings/{bookingId}",
                        operatorA.operator().getId(),
                        tripA.tripId(),
                        bookingId)
                        .with(bearer(operatorA.accessToken())))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(
                        "/api/v1/operator/{id}/trips/{tripId}/bookings/{bookingId}",
                        operatorB.operator().getId(),
                        tripA.tripId(),
                        bookingId)
                        .with(bearer(operatorB.accessToken())))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(
                        "/api/v1/operator/{id}/trips/{tripId}/bookings/{bookingId}",
                        operatorB.operator().getId(),
                        tripB.tripId(),
                        bookingId)
                        .with(bearer(operatorB.accessToken())))
                .andExpect(status().isNotFound());
    }

    private UUID bookSeat(TripFixture trip, UUID seat, String bearerToken, String idempotencyKey) throws Exception {
        JsonNode hold = createHold(trip, List.of(seat), bearerToken);
        JsonNode booking = objectMapper.readTree(mockMvc.perform(post("/api/v1/bookings")
                        .with(bearer(bearerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "holdId":"%s",
                                  "originStopId":"%s",
                                  "destinationStopId":"%s",
                                  "idempotencyKey":"%s",
                                  "passengers":[
                                    {"seatInventoryId":"%s","fullName":"Passenger","age":30}
                                  ]
                                }
                                """.formatted(
                                hold.get("holdId").asText(),
                                trip.stopId(1),
                                trip.stopId(3),
                                idempotencyKey,
                                seat)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString());
        return UUID.fromString(booking.get("bookingId").asText());
    }

    private JsonNode createHold(TripFixture trip, List<UUID> seats, String bearerToken) throws Exception {
        StringBuilder seatJson = new StringBuilder("[");
        for (int i = 0; i < seats.size(); i++) {
            if (i > 0) {
                seatJson.append(',');
            }
            seatJson.append('"').append(seats.get(i)).append('"');
        }
        seatJson.append(']');
        MvcResult result = mockMvc.perform(post("/api/v1/trips/{tripId}/holds", trip.tripId())
                        .with(bearer(bearerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "originStopId":"%s",
                                  "destinationStopId":"%s",
                                  "seatInventoryIds":%s
                                }
                                """.formatted(trip.stopId(1), trip.stopId(3), seatJson)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private UUID createBus(UUID operatorId, String suffix) throws Exception {
        UUID busTypeId = createBusType("BT" + suffix, "Type " + suffix);
        UUID layoutId = createSeatLayout(operatorId, "Layout " + suffix, 4);
        return createBus(operatorId, busTypeId, layoutId, "REG" + suffix);
    }

    private TripFixture createTrip(UUID operatorId, String suffix) throws Exception {
        UUID busTypeId = createBusType("TT" + suffix, "Trip Type " + suffix);
        UUID layoutId = createSeatLayout(operatorId, "Trip Layout " + suffix, 4);
        UUID busId = createBus(operatorId, busTypeId, layoutId, "TR" + suffix);
        UUID hyderabadId = createLocation("Telangana", "Hyd-" + suffix);
        UUID suryapetId = createLocation("Telangana", "Sur-" + suffix);
        UUID vijayawadaId = createLocation("Andhra Pradesh", "Vja-" + suffix);
        UUID gunturId = createLocation("Andhra Pradesh", "Gnt-" + suffix);
        UUID routeId = createRoute(operatorId, "RT" + suffix, hyderabadId, suryapetId, vijayawadaId, gunturId);

        Instant departure = Instant.parse("2026-12-01T10:00:00Z");
        Instant arrival = departure.plusSeconds(6 * 3600);
        Instant opens = departure.minusSeconds(7 * 24 * 3600);
        Instant closes = departure.minusSeconds(3600);

        MvcResult created = mockMvc.perform(post("/api/v1/admin/trips")
                        .with(bearer(platformAdminToken))
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
        assertThat(available).hasSizeGreaterThanOrEqualTo(1);
        return new TripFixture(tripId, stopIdsBySequence, available);
    }

    private UUID createBusType(String code, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/admin/bus-types")
                        .with(bearer(platformAdminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"%s","displayName":"%s"}
                                """.formatted(code, name)))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private UUID createSeatLayout(UUID operatorId, String name, int seatCount) throws Exception {
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
                        .with(bearer(platformAdminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "operatorId":"%s",
                                  "name":"%s",
                                  "version":1,
                                  "deckCount":1,
                                  "rowCount":2,
                                  "columnCount":2,
                                  "seats":%s
                                }
                                """.formatted(operatorId, name, seats)))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private UUID createBus(UUID operatorId, UUID busTypeId, UUID layoutId, String registration) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/admin/buses")
                        .with(bearer(platformAdminToken))
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
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private UUID createLocation(String state, String city) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/admin/locations")
                        .with(bearer(platformAdminToken))
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
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private UUID createRoute(
            UUID operatorId,
            String code,
            UUID hyderabadId,
            UUID suryapetId,
            UUID vijayawadaId,
            UUID gunturId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/admin/routes")
                        .with(bearer(platformAdminToken))
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
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private static String unique(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private record TripFixture(UUID tripId, List<UUID> stopIdsBySequence, List<UUID> availableSeatIds) {
        UUID stopId(int sequence) {
            return stopIdsBySequence.get(sequence);
        }
    }
}
