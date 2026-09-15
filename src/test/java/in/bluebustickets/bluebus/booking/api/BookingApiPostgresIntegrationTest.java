package in.bluebustickets.bluebus.booking.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import in.bluebustickets.bluebus.booking.domain.BookingStatus;
import in.bluebustickets.bluebus.booking.repository.BookingRepository;
import in.bluebustickets.bluebus.identity.domain.Role;
import in.bluebustickets.bluebus.identity.domain.RoleCode;
import in.bluebustickets.bluebus.identity.domain.User;
import in.bluebustickets.bluebus.identity.domain.UserRole;
import in.bluebustickets.bluebus.identity.repository.RoleRepository;
import in.bluebustickets.bluebus.identity.repository.UserRepository;
import in.bluebustickets.bluebus.identity.repository.UserRoleRepository;
import in.bluebustickets.bluebus.scheduling.domain.SeatHoldStatus;
import in.bluebustickets.bluebus.scheduling.domain.TripSeatAllocationState;
import in.bluebustickets.bluebus.scheduling.repository.SeatHoldRepository;
import in.bluebustickets.bluebus.scheduling.repository.TripSeatAllocationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
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
class BookingApiPostgresIntegrationTest {

    private static final String PASSWORD = "CorrectHorseBatteryStaple!";
    private static final String CUSTOMER_A_EMAIL = "booking-a@example.test";
    private static final String CUSTOMER_B_EMAIL = "booking-b@example.test";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("blue-bus.seat-holds.expiry.enabled", () -> "false");
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRoleRepository userRoleRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private BookingRepository bookingRepository;
    @Autowired private SeatHoldRepository seatHoldRepository;
    @Autowired private TripSeatAllocationRepository allocationRepository;

    private String customerAToken;
    private String customerBToken;

    @BeforeEach
    void seedCustomers() throws Exception {
        bookingRepository.deleteAll();
        userRoleRepository.deleteAll();
        userRepository.deleteAll();

        Role customerRole = roleRepository.findByCode(RoleCode.CUSTOMER).orElseThrow();
        seedCustomer(CUSTOMER_A_EMAIL, "+919933300001", customerRole);
        seedCustomer(CUSTOMER_B_EMAIL, "+919933300002", customerRole);
        customerAToken = loginToken(CUSTOMER_A_EMAIL);
        customerBToken = loginToken(CUSTOMER_B_EMAIL);
    }

    @Test
    @WithMockUser
    void convertsHoldToBookingSuccessfullyIncludingMultiSeat() throws Exception {
        TripFixture trip = createTrip("BOOK-01", "BOOK-RT-01");
        UUID seat1 = trip.availableSeatIds().get(0);
        UUID seat2 = trip.availableSeatIds().get(1);

        JsonNode hold = createHold(trip, List.of(seat1, seat2), customerAToken);
        UUID holdId = UUID.fromString(hold.get("holdId").asText());

        MvcResult created = mockMvc.perform(post("/api/v1/bookings")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingBody(
                                holdId,
                                trip.stopId(1),
                                trip.stopId(3),
                                "idem-multi-1",
                                List.of(
                                        passenger(seat1, "Ada Lovelace", 36),
                                        passenger(seat2, "Alan Turing", 41)))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.passengers.length()").value(2))
                .andExpect(jsonPath("$.totalAmount").value(1800.00))
                .andReturn();

        JsonNode booking = objectMapper.readTree(created.getResponse().getContentAsString());
        UUID bookingId = UUID.fromString(booking.get("bookingId").asText());

        assertThat(seatHoldRepository.findById(holdId).orElseThrow().getStatus())
                .isEqualTo(SeatHoldStatus.CONSUMED);
        assertThat(allocationRepository.findByHoldIdOrderByCreatedAtAsc(holdId))
                .allMatch(a -> a.getState() == TripSeatAllocationState.BOOKED)
                .allMatch(a -> a.getBookingItemId() != null);

        mockMvc.perform(get("/api/v1/bookings/{id}", bookingId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingId").value(bookingId.toString()))
                .andExpect(jsonPath("$.status").value(BookingStatus.PENDING_PAYMENT.name()));
    }

    @Test
    @WithMockUser
    void rejectsExpiredCancelledAndConsumedHolds() throws Exception {
        TripFixture trip = createTrip("BOOK-02", "BOOK-RT-02");
        UUID seat = trip.availableSeatIds().get(0);

        JsonNode expiredHold = createHold(trip, List.of(seat), customerAToken);
        UUID expiredHoldId = UUID.fromString(expiredHold.get("holdId").asText());
        jdbcTemplate.update(
                "UPDATE seat_holds SET expires_at = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(30)),
                expiredHoldId);
        mockMvc.perform(post("/api/v1/bookings")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingBody(
                                expiredHoldId,
                                trip.stopId(1),
                                trip.stopId(3),
                                "idem-expired",
                                List.of(passenger(seat, "Expired User", 20)))))
                .andExpect(status().isConflict());

        UUID cancelSeat = trip.availableSeatIds().get(1);
        JsonNode cancelHold = createHold(trip, List.of(cancelSeat), customerAToken);
        UUID cancelHoldId = UUID.fromString(cancelHold.get("holdId").asText());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/holds/{holdId}", cancelHoldId)
                        .with(anonymous()))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/v1/bookings")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingBody(
                                cancelHoldId,
                                trip.stopId(1),
                                trip.stopId(3),
                                "idem-cancelled",
                                List.of(passenger(cancelSeat, "Cancel User", 21)))))
                .andExpect(status().isConflict());

        UUID consumeSeat = trip.availableSeatIds().get(2);
        JsonNode consumeHold = createHold(trip, List.of(consumeSeat), customerAToken);
        UUID consumeHoldId = UUID.fromString(consumeHold.get("holdId").asText());
        mockMvc.perform(post("/api/v1/bookings")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingBody(
                                consumeHoldId,
                                trip.stopId(1),
                                trip.stopId(3),
                                "idem-consume-1",
                                List.of(passenger(consumeSeat, "First Booker", 22)))))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/bookings")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingBody(
                                consumeHoldId,
                                trip.stopId(1),
                                trip.stopId(3),
                                "idem-consume-2",
                                List.of(passenger(consumeSeat, "Second Booker", 23)))))
                .andExpect(status().isConflict());
    }

    @Test
    @WithMockUser
    void enforcesOwnershipIsolationAndListing() throws Exception {
        TripFixture trip = createTrip("BOOK-03", "BOOK-RT-03");
        UUID seatA = trip.availableSeatIds().get(0);
        UUID seatB = trip.availableSeatIds().get(1);

        JsonNode holdA = createHold(trip, List.of(seatA), customerAToken);
        JsonNode holdB = createHold(trip, List.of(seatB), customerBToken);

        JsonNode bookingA = objectMapper.readTree(mockMvc.perform(post("/api/v1/bookings")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingBody(
                                UUID.fromString(holdA.get("holdId").asText()),
                                trip.stopId(1),
                                trip.stopId(3),
                                "idem-owner-a",
                                List.of(passenger(seatA, "Customer A", 30)))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString());

        JsonNode bookingB = objectMapper.readTree(mockMvc.perform(post("/api/v1/bookings")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerBToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingBody(
                                UUID.fromString(holdB.get("holdId").asText()),
                                trip.stopId(1),
                                trip.stopId(3),
                                "idem-owner-b",
                                List.of(passenger(seatB, "Customer B", 31)))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString());

        UUID bookingAId = UUID.fromString(bookingA.get("bookingId").asText());
        UUID bookingBId = UUID.fromString(bookingB.get("bookingId").asText());

        mockMvc.perform(get("/api/v1/bookings/{id}", bookingBId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerAToken))
                .andExpect(status().isNotFound());

        MvcResult listA = mockMvc.perform(get("/api/v1/bookings")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerAToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode listed = objectMapper.readTree(listA.getResponse().getContentAsString());
        assertThat(listed).hasSize(1);
        assertThat(listed.get(0).get("bookingId").asText()).isEqualTo(bookingAId.toString());
    }

    @Test
    @WithMockUser
    void idempotencyReturnsSameBookingAndRejectsConflicts() throws Exception {
        TripFixture trip = createTrip("BOOK-04", "BOOK-RT-04");
        UUID seat = trip.availableSeatIds().get(0);
        JsonNode hold = createHold(trip, List.of(seat), customerAToken);
        UUID holdId = UUID.fromString(hold.get("holdId").asText());
        String body = bookingBody(
                holdId,
                trip.stopId(1),
                trip.stopId(3),
                "idem-same",
                List.of(passenger(seat, "Idem User", 25)));

        JsonNode first = objectMapper.readTree(mockMvc.perform(post("/api/v1/bookings")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString());

        JsonNode second = objectMapper.readTree(mockMvc.perform(post("/api/v1/bookings")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString());

        assertThat(second.get("bookingId").asText()).isEqualTo(first.get("bookingId").asText());
        assertThat(second.get("passengers").size()).isEqualTo(1);
        assertThat(bookingRepository.count()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM booking_passengers", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM booking_items", Integer.class))
                .isEqualTo(1);

        UUID otherSeat = trip.availableSeatIds().get(1);
        JsonNode otherHold = createHold(trip, List.of(otherSeat), customerAToken);
        mockMvc.perform(post("/api/v1/bookings")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingBody(
                                UUID.fromString(otherHold.get("holdId").asText()),
                                trip.stopId(1),
                                trip.stopId(3),
                                "idem-same",
                                List.of(passenger(otherSeat, "Different Request", 26)))))
                .andExpect(status().isConflict());
    }

    @Test
    @WithMockUser
    void concurrentSameIdempotencyKeyCreatesOneBooking() throws Exception {
        TripFixture trip = createTrip("BOOK-05", "BOOK-RT-05");
        UUID seat = trip.availableSeatIds().get(0);
        JsonNode hold = createHold(trip, List.of(seat), customerAToken);
        UUID holdId = UUID.fromString(hold.get("holdId").asText());
        String body = bookingBody(
                holdId,
                trip.stopId(1),
                trip.stopId(3),
                "idem-concurrent",
                List.of(passenger(seat, "Concurrent User", 27)));

        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger created = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        AtomicInteger unexpected = new AtomicInteger();
        AtomicReference<String> bookingId = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    MvcResult result = mockMvc.perform(post("/api/v1/bookings")
                                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerAToken)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body))
                            .andReturn();
                    int statusCode = result.getResponse().getStatus();
                    if (statusCode == 201) {
                        created.incrementAndGet();
                        String id = objectMapper
                                .readTree(result.getResponse().getContentAsString())
                                .get("bookingId")
                                .asText();
                        bookingId.compareAndSet(null, id);
                        assertThat(bookingId.get()).isEqualTo(id);
                    } else if (statusCode == 409) {
                        conflicts.incrementAndGet();
                    } else {
                        unexpected.incrementAndGet();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(unexpected.get()).isZero();
        assertThat(created.get()).isEqualTo(2);
        assertThat(conflicts.get()).isZero();
        assertThat(bookingRepository.count()).isEqualTo(1);
        assertThat(bookingId.get()).isNotBlank();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM booking_passengers", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM booking_items", Integer.class))
                .isEqualTo(1);
        assertThat(seatHoldRepository.findById(holdId).orElseThrow().getStatus())
                .isEqualTo(SeatHoldStatus.CONSUMED);
    }

    @Test
    @WithMockUser
    void customerCannotBookAnotherCustomersHold() throws Exception {
        TripFixture trip = createTrip("BOOK-OWN-01", "BOOK-OWN-RT-01");
        UUID seat = trip.availableSeatIds().get(0);
        JsonNode holdA = createHold(trip, List.of(seat), customerAToken);
        UUID holdId = UUID.fromString(holdA.get("holdId").asText());

        mockMvc.perform(post("/api/v1/bookings")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerBToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingBody(
                                holdId,
                                trip.stopId(1),
                                trip.stopId(3),
                                "idem-steal",
                                List.of(passenger(seat, "Thief", 40)))))
                .andExpect(status().isNotFound());

        assertThat(bookingRepository.count()).isZero();
        assertThat(seatHoldRepository.findById(holdId).orElseThrow().getStatus())
                .isEqualTo(SeatHoldStatus.ACTIVE);
        assertThat(seatHoldRepository.findById(holdId).orElseThrow().getUserId()).isNotNull();
    }

    @Test
    @WithMockUser
    void anonymousHoldCannotBeBooked() throws Exception {
        TripFixture trip = createTrip("BOOK-ANON-01", "BOOK-ANON-RT-01");
        UUID seat = trip.availableSeatIds().get(0);
        JsonNode anonymousHold = createHoldAnonymously(trip, List.of(seat));
        UUID holdId = UUID.fromString(anonymousHold.get("holdId").asText());
        assertThat(seatHoldRepository.findById(holdId).orElseThrow().getUserId()).isNull();

        mockMvc.perform(post("/api/v1/bookings")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingBody(
                                holdId,
                                trip.stopId(1),
                                trip.stopId(3),
                                "idem-anon",
                                List.of(passenger(seat, "Anon Booker", 33)))))
                .andExpect(status().isConflict());

        assertThat(bookingRepository.count()).isZero();
        assertThat(seatHoldRepository.findById(holdId).orElseThrow().getStatus())
                .isEqualTo(SeatHoldStatus.ACTIVE);
    }

    @Test
    @WithMockUser
    void overlappingAllocationStillProtectedAfterBooking() throws Exception {
        TripFixture trip = createTrip("BOOK-06", "BOOK-RT-06");
        UUID seat = trip.availableSeatIds().get(0);
        JsonNode hold = createHold(trip, List.of(seat), customerAToken);
        mockMvc.perform(post("/api/v1/bookings")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingBody(
                                UUID.fromString(hold.get("holdId").asText()),
                                trip.stopId(1),
                                trip.stopId(3),
                                "idem-overlap",
                                List.of(passenger(seat, "Overlap User", 28)))))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/trips/{tripId}/holds", trip.tripId())
                        .with(anonymous())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "originStopId":"%s",
                                  "destinationStopId":"%s",
                                  "seatInventoryIds":["%s"]
                                }
                                """.formatted(trip.stopId(1), trip.stopId(3), seat)))
                .andExpect(status().isConflict());
    }

    @Test
    void bookingsRequireAuthentication() throws Exception {
        mockMvc.perform(post("/api/v1/bookings")
                        .with(anonymous())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/bookings").with(anonymous()))
                .andExpect(status().isUnauthorized());
    }

    private void seedCustomer(String email, String phone, Role customerRole) {
        User user = new User(email, phone, "Book", "Customer");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user = userRepository.saveAndFlush(user);
        userRoleRepository.saveAndFlush(new UserRole(user, customerRole));
    }

    private String loginToken(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}
                                """.formatted(email, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
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
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
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

    private JsonNode createHoldAnonymously(TripFixture trip, List<UUID> seats) throws Exception {
        StringBuilder seatJson = new StringBuilder("[");
        for (int i = 0; i < seats.size(); i++) {
            if (i > 0) {
                seatJson.append(',');
            }
            seatJson.append('"').append(seats.get(i)).append('"');
        }
        seatJson.append(']');
        MvcResult result = mockMvc.perform(post("/api/v1/trips/{tripId}/holds", trip.tripId())
                        .with(anonymous())
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

    private static String bookingBody(
            UUID holdId,
            UUID originStopId,
            UUID destinationStopId,
            String idempotencyKey,
            List<String> passengersJson) {
        return """
                {
                  "holdId":"%s",
                  "originStopId":"%s",
                  "destinationStopId":"%s",
                  "idempotencyKey":"%s",
                  "passengers":[%s]
                }
                """.formatted(
                holdId,
                originStopId,
                destinationStopId,
                idempotencyKey,
                String.join(",", passengersJson));
    }

    private static String passenger(UUID seatInventoryId, String fullName, int age) {
        return """
                {"seatInventoryId":"%s","fullName":"%s","age":%d}
                """.formatted(seatInventoryId, fullName, age);
    }

    private TripFixture createTrip(String registration, String routeCode) throws Exception {
        Fixture fixture = createFixture(registration, routeCode, 4);
        Instant departure = Instant.parse("2026-12-01T10:00:00Z");
        Instant arrival = departure.plusSeconds(6 * 3600);
        Instant opens = departure.minusSeconds(7 * 24 * 3600);
        Instant closes = departure.minusSeconds(3600);

        MvcResult created = mockMvc.perform(post("/api/v1/admin/trips")
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

    private Fixture createFixture(String registration, String routeCode, int seatCount) throws Exception {
        UUID operatorId = createOperator("Book Op " + registration, "Book Co " + registration);
        UUID busTypeId = createBusType("BTYPE_" + registration.replace(" ", ""), "Type " + registration);
        UUID layoutId = createSeatLayout(operatorId, "Layout " + registration, 1, seatCount);
        UUID busId = createBus(operatorId, busTypeId, layoutId, registration);
        UUID hyderabadId = createLocation("Telangana", "Hyderabad-" + registration);
        UUID suryapetId = createLocation("Telangana", "Suryapet-" + registration);
        UUID vijayawadaId = createLocation("Andhra Pradesh", "Vijayawada-" + registration);
        UUID gunturId = createLocation("Andhra Pradesh", "Guntur-" + registration);
        UUID routeId = createRoute(operatorId, routeCode, hyderabadId, suryapetId, vijayawadaId, gunturId);
        return new Fixture(busId, routeId);
    }

    private UUID createOperator(String legal, String display) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/admin/operators")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"legalName":"%s","displayName":"%s"}
                                """.formatted(legal, display)))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private UUID createBusType(String code, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/admin/bus-types")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"%s","displayName":"%s"}
                                """.formatted(code, name)))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
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
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private UUID createBus(UUID operatorId, UUID busTypeId, UUID layoutId, String registration) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/admin/buses")
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

    private record Fixture(UUID busId, UUID routeId) {
    }

    private record TripFixture(UUID tripId, List<UUID> stopIdsBySequence, List<UUID> availableSeatIds) {
        UUID stopId(int sequence) {
            return stopIdsBySequence.get(sequence);
        }
    }
}
