package in.bluebustickets.bluebus.booking.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import in.bluebustickets.bluebus.booking.domain.Booking;
import in.bluebustickets.bluebus.booking.domain.BookingItemStatus;
import in.bluebustickets.bluebus.booking.domain.BookingStatus;
import in.bluebustickets.bluebus.booking.repository.BookingRepository;
import in.bluebustickets.bluebus.foundation.api.error.ApplicationConflictException;
import in.bluebustickets.bluebus.identity.domain.Role;
import in.bluebustickets.bluebus.identity.domain.RoleCode;
import in.bluebustickets.bluebus.identity.domain.User;
import in.bluebustickets.bluebus.identity.domain.UserRole;
import in.bluebustickets.bluebus.identity.repository.RoleRepository;
import in.bluebustickets.bluebus.identity.repository.UserRepository;
import in.bluebustickets.bluebus.identity.repository.UserRoleRepository;
import in.bluebustickets.bluebus.scheduling.application.JourneySeatAvailability;
import in.bluebustickets.bluebus.scheduling.application.SeatAvailabilityService;
import in.bluebustickets.bluebus.scheduling.domain.TripSeatAllocation;
import in.bluebustickets.bluebus.scheduling.domain.TripSeatAllocationState;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser
class BookingExpiryPostgresIntegrationTest {

    private static final String PASSWORD = "CorrectHorseBatteryStaple!";
    private static final String CUSTOMER_EMAIL = "expiry-a@example.test";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("blue-bus.seat-holds.expiry.enabled", () -> "false");
        registry.add("blue-bus.bookings.expiry.enabled", () -> "false");
        registry.add("blue-bus.bookings.expiry.batch-size", () -> "2");
        registry.add("blue-bus.bookings.expiry.reaper-interval-ms", () -> "30000");
        registry.add("blue-bus.bookings.unpaid.ttl-seconds", () -> "900");
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRoleRepository userRoleRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private BookingRepository bookingRepository;
    @Autowired private TripSeatAllocationRepository allocationRepository;
    @Autowired private BookingExpiryService bookingExpiryService;
    @Autowired private BookingExpiryProperties expiryProperties;
    @Autowired private BookingUnpaidProperties unpaidProperties;
    @Autowired private BookingLifecycleService bookingLifecycleService;
    @Autowired private SeatAvailabilityService seatAvailabilityService;

    private String customerToken;

    @BeforeEach
    void seedCustomer() throws Exception {
        bookingRepository.deleteAll();
        userRoleRepository.deleteAll();
        userRepository.deleteAll();

        Role customerRole = roleRepository.findByCode(RoleCode.CUSTOMER).orElseThrow();
        User user = new User(CUSTOMER_EMAIL, "+919944400001", "Expiry", "Customer");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user = userRepository.saveAndFlush(user);
        userRoleRepository.saveAndFlush(new UserRole(user, customerRole));
        customerToken = loginToken(CUSTOMER_EMAIL);
    }

    @Test
    void schedulerConfigurationIsRespected() {
        assertThat(expiryProperties.isEnabled()).isFalse();
        assertThat(expiryProperties.getBatchSize()).isEqualTo(2);
        assertThat(expiryProperties.getReaperIntervalMs()).isEqualTo(30_000L);
        assertThat(unpaidProperties.getTtlSeconds()).isEqualTo(900L);
    }

    @Test
    void pendingPaymentWithFutureDeadlineRemainsActive() throws Exception {
        CreatedBooking created = createPaidPendingBooking("BEXP-01", "BEXP-RT-01", 0);

        BookingExpiryResult result = bookingExpiryService.expireDueBookings(Instant.now());
        assertThat(result.bookingsExpired()).isZero();
        assertThat(bookingRepository.findById(created.bookingId()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.PENDING_PAYMENT);
        assertThat(allocationsFor(created)).allMatch(a -> a.getState() == TripSeatAllocationState.BOOKED);
        assertThat(findAvailability(created.tripId(), created.seatIds().get(0)))
                .isEqualTo(JourneySeatAvailability.UNAVAILABLE);
    }

    @Test
    void expiredPendingPaymentBecomesExpiredReleasesAllocationsAndUnblocksAvailability() throws Exception {
        CreatedBooking created = createPaidPendingBooking("BEXP-02", "BEXP-RT-02", 0, 2);
        Instant dueAt = Instant.parse("2026-06-01T12:00:00Z");
        forcePaymentExpiresAt(created.bookingId(), dueAt);

        assertThat(findAvailability(created.tripId(), created.seatIds().get(0)))
                .isEqualTo(JourneySeatAvailability.UNAVAILABLE);

        BookingExpiryResult result = bookingExpiryService.expireDueBookings(dueAt);
        assertThat(result.bookingsExpired()).isGreaterThanOrEqualTo(1);
        assertThat(result.allocationsReleased()).isGreaterThanOrEqualTo(2);

        Booking expired = bookingRepository.findDetailedById(created.bookingId()).orElseThrow();
        assertThat(expired.getStatus()).isEqualTo(BookingStatus.EXPIRED);
        assertThat(expired.getItems()).hasSize(2);
        assertThat(expired.getItems()).allMatch(item -> item.getStatus() == BookingItemStatus.EXPIRED);
        assertThat(expired.getPassengers()).hasSize(2);
        assertThat(allocationsFor(created))
                .hasSize(2)
                .allMatch(a -> a.getState() == TripSeatAllocationState.RELEASED);

        assertThat(findAvailability(created.tripId(), created.seatIds().get(0)))
                .isEqualTo(JourneySeatAvailability.AVAILABLE);
        assertThat(findAvailability(created.tripId(), created.seatIds().get(1)))
                .isEqualTo(JourneySeatAvailability.AVAILABLE);

        mockMvc.perform(get("/api/v1/bookings/{id}", created.bookingId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXPIRED"))
                .andExpect(jsonPath("$.paymentExpiresAt").exists())
                .andExpect(jsonPath("$.passengers.length()").value(2))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].status").value("EXPIRED"));
    }

    @Test
    void confirmedBookingIsNeverExpired() throws Exception {
        CreatedBooking created = createPaidPendingBooking("BEXP-03", "BEXP-RT-03", 0);
        bookingLifecycleService.confirmPendingPayment(created.bookingId());
        forcePaymentExpiresAt(created.bookingId(), Instant.now().minusSeconds(60));

        BookingExpiryResult result = bookingExpiryService.expireDueBookings(Instant.now());
        assertThat(result.bookingsExpired()).isZero();
        assertThat(bookingRepository.findById(created.bookingId()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.CONFIRMED);
        assertThat(allocationsFor(created)).allMatch(a -> a.getState() == TripSeatAllocationState.BOOKED);
        assertThat(findAvailability(created.tripId(), created.seatIds().get(0)))
                .isEqualTo(JourneySeatAvailability.UNAVAILABLE);
    }

    @Test
    void cancelledBookingIsNeverExpired() throws Exception {
        CreatedBooking created = createPaidPendingBooking("BEXP-04", "BEXP-RT-04", 0);
        bookingLifecycleService.cancelUnpaidBooking(created.bookingId());
        forcePaymentExpiresAt(created.bookingId(), Instant.now().minusSeconds(60));

        BookingExpiryResult result = bookingExpiryService.expireDueBookings(Instant.now());
        assertThat(result.bookingsExpired()).isZero();
        assertThat(bookingRepository.findById(created.bookingId()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.CANCELLED);
        assertThat(allocationsFor(created)).allMatch(a -> a.getState() == TripSeatAllocationState.CANCELLED);
        assertThat(findAvailability(created.tripId(), created.seatIds().get(0)))
                .isEqualTo(JourneySeatAvailability.AVAILABLE);
    }

    @Test
    void onlyTargetedBookingAllocationsAreReleased() throws Exception {
        TripFixture trip = createTrip("BEXP-05", "BEXP-RT-05");
        CreatedBooking first = bookSeats(trip, List.of(trip.availableSeatIds().get(0)), "idem-target-1", "One");
        CreatedBooking second = bookSeats(trip, List.of(trip.availableSeatIds().get(1)), "idem-target-2", "Two");
        forcePaymentExpiresAt(first.bookingId(), Instant.now().minusSeconds(30));

        BookingExpiryResult result = bookingExpiryService.expireDueBookings(Instant.now());
        assertThat(result.bookingsExpired()).isEqualTo(1);

        assertThat(bookingRepository.findById(first.bookingId()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.EXPIRED);
        assertThat(bookingRepository.findById(second.bookingId()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.PENDING_PAYMENT);
        assertThat(allocationsFor(first)).allMatch(a -> a.getState() == TripSeatAllocationState.RELEASED);
        assertThat(allocationsFor(second)).allMatch(a -> a.getState() == TripSeatAllocationState.BOOKED);
        assertThat(findAvailability(trip.tripId(), first.seatIds().get(0)))
                .isEqualTo(JourneySeatAvailability.AVAILABLE);
        assertThat(findAvailability(trip.tripId(), second.seatIds().get(0)))
                .isEqualTo(JourneySeatAvailability.UNAVAILABLE);
    }

    @Test
    void reaperProcessesBoundedBatchesUntilDueSetIsEmpty() throws Exception {
        TripFixture trip = createTrip("BEXP-06", "BEXP-RT-06");
        CreatedBooking b1 = bookSeats(trip, List.of(trip.availableSeatIds().get(0)), "idem-batch-1", "B1");
        CreatedBooking b2 = bookSeats(trip, List.of(trip.availableSeatIds().get(1)), "idem-batch-2", "B2");
        CreatedBooking b3 = bookSeats(trip, List.of(trip.availableSeatIds().get(2)), "idem-batch-3", "B3");
        Instant past = Instant.now().minusSeconds(45);
        forcePaymentExpiresAt(b1.bookingId(), past);
        forcePaymentExpiresAt(b2.bookingId(), past);
        forcePaymentExpiresAt(b3.bookingId(), past);

        assertThat(bookingExpiryService.countDueUnpaidBookings(Instant.now())).isEqualTo(2);

        BookingExpiryResult result = bookingExpiryService.expireDueBookings(Instant.now());
        assertThat(result.bookingsExpired()).isEqualTo(3);
        assertThat(bookingRepository.findById(b1.bookingId()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.EXPIRED);
        assertThat(bookingRepository.findById(b2.bookingId()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.EXPIRED);
        assertThat(bookingRepository.findById(b3.bookingId()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.EXPIRED);
    }

    @Test
    void concurrentExpiryWorkersDoNotDoubleRelease() throws Exception {
        CreatedBooking created = createPaidPendingBooking("BEXP-07", "BEXP-RT-07", 0);
        forcePaymentExpiresAt(created.bookingId(), Instant.now().minusSeconds(40));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<BookingExpiryResult>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return bookingExpiryService.expireDueBookings(Instant.now());
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            int expired = 0;
            int released = 0;
            for (Future<BookingExpiryResult> future : futures) {
                BookingExpiryResult result = future.get(20, TimeUnit.SECONDS);
                expired += result.bookingsExpired();
                released += result.allocationsReleased();
            }
            assertThat(expired).isEqualTo(1);
            assertThat(released).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }

        assertThat(bookingRepository.findById(created.bookingId()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.EXPIRED);
        assertThat(allocationsFor(created))
                .hasSize(1)
                .allMatch(a -> a.getState() == TripSeatAllocationState.RELEASED);
    }

    @Test
    void confirmationVersusExpiryRaceProducesOneValidFinalState() throws Exception {
        CreatedBooking created = createPaidPendingBooking("BEXP-08", "BEXP-RT-08", 0);
        forcePaymentExpiresAt(created.bookingId(), Instant.now().minusSeconds(20));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<BookingStatus> confirmStatus = new AtomicReference<>();
        AtomicReference<String> confirmError = new AtomicReference<>();
        AtomicReference<BookingExpiryResult> expiryResult = new AtomicReference<>();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> confirmFuture = executor.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    confirmStatus.set(bookingLifecycleService.confirmPendingPayment(created.bookingId()));
                } catch (RuntimeException exception) {
                    confirmError.set(exception.getClass().getSimpleName());
                }
                return null;
            });
            Future<?> expireFuture = executor.submit(() -> {
                ready.countDown();
                start.await();
                expiryResult.set(bookingExpiryService.expireDueBookings(Instant.now()));
                return null;
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            confirmFuture.get(20, TimeUnit.SECONDS);
            expireFuture.get(20, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        BookingStatus finalStatus = bookingRepository.findById(created.bookingId()).orElseThrow().getStatus();
        assertThat(finalStatus).isIn(BookingStatus.CONFIRMED, BookingStatus.EXPIRED);
        List<TripSeatAllocation> allocations = allocationsFor(created);
        if (finalStatus == BookingStatus.EXPIRED) {
            assertThat(allocations).allMatch(a -> a.getState() == TripSeatAllocationState.RELEASED);
            assertThat(confirmError.get()).isEqualTo(ApplicationConflictException.class.getSimpleName());
            assertThat(expiryResult.get().bookingsExpired()).isEqualTo(1);
        } else {
            assertThat(allocations).allMatch(a -> a.getState() == TripSeatAllocationState.BOOKED);
            assertThat(confirmStatus.get()).isEqualTo(BookingStatus.CONFIRMED);
            assertThat(expiryResult.get().bookingsExpired()).isZero();
            assertThat(bookingLifecycleService.confirmPendingPayment(created.bookingId()))
                    .isEqualTo(BookingStatus.CONFIRMED);
        }
    }

    @Test
    void cancellationVersusExpiryRaceProducesOneValidFinalState() throws Exception {
        CreatedBooking created = createPaidPendingBooking("BEXP-09", "BEXP-RT-09", 0);
        forcePaymentExpiresAt(created.bookingId(), Instant.now().minusSeconds(20));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<BookingStatus> cancelStatus = new AtomicReference<>();
        AtomicReference<String> cancelError = new AtomicReference<>();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> cancelFuture = executor.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    cancelStatus.set(bookingLifecycleService.cancelUnpaidBooking(created.bookingId()));
                } catch (RuntimeException exception) {
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

        BookingStatus finalStatus = bookingRepository.findById(created.bookingId()).orElseThrow().getStatus();
        assertThat(finalStatus).isIn(BookingStatus.CANCELLED, BookingStatus.EXPIRED);
        List<TripSeatAllocation> allocations = allocationsFor(created);
        if (finalStatus == BookingStatus.EXPIRED) {
            assertThat(allocations).allMatch(a -> a.getState() == TripSeatAllocationState.RELEASED);
            assertThat(cancelError.get()).isEqualTo(ApplicationConflictException.class.getSimpleName());
        } else {
            assertThat(allocations).allMatch(a -> a.getState() == TripSeatAllocationState.CANCELLED);
            assertThat(cancelStatus.get()).isEqualTo(BookingStatus.CANCELLED);
        }
        long releasedOrCancelled = allocations.stream()
                .filter(a -> a.getState() == TripSeatAllocationState.RELEASED
                        || a.getState() == TripSeatAllocationState.CANCELLED)
                .count();
        assertThat(releasedOrCancelled).isEqualTo(1);
    }

    @Test
    void unexpectedNonBookedAllocationFailsSafelyWithoutPartialCommit() throws Exception {
        CreatedBooking created = createPaidPendingBooking("BEXP-10", "BEXP-RT-10", 0);
        UUID allocationId = allocationsFor(created).get(0).getId();
        forcePaymentExpiresAt(created.bookingId(), Instant.now().minusSeconds(45));
        jdbcTemplate.update("UPDATE trip_seat_allocations SET state = 'CANCELLED' WHERE id = ?", allocationId);

        BookingExpiryResult result = bookingExpiryService.expireDueBookings(Instant.now());
        assertThat(result.bookingsExpired()).isZero();
        assertThat(bookingRepository.findById(created.bookingId()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.PENDING_PAYMENT);
        assertThat(allocationRepository.findById(allocationId).orElseThrow().getState())
                .isEqualTo(TripSeatAllocationState.CANCELLED);

        jdbcTemplate.update("UPDATE bookings SET status = 'CANCELLED' WHERE id = ?", created.bookingId());
    }

    private CreatedBooking createPaidPendingBooking(String registration, String routeCode, int seatIndex)
            throws Exception {
        return createPaidPendingBooking(registration, routeCode, seatIndex, 1);
    }

    private CreatedBooking createPaidPendingBooking(
            String registration, String routeCode, int seatIndex, int seatCount) throws Exception {
        TripFixture trip = createTrip(registration, routeCode);
        List<UUID> seats = trip.availableSeatIds().subList(seatIndex, seatIndex + seatCount);
        return bookSeats(trip, seats, "idem-" + registration, "Passenger");
    }

    private CreatedBooking bookSeats(
            TripFixture trip, List<UUID> seats, String idempotencyKey, String namePrefix) throws Exception {
        JsonNode hold = createHold(trip, seats);
        UUID holdId = UUID.fromString(hold.get("holdId").asText());
        List<String> passengers = new ArrayList<>();
        for (int i = 0; i < seats.size(); i++) {
            passengers.add(passenger(seats.get(i), namePrefix + " " + (i + 1), 30 + i));
        }
        MvcResult created = mockMvc.perform(post("/api/v1/bookings")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingBody(
                                holdId,
                                trip.stopId(1),
                                trip.stopId(3),
                                idempotencyKey,
                                passengers)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(created.getResponse().getContentAsString());
        return new CreatedBooking(
                UUID.fromString(body.get("bookingId").asText()),
                trip.tripId(),
                holdId,
                List.copyOf(seats));
    }

    private List<TripSeatAllocation> allocationsFor(CreatedBooking created) {
        return allocationRepository.findByHoldIdOrderByCreatedAtAsc(created.holdId());
    }

    private JourneySeatAvailability findAvailability(UUID tripId, UUID inventoryId) {
        return seatAvailabilityService.getSeatAvailability(tripId, 1, 3).stream()
                .filter(result -> result.inventoryId().equals(inventoryId))
                .findFirst()
                .orElseThrow()
                .journeyAvailability();
    }

    private void forcePaymentExpiresAt(UUID bookingId, Instant expiresAt) {
        jdbcTemplate.update(
                "UPDATE bookings SET payment_expires_at = ? WHERE id = ?",
                java.sql.Timestamp.from(expiresAt),
                bookingId);
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

    private JsonNode createHold(TripFixture trip, List<UUID> seats) throws Exception {
        StringBuilder seatJson = new StringBuilder("[");
        for (int i = 0; i < seats.size(); i++) {
            if (i > 0) {
                seatJson.append(',');
            }
            seatJson.append('"').append(seats.get(i)).append('"');
        }
        seatJson.append(']');
        MvcResult result = mockMvc.perform(post("/api/v1/trips/{tripId}/holds", trip.tripId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken)
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
        UUID operatorId = createOperator("Exp Book Op " + registration, "Exp Book Co " + registration);
        UUID busTypeId = createBusType("EBTYPE_" + registration.replace(" ", ""), "Type " + registration);
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

    private record CreatedBooking(UUID bookingId, UUID tripId, UUID holdId, List<UUID> seatIds) {
    }
}
