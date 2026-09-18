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

import in.bluebustickets.bluebus.foundation.security.TestAccessTokenFactory;
import in.bluebustickets.bluebus.booking.application.BookingExpiryService;
import in.bluebustickets.bluebus.booking.application.BookingLifecycleService;
import in.bluebustickets.bluebus.booking.domain.BookingStatus;
import in.bluebustickets.bluebus.booking.repository.BookingCancellationRepository;
import in.bluebustickets.bluebus.booking.repository.BookingRepository;
import in.bluebustickets.bluebus.foundation.api.error.ApplicationConflictException;
import in.bluebustickets.bluebus.scheduling.application.JourneySeatAvailability;
import in.bluebustickets.bluebus.scheduling.application.SeatAvailabilityService;
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
        registry.add("blue-bus.bookings.expiry.enabled", () -> "false");
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRoleRepository userRoleRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private BookingRepository bookingRepository;
    @Autowired private BookingCancellationRepository cancellationRepository;
    @Autowired private SeatHoldRepository seatHoldRepository;
    @Autowired private TripSeatAllocationRepository allocationRepository;
    @Autowired private BookingLifecycleService bookingLifecycleService;
    @Autowired private BookingExpiryService bookingExpiryService;
    @Autowired private SeatAvailabilityService seatAvailabilityService;
    @Autowired private TestAccessTokenFactory testAccessTokenFactory;
    private String adminToken;

    private String customerAToken;
    private String customerBToken;

    @BeforeEach
    void seedCustomers() throws Exception {
        cancellationRepository.deleteAll();
        bookingRepository.deleteAll();
        userRoleRepository.deleteAll();
        userRepository.deleteAll();

        Role customerRole = roleRepository.findByCode(RoleCode.CUSTOMER).orElseThrow();
        seedCustomer(CUSTOMER_A_EMAIL, "+919933300001", customerRole);
        seedCustomer(CUSTOMER_B_EMAIL, "+919933300002", customerRole);
        customerAToken = loginToken(CUSTOMER_A_EMAIL);
        customerBToken = loginToken(CUSTOMER_B_EMAIL);
        adminToken = testAccessTokenFactory.issuePlatformAdmin().accessToken();
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
                .andExpect(jsonPath("$.paymentExpiresAt").exists())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.passengers.length()").value(2))
                .andExpect(jsonPath("$.totalAmount").value(1800.00))
                .andReturn();

        JsonNode booking = objectMapper.readTree(created.getResponse().getContentAsString());
        UUID bookingId = UUID.fromString(booking.get("bookingId").asText());
        Instant paymentExpiresAt = Instant.parse(booking.get("paymentExpiresAt").asText());
        assertThat(paymentExpiresAt).isAfter(Instant.parse(booking.get("createdAt").asText()));
        assertThat(paymentExpiresAt).isBefore(Instant.now().plusSeconds(960));

        assertThat(seatHoldRepository.findById(holdId).orElseThrow().getStatus())
                .isEqualTo(SeatHoldStatus.CONSUMED);
        assertThat(allocationRepository.findByHoldIdOrderByCreatedAtAsc(holdId))
                .allMatch(a -> a.getState() == TripSeatAllocationState.BOOKED)
                .allMatch(a -> a.getBookingItemId() != null);

        mockMvc.perform(get("/api/v1/bookings/{id}", bookingId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingId").value(bookingId.toString()))
                .andExpect(jsonPath("$.status").value(BookingStatus.PENDING_PAYMENT.name()))
                .andExpect(jsonPath("$.trip.tripId").value(trip.tripId().toString()))
                .andExpect(jsonPath("$.trip.origin.sequenceNumber").value(1))
                .andExpect(jsonPath("$.trip.destination.sequenceNumber").value(3))
                .andExpect(jsonPath("$.trip.origin.points[0].pointType").value("BOARDING"))
                .andExpect(jsonPath("$.items[0].seatNumber").exists())
                .andExpect(jsonPath("$.items[0].status").value("ACTIVE"));
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
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerAToken))
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
        assertThat(listed.get(0).get("trip").get("tripId").asText()).isEqualTo(trip.tripId().toString());
        assertThat(listed.get(0).path("webhookPayload").isMissingNode()).isTrue();
    }

    @Test
    @WithMockUser
    void bookingAfterWindowCloseLeavesHoldUntouched() throws Exception {
        TripFixture trip = createTrip("BOOK-SALE-01", "BOOK-SALE-RT-01");
        UUID seat = trip.availableSeatIds().get(0);
        JsonNode hold = createHold(trip, List.of(seat), customerAToken);
        UUID holdId = UUID.fromString(hold.get("holdId").asText());

        jdbcTemplate.update(
                "UPDATE trips SET booking_closes_at = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(60)),
                trip.tripId());

        long bookingsBefore = bookingRepository.count();
        mockMvc.perform(post("/api/v1/bookings")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingBody(
                                holdId,
                                trip.stopId(1),
                                trip.stopId(3),
                                "idem-sale-closed",
                                List.of(passenger(seat, "Late Booker", 33)))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value("Booking is closed."));

        assertThat(bookingRepository.count()).isEqualTo(bookingsBefore);
        assertThat(seatHoldRepository.findById(holdId).orElseThrow().getStatus())
                .isEqualTo(SeatHoldStatus.ACTIVE);
        assertThat(allocationRepository.findByHoldIdOrderByCreatedAtAsc(holdId))
                .isNotEmpty()
                .allMatch(allocation -> allocation.getState() == TripSeatAllocationState.HELD);
    }

    @Test
    @WithMockUser
    void bookingDuringWindowStillWorksAndIdempotentReplaySurvivesClose() throws Exception {
        TripFixture trip = createTrip("BOOK-SALE-02", "BOOK-SALE-RT-02");
        UUID seat = trip.availableSeatIds().get(0);
        JsonNode hold = createHold(trip, List.of(seat), customerAToken);
        UUID holdId = UUID.fromString(hold.get("holdId").asText());
        String body = bookingBody(
                holdId,
                trip.stopId(1),
                trip.stopId(3),
                "idem-sale-replay",
                List.of(passenger(seat, "On Time", 28)));

        MvcResult created = mockMvc.perform(post("/api/v1/bookings")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andReturn();
        UUID bookingId = UUID.fromString(
                objectMapper.readTree(created.getResponse().getContentAsString()).get("bookingId").asText());

        jdbcTemplate.update(
                "UPDATE trips SET booking_closes_at = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(60)),
                trip.tripId());

        MvcResult replay = mockMvc.perform(post("/api/v1/bookings")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        assertThat(objectMapper.readTree(replay.getResponse().getContentAsString()).get("bookingId").asText())
                .isEqualTo(bookingId.toString());
        assertThat(bookingRepository.findByHoldId(holdId).orElseThrow().getId()).isEqualTo(bookingId);
    }

    @Test
    @WithMockUser
    void ownerCanCancelUnpaidBookingAndRepeatIsIdempotent() throws Exception {
        TripFixture trip = createTrip("BOOK-CANCEL-01", "BOOK-CANCEL-RT-01");
        UUID seat = trip.availableSeatIds().get(0);
        JsonNode hold = createHold(trip, List.of(seat), customerAToken);
        UUID bookingId = UUID.fromString(objectMapper.readTree(mockMvc.perform(post("/api/v1/bookings")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingBody(
                                UUID.fromString(hold.get("holdId").asText()),
                                trip.stopId(1),
                                trip.stopId(3),
                                "idem-cancel-1",
                                List.of(passenger(seat, "Cancel User", 29)))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString()).get("bookingId").asText());

        assertThat(seatAvailabilityService.getSeatAvailability(trip.tripId(), 1, 3).stream()
                .filter(result -> result.inventoryId().equals(seat))
                .findFirst()
                .orElseThrow()
                .journeyAvailability()).isEqualTo(JourneySeatAvailability.UNAVAILABLE);

        mockMvc.perform(post("/api/v1/bookings/{id}/cancel", bookingId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"Changed plans"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingId").value(bookingId.toString()))
                .andExpect(jsonPath("$.previousStatus").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.policyCode").value("UNPAID_CUSTOMER_CANCELLATION_V1"))
                .andExpect(jsonPath("$.refundableAmount").value(0.00))
                .andExpect(jsonPath("$.booking.status").value("CANCELLED"))
                .andExpect(jsonPath("$.booking.items[0].status").value("CANCELLED"));

        mockMvc.perform(post("/api/v1/bookings/{id}/cancel", bookingId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.booking.status").value("CANCELLED"));

        assertThat(cancellationRepository.count()).isEqualTo(1);
        assertThat(allocationRepository.findByHoldIdOrderByCreatedAtAsc(
                        UUID.fromString(hold.get("holdId").asText())))
                .allMatch(a -> a.getState() == TripSeatAllocationState.CANCELLED);
        assertThat(seatAvailabilityService.getSeatAvailability(trip.tripId(), 1, 3).stream()
                .filter(result -> result.inventoryId().equals(seat))
                .findFirst()
                .orElseThrow()
                .journeyAvailability()).isEqualTo(JourneySeatAvailability.AVAILABLE);
        mockMvc.perform(get("/api/v1/bookings/{id}", bookingId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.passengers.length()").value(1));
    }

    @Test
    @WithMockUser
    void cancellationRejectsOtherCustomersAndUnsupportedStates() throws Exception {
        TripFixture trip = createTrip("BOOK-CANCEL-02", "BOOK-CANCEL-RT-02");
        UUID seatB = trip.availableSeatIds().get(1);
        UUID seatC = trip.availableSeatIds().get(2);

        UUID ownedByB = bookSeat(trip, seatB, customerBToken, "idem-cancel-other");
        UUID confirmed = bookSeat(trip, seatC, customerAToken, "idem-cancel-confirmed");
        bookingLifecycleService.confirmPendingPayment(confirmed);

        mockMvc.perform(post("/api/v1/bookings/{id}/cancel", ownedByB)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());

        // Lifecycle confirm without a SUCCEEDED payment is not refund-eligible.
        mockMvc.perform(post("/api/v1/bookings/{id}/cancel", confirmed)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict());

        TripFixture expiredTrip = createTrip("BOOK-CANCEL-03", "BOOK-CANCEL-RT-03");
        UUID expired = bookSeat(
                expiredTrip, expiredTrip.availableSeatIds().get(0), customerAToken, "idem-cancel-expired");
        forcePaymentExpiresAt(expired, Instant.now().minusSeconds(30));
        bookingExpiryService.expireDueBookings(Instant.now());
        mockMvc.perform(post("/api/v1/bookings/{id}/cancel", expired)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict());
        assertThat(allocationRepository.findByHoldIdOrderByCreatedAtAsc(
                        bookingRepository.findById(expired).orElseThrow().getHoldId()))
                .allMatch(a -> a.getState() == TripSeatAllocationState.RELEASED);
    }

    @Test
    @WithMockUser
    void cancellationVersusExpiryRaceProducesOneValidFinalState() throws Exception {
        TripFixture trip = createTrip("BOOK-CANCEL-RACE-01", "BOOK-CANCEL-RACE-RT-01");
        UUID bookingId = bookSeat(trip, trip.availableSeatIds().get(0), customerAToken, "idem-cancel-expiry-race");
        forcePaymentExpiresAt(bookingId, Instant.now().minusSeconds(20));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger cancelStatus = new AtomicInteger();
        AtomicReference<String> cancelError = new AtomicReference<>();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> cancelFuture = executor.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    cancelStatus.set(mockMvc.perform(post("/api/v1/bookings/{id}/cancel", bookingId)
                                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerAToken)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{}"))
                            .andReturn()
                            .getResponse()
                            .getStatus());
                } catch (Exception exception) {
                    cancelError.set(exception.getClass().getSimpleName());
                }
                return null;
            });
            Future<?> expireFuture = executor.submit(() -> {
                ready.countDown();
                start.await();
                bookingExpiryService.expireDueBookings(Instant.now());
                return null;
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            cancelFuture.get(20, TimeUnit.SECONDS);
            expireFuture.get(20, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(cancelError.get()).isNull();
        BookingStatus finalStatus = bookingRepository.findById(bookingId).orElseThrow().getStatus();
        assertThat(finalStatus).isIn(BookingStatus.CANCELLED, BookingStatus.EXPIRED);
        var allocations = allocationRepository.findByHoldIdOrderByCreatedAtAsc(
                bookingRepository.findById(bookingId).orElseThrow().getHoldId());
        if (finalStatus == BookingStatus.EXPIRED) {
            assertThat(allocations).allMatch(a -> a.getState() == TripSeatAllocationState.RELEASED);
            assertThat(cancelStatus.get()).isEqualTo(409);
            assertThat(cancellationRepository.count()).isZero();
        } else {
            assertThat(allocations).allMatch(a -> a.getState() == TripSeatAllocationState.CANCELLED);
            assertThat(cancelStatus.get()).isEqualTo(200);
            assertThat(cancellationRepository.count()).isEqualTo(1);
        }
        assertThat(seatAvailabilityService.getSeatAvailability(trip.tripId(), 1, 3).stream()
                .filter(result -> result.inventoryId().equals(trip.availableSeatIds().get(0)))
                .findFirst()
                .orElseThrow()
                .journeyAvailability()).isEqualTo(JourneySeatAvailability.AVAILABLE);
    }

    @Test
    @WithMockUser
    void cancellationVersusPaymentConfirmationRaceProducesOneValidFinalState() throws Exception {
        TripFixture trip = createTrip("BOOK-CANCEL-RACE-02", "BOOK-CANCEL-RACE-RT-02");
        UUID seat = trip.availableSeatIds().get(0);
        UUID bookingId = bookSeat(trip, seat, customerAToken, "idem-cancel-confirm-race");

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger cancelStatus = new AtomicInteger();
        AtomicReference<BookingStatus> confirmStatus = new AtomicReference<>();
        AtomicReference<String> confirmError = new AtomicReference<>();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> cancelFuture = executor.submit(() -> {
                ready.countDown();
                start.await();
                cancelStatus.set(mockMvc.perform(post("/api/v1/bookings/{id}/cancel", bookingId)
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerAToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                        .andReturn()
                        .getResponse()
                        .getStatus());
                return null;
            });
            Future<?> confirmFuture = executor.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    confirmStatus.set(bookingLifecycleService.confirmPendingPayment(bookingId));
                } catch (ApplicationConflictException exception) {
                    confirmError.set(exception.getClass().getSimpleName());
                }
                return null;
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            cancelFuture.get(20, TimeUnit.SECONDS);
            confirmFuture.get(20, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        BookingStatus finalStatus = bookingRepository.findById(bookingId).orElseThrow().getStatus();
        assertThat(finalStatus).isIn(BookingStatus.CANCELLED, BookingStatus.CONFIRMED);
        var allocations = allocationRepository.findByHoldIdOrderByCreatedAtAsc(
                bookingRepository.findById(bookingId).orElseThrow().getHoldId());
        if (finalStatus == BookingStatus.CONFIRMED) {
            assertThat(allocations).allMatch(a -> a.getState() == TripSeatAllocationState.BOOKED);
            assertThat(confirmStatus.get()).isEqualTo(BookingStatus.CONFIRMED);
            assertThat(cancelStatus.get()).isEqualTo(409);
            assertThat(cancellationRepository.count()).isZero();
            assertThat(seatAvailabilityService.getSeatAvailability(trip.tripId(), 1, 3).stream()
                    .filter(result -> result.inventoryId().equals(seat))
                    .findFirst()
                    .orElseThrow()
                    .journeyAvailability()).isEqualTo(JourneySeatAvailability.UNAVAILABLE);
        } else {
            assertThat(allocations).allMatch(a -> a.getState() == TripSeatAllocationState.CANCELLED);
            assertThat(cancelStatus.get()).isEqualTo(200);
            assertThat(confirmError.get()).isEqualTo(ApplicationConflictException.class.getSimpleName());
            assertThat(cancellationRepository.count()).isEqualTo(1);
            assertThat(seatAvailabilityService.getSeatAvailability(trip.tripId(), 1, 3).stream()
                    .filter(result -> result.inventoryId().equals(seat))
                    .findFirst()
                    .orElseThrow()
                    .journeyAvailability()).isEqualTo(JourneySeatAvailability.AVAILABLE);
        }
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
        mockMvc.perform(get("/api/v1/bookings/{id}", UUID.randomUUID()).with(anonymous()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/bookings/{id}/cancel", UUID.randomUUID())
                        .with(anonymous())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    private UUID bookSeat(TripFixture trip, UUID seat, String bearerToken, String idempotencyKey) throws Exception {
        JsonNode hold = createHold(trip, List.of(seat), bearerToken);
        JsonNode booking = objectMapper.readTree(mockMvc.perform(post("/api/v1/bookings")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingBody(
                                UUID.fromString(hold.get("holdId").asText()),
                                trip.stopId(1),
                                trip.stopId(3),
                                idempotencyKey,
                                List.of(passenger(seat, "Passenger", 30)))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString());
        return UUID.fromString(booking.get("bookingId").asText());
    }

    private void forcePaymentExpiresAt(UUID bookingId, Instant expiresAt) {
        jdbcTemplate.update(
                "UPDATE bookings SET payment_expires_at = ? WHERE id = ?",
                java.sql.Timestamp.from(expiresAt),
                bookingId);
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
        Instant opens = Instant.parse("2020-01-01T00:00:00Z");
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
        mockMvc.perform(post("/api/v1/admin/trips/{id}/activate", tripId).with(adminAuth()))
                .andExpect(status().isOk());
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
                        .with(adminAuth())
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
                        .with(adminAuth())
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
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
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
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private UUID createLocation(String state, String city) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/admin/locations")
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
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }


    private org.springframework.test.web.servlet.request.RequestPostProcessor adminAuth() {
        return TestAccessTokenFactory.bearer(adminToken);
    }

    private record Fixture(UUID busId, UUID routeId) {
    }

    private record TripFixture(UUID tripId, List<UUID> stopIdsBySequence, List<UUID> availableSeatIds) {
        UUID stopId(int sequence) {
            return stopIdsBySequence.get(sequence);
        }
    }
}
