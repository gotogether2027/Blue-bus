package in.bluebustickets.bluebus.scheduling.application;

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

import in.bluebustickets.bluebus.foundation.security.TestAccessTokenFactory;
import in.bluebustickets.bluebus.scheduling.domain.SeatHoldStatus;
import in.bluebustickets.bluebus.scheduling.domain.TripSeatAllocation;
import in.bluebustickets.bluebus.scheduling.domain.TripSeatAllocationState;
import in.bluebustickets.bluebus.scheduling.repository.SeatHoldRepository;
import in.bluebustickets.bluebus.scheduling.repository.TripSeatAllocationRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser
class SeatHoldExpiryPostgresIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("blue-bus.seat-holds.expiry.enabled", () -> "false");
        registry.add("blue-bus.seat-holds.expiry.batch-size", () -> "2");
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private SeatHoldService seatHoldService;
    @Autowired private SeatHoldExpiryService seatHoldExpiryService;
    @Autowired private SeatAvailabilityService seatAvailabilityService;
    @Autowired private TripSeatAllocationService allocationService;
    @Autowired private SeatHoldRepository seatHoldRepository;
    @Autowired private TripSeatAllocationRepository allocationRepository;
    @Autowired private TestAccessTokenFactory testAccessTokenFactory;
    private String adminToken;

    @BeforeEach
    void seedPlatformAdmin() {
        adminToken = testAccessTokenFactory.issuePlatformAdmin().accessToken();
    }

    @Test
    void unexpiredActiveHoldRemainsActive() throws Exception {
        TripData trip = createTrip("EXP-REG-01", "EXP-RT-01");
        Instant future = Instant.now().plusSeconds(600);
        SeatHoldService.SeatHoldResult created = seatHoldService.createHold(
                trip.tripId(), 1, 3, future, List.of(trip.availableSeatIds().get(0)));

        SeatHoldExpiryResult result = seatHoldExpiryService.expireDueHolds(Instant.now());
        assertThat(result.holdsExpired()).isZero();
        assertThat(seatHoldRepository.findById(created.hold().getId()).orElseThrow().getStatus())
                .isEqualTo(SeatHoldStatus.ACTIVE);
    }

    @Test
    void dueActiveHoldAndHeldAllocationsExpireAndUnblockAvailability() throws Exception {
        TripData trip = createTrip("EXP-REG-02", "EXP-RT-02");
        List<UUID> seats = trip.availableSeatIds().subList(0, 2);
        Instant past = Instant.now().minusSeconds(30);
        SeatHoldService.SeatHoldResult created = createHoldAlreadyExpired(trip, seats, past);

        assertThat(findAvailability(trip.tripId(), seats.get(0)))
                .isEqualTo(JourneySeatAvailability.UNAVAILABLE);

        Instant now = Instant.now();
        SeatHoldExpiryResult result = seatHoldExpiryService.expireDueHolds(now);
        assertThat(result.holdsExpired()).isGreaterThanOrEqualTo(1);
        assertThat(result.allocationsExpired()).isGreaterThanOrEqualTo(2);

        assertThat(seatHoldRepository.findById(created.hold().getId()).orElseThrow().getStatus())
                .isEqualTo(SeatHoldStatus.EXPIRED);
        List<TripSeatAllocation> allocations =
                allocationRepository.findByHoldIdOrderByCreatedAtAsc(created.hold().getId());
        assertThat(allocations).hasSize(2);
        assertThat(allocations).allMatch(a -> a.getState() == TripSeatAllocationState.EXPIRED);

        assertThat(findAvailability(trip.tripId(), seats.get(0)))
                .isEqualTo(JourneySeatAvailability.AVAILABLE);
        assertThat(findAvailability(trip.tripId(), seats.get(1)))
                .isEqualTo(JourneySeatAvailability.AVAILABLE);
    }

    @Test
    void expiresAtEqualToNowIsDue() throws Exception {
        TripData trip = createTrip("EXP-REG-14", "EXP-RT-14");
        Instant dueAt = Instant.parse("2026-06-01T12:00:00Z");
        SeatHoldService.SeatHoldResult created = createHoldAlreadyExpired(
                trip, List.of(trip.availableSeatIds().get(0)), dueAt);

        SeatHoldExpiryResult result = seatHoldExpiryService.expireDueHolds(dueAt);
        assertThat(result.holdsExpired()).isEqualTo(1);
        assertThat(seatHoldRepository.findById(created.hold().getId()).orElseThrow().getStatus())
                .isEqualTo(SeatHoldStatus.EXPIRED);
    }

    @Test
    void alreadyTerminalHoldsAndNonHeldAllocationsAreLeftAlone() throws Exception {
        TripData trip = createTrip("EXP-REG-06", "EXP-RT-06");
        UUID s0 = trip.availableSeatIds().get(0);
        UUID s1 = trip.availableSeatIds().get(1);
        UUID s2 = trip.availableSeatIds().get(2);

        SeatHoldService.SeatHoldResult alreadyExpired = createHoldAlreadyExpired(
                trip, List.of(s0), Instant.now().minusSeconds(60));
        seatHoldService.expire(alreadyExpired.hold().getId());

        SeatHoldService.SeatHoldResult cancelled = seatHoldService.createHold(
                trip.tripId(), 1, 3, Instant.now().plusSeconds(300), List.of(s1));
        seatHoldService.cancel(cancelled.hold().getId());
        forceExpiresAt(cancelled.hold().getId(), Instant.now().minusSeconds(10));

        SeatHoldService.SeatHoldResult consumed = seatHoldService.createHold(
                trip.tripId(), 1, 3, Instant.now().plusSeconds(300), List.of(s2));
        seatHoldService.consume(consumed.hold().getId());
        forceExpiresAt(consumed.hold().getId(), Instant.now().minusSeconds(10));

        TripData tripB = createTrip("EXP-REG-06B", "EXP-RT-06B");
        UUID booked = tripB.availableSeatIds().get(0);
        UUID blockedAlloc = tripB.availableSeatIds().get(1);
        UUID cancelledAlloc = tripB.availableSeatIds().get(2);
        allocationService.allocate(tripB.tripId(), booked, 1, 3, TripSeatAllocationState.BOOKED, null);
        allocationService.allocate(tripB.tripId(), blockedAlloc, 1, 3, TripSeatAllocationState.BLOCKED, null);
        allocationService.allocate(tripB.tripId(), cancelledAlloc, 1, 3, TripSeatAllocationState.CANCELLED, null);
        allocationService.allocate(tripB.tripId(), booked, 3, 4, TripSeatAllocationState.RELEASED, null);

        long bookedBefore = countState(TripSeatAllocationState.BOOKED);
        long blockedBefore = countState(TripSeatAllocationState.BLOCKED);
        long cancelledBefore = countState(TripSeatAllocationState.CANCELLED);
        long releasedBefore = countState(TripSeatAllocationState.RELEASED);

        SeatHoldExpiryResult result = seatHoldExpiryService.expireDueHolds(Instant.now());
        assertThat(result.holdsExpired()).isZero();
        assertThat(seatHoldRepository.findById(alreadyExpired.hold().getId()).orElseThrow().getStatus())
                .isEqualTo(SeatHoldStatus.EXPIRED);
        assertThat(seatHoldRepository.findById(cancelled.hold().getId()).orElseThrow().getStatus())
                .isEqualTo(SeatHoldStatus.CANCELLED);
        assertThat(seatHoldRepository.findById(consumed.hold().getId()).orElseThrow().getStatus())
                .isEqualTo(SeatHoldStatus.CONSUMED);

        assertThat(countState(TripSeatAllocationState.BOOKED)).isEqualTo(bookedBefore);
        assertThat(countState(TripSeatAllocationState.BLOCKED)).isEqualTo(blockedBefore);
        assertThat(countState(TripSeatAllocationState.CANCELLED)).isEqualTo(cancelledBefore);
        assertThat(countState(TripSeatAllocationState.RELEASED)).isEqualTo(releasedBefore);
    }

    private long countState(TripSeatAllocationState state) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM trip_seat_allocations WHERE state = ?",
                Long.class,
                state.name());
        return count == null ? 0L : count;
    }

    @Test
    void reaperIsIdempotentAndProcessesMultipleDueHoldsInBatches() throws Exception {
        TripData trip = createTrip("EXP-REG-15", "EXP-RT-15");
        Instant past = Instant.now().minusSeconds(120);
        SeatHoldService.SeatHoldResult h1 = createHoldAlreadyExpired(
                trip, List.of(trip.availableSeatIds().get(0)), past);
        SeatHoldService.SeatHoldResult h2 = createHoldAlreadyExpired(
                trip, List.of(trip.availableSeatIds().get(1)), past);
        SeatHoldService.SeatHoldResult h3 = createHoldAlreadyExpired(
                trip, List.of(trip.availableSeatIds().get(2)), past);

        SeatHoldExpiryResult first = seatHoldExpiryService.expireDueHolds(Instant.now());
        assertThat(first.holdsExpired()).isEqualTo(3);

        SeatHoldExpiryResult second = seatHoldExpiryService.expireDueHolds(Instant.now());
        assertThat(second.holdsExpired()).isZero();
        assertThat(second.allocationsExpired()).isZero();

        assertThat(seatHoldRepository.findById(h1.hold().getId()).orElseThrow().getStatus())
                .isEqualTo(SeatHoldStatus.EXPIRED);
        assertThat(seatHoldRepository.findById(h2.hold().getId()).orElseThrow().getStatus())
                .isEqualTo(SeatHoldStatus.EXPIRED);
        assertThat(seatHoldRepository.findById(h3.hold().getId()).orElseThrow().getStatus())
                .isEqualTo(SeatHoldStatus.EXPIRED);
    }

    @Test
    void unexpectedBookedAllocationUnderActiveHoldFailsSafelyWithoutPartialCommit() throws Exception {
        TripData trip = createTrip("EXP-REG-16", "EXP-RT-16");
        Instant past = Instant.now().minusSeconds(45);
        SeatHoldService.SeatHoldResult created = createHoldAlreadyExpired(
                trip, List.of(trip.availableSeatIds().get(0)), past);
        UUID holdId = created.hold().getId();
        UUID allocationId = created.allocations().get(0).getId();

        jdbcTemplate.update(
                "UPDATE trip_seat_allocations SET state = 'BOOKED' WHERE id = ?",
                allocationId);

        SeatHoldExpiryResult result = seatHoldExpiryService.expireDueHolds(Instant.now());
        assertThat(result.holdsExpired()).isZero();
        assertThat(seatHoldRepository.findById(holdId).orElseThrow().getStatus())
                .isEqualTo(SeatHoldStatus.ACTIVE);
        assertThat(allocationRepository.findById(allocationId).orElseThrow().getState())
                .isEqualTo(TripSeatAllocationState.BOOKED);

        // Remove from due-ACTIVE discovery so later tests are not polluted.
        jdbcTemplate.update(
                "UPDATE seat_holds SET status = 'CANCELLED' WHERE id = ?",
                holdId);
    }

    @Test
    void consumeVersusExpiryRaceProducesValidFinalState() throws Exception {
        TripData trip = createTrip("EXP-REG-17", "EXP-RT-17");
        Instant past = Instant.now().minusSeconds(20);
        SeatHoldService.SeatHoldResult created = createHoldAlreadyExpired(
                trip, List.of(trip.availableSeatIds().get(0)), past);
        UUID holdId = created.hold().getId();

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<SeatHoldStatus> consumeStatus = new AtomicReference<>();
        AtomicReference<String> consumeError = new AtomicReference<>();
        AtomicReference<SeatHoldExpiryResult> expiryResult = new AtomicReference<>();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> consumeFuture = executor.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    consumeStatus.set(seatHoldService.consume(holdId).hold().getStatus());
                } catch (RuntimeException exception) {
                    consumeError.set(exception.getClass().getSimpleName());
                }
                return null;
            });
            Future<?> expireFuture = executor.submit(() -> {
                ready.countDown();
                start.await();
                expiryResult.set(seatHoldExpiryService.expireDueHolds(Instant.now()));
                return null;
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            consumeFuture.get(20, TimeUnit.SECONDS);
            expireFuture.get(20, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        SeatHoldStatus finalStatus = seatHoldRepository.findById(holdId).orElseThrow().getStatus();
        assertThat(finalStatus).isIn(SeatHoldStatus.CONSUMED, SeatHoldStatus.EXPIRED);
        List<TripSeatAllocation> allocations = allocationRepository.findByHoldIdOrderByCreatedAtAsc(holdId);
        if (finalStatus == SeatHoldStatus.EXPIRED) {
            assertThat(allocations).allMatch(a -> a.getState() == TripSeatAllocationState.EXPIRED);
            assertThat(consumeError.get()).isNotNull();
        } else {
            assertThat(allocations).allMatch(a -> a.getState() == TripSeatAllocationState.HELD);
            assertThat(consumeStatus.get()).isEqualTo(SeatHoldStatus.CONSUMED);
            assertThat(expiryResult.get().holdsExpired()).isZero();
        }
    }

    @Test
    void cancelVersusExpiryRaceProducesValidFinalState() throws Exception {
        TripData trip = createTrip("EXP-REG-18", "EXP-RT-18");
        Instant past = Instant.now().minusSeconds(20);
        SeatHoldService.SeatHoldResult created = createHoldAlreadyExpired(
                trip, List.of(trip.availableSeatIds().get(0)), past);
        UUID holdId = created.hold().getId();

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<SeatHoldStatus> cancelStatus = new AtomicReference<>();
        AtomicReference<String> cancelError = new AtomicReference<>();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> cancelFuture = executor.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    cancelStatus.set(seatHoldService.cancel(holdId).hold().getStatus());
                } catch (RuntimeException exception) {
                    cancelError.set(exception.getClass().getSimpleName());
                }
                return null;
            });
            Future<?> expireFuture = executor.submit(() -> {
                ready.countDown();
                start.await();
                seatHoldExpiryService.expireDueHolds(Instant.now());
                return null;
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            cancelFuture.get(20, TimeUnit.SECONDS);
            expireFuture.get(20, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        SeatHoldStatus finalStatus = seatHoldRepository.findById(holdId).orElseThrow().getStatus();
        assertThat(finalStatus).isIn(SeatHoldStatus.CANCELLED, SeatHoldStatus.EXPIRED);
        List<TripSeatAllocation> allocations = allocationRepository.findByHoldIdOrderByCreatedAtAsc(holdId);
        if (finalStatus == SeatHoldStatus.EXPIRED) {
            assertThat(allocations).allMatch(a -> a.getState() == TripSeatAllocationState.EXPIRED);
            assertThat(cancelError.get()).isNotNull();
        } else {
            assertThat(allocations).allMatch(a -> a.getState() == TripSeatAllocationState.CANCELLED);
            assertThat(cancelStatus.get()).isEqualTo(SeatHoldStatus.CANCELLED);
        }
    }

    @Test
    void concurrentExpiryExecutionsDoNotCorruptHold() throws Exception {
        TripData trip = createTrip("EXP-REG-19", "EXP-RT-19");
        Instant past = Instant.now().minusSeconds(40);
        SeatHoldService.SeatHoldResult created = createHoldAlreadyExpired(
                trip, List.of(trip.availableSeatIds().get(0)), past);
        UUID holdId = created.hold().getId();

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<SeatHoldExpiryResult>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return seatHoldExpiryService.expireDueHolds(Instant.now());
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            int expiredHolds = 0;
            for (Future<SeatHoldExpiryResult> future : futures) {
                expiredHolds += future.get(20, TimeUnit.SECONDS).holdsExpired();
            }
            assertThat(expiredHolds).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }

        assertThat(seatHoldRepository.findById(holdId).orElseThrow().getStatus())
                .isEqualTo(SeatHoldStatus.EXPIRED);
        assertThat(allocationRepository.findByHoldIdOrderByCreatedAtAsc(holdId))
                .allMatch(a -> a.getState() == TripSeatAllocationState.EXPIRED);
    }

    private JourneySeatAvailability findAvailability(UUID tripId, UUID inventoryId) {
        return seatAvailabilityService.getSeatAvailability(tripId, 1, 3).stream()
                .filter(result -> result.inventoryId().equals(inventoryId))
                .findFirst()
                .orElseThrow()
                .journeyAvailability();
    }

    private SeatHoldService.SeatHoldResult createHoldAlreadyExpired(
            TripData trip,
            List<UUID> seats,
            Instant expiresAt) {
        Instant future = Instant.now().plusSeconds(600);
        SeatHoldService.SeatHoldResult created = seatHoldService.createHold(
                trip.tripId(), 1, 3, future, seats);
        forceExpiresAt(created.hold().getId(), expiresAt);
        for (TripSeatAllocation allocation : created.allocations()) {
            jdbcTemplate.update(
                    "UPDATE trip_seat_allocations SET expires_at = ? WHERE id = ?",
                    java.sql.Timestamp.from(expiresAt),
                    allocation.getId());
        }
        return seatHoldService.getHold(created.hold().getId());
    }

    private void forceExpiresAt(UUID holdId, Instant expiresAt) {
        jdbcTemplate.update(
                "UPDATE seat_holds SET expires_at = ? WHERE id = ?",
                java.sql.Timestamp.from(expiresAt),
                holdId);
    }

    private TripData createTrip(String registration, String routeCode) throws Exception {
        Fixture fixture = createFixture(registration, routeCode, 4);
        Instant departure = Instant.parse("2026-11-10T12:30:00Z");
        Instant arrival = departure.plusSeconds(8 * 3600);
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
        return new TripData(tripId, available, blocked);
    }

    private Fixture createFixture(String registration, String routeCode, int seatCount) throws Exception {
        UUID operatorId = createOperator("Exp Op " + registration, "Exp Co " + registration);
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

    private record TripData(UUID tripId, List<UUID> availableSeatIds, UUID blockedSeatId) { }
}
