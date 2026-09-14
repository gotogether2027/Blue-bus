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
import java.util.concurrent.atomic.AtomicInteger;

import in.bluebustickets.bluebus.foundation.api.error.ApplicationConflictException;
import in.bluebustickets.bluebus.scheduling.domain.SeatHoldStatus;
import in.bluebustickets.bluebus.scheduling.domain.TripSeatAllocation;
import in.bluebustickets.bluebus.scheduling.domain.TripSeatAllocationState;
import in.bluebustickets.bluebus.scheduling.repository.SeatHoldRepository;
import in.bluebustickets.bluebus.scheduling.repository.TripSeatAllocationRepository;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser
class SeatHoldPostgresIntegrationTest {

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
    @Autowired private SeatHoldService seatHoldService;
    @Autowired private SeatHoldRepository seatHoldRepository;
    @Autowired private TripSeatAllocationRepository allocationRepository;
    @Autowired private TripSeatAllocationService allocationService;

    @Test
    void createActiveHoldWithMultipleMatchingAllocations() throws Exception {
        TripData trip = createTrip("HOLD-REG-01", "HOLD-RT-01");
        Instant expiresAt = Instant.now().plusSeconds(600);
        List<UUID> seats = trip.availableSeatIds().subList(0, 3);

        SeatHoldService.SeatHoldResult result = seatHoldService.createHold(
                trip.tripId(), 1, 3, expiresAt, seats);

        assertThat(result.hold().getStatus()).isEqualTo(SeatHoldStatus.ACTIVE);
        assertThat(result.hold().getTripId()).isEqualTo(trip.tripId());
        assertThat(result.hold().getOriginSequence()).isEqualTo(1);
        assertThat(result.hold().getDestinationSequence()).isEqualTo(3);
        assertThat(result.hold().getExpiresAt()).isEqualTo(expiresAt);
        assertThat(result.allocations()).hasSize(3);
        assertThat(result.allocations()).allSatisfy(allocation -> {
            assertThat(allocation.getState()).isEqualTo(TripSeatAllocationState.HELD);
            assertThat(allocation.getHoldId()).isEqualTo(result.hold().getId());
            assertThat(allocation.getExpiresAt()).isEqualTo(expiresAt);
            assertThat(allocation.getOriginSequence()).isEqualTo(1);
            assertThat(allocation.getDestinationSequence()).isEqualTo(3);
            assertThat(allocation.getTripId()).isEqualTo(trip.tripId());
        });
        assertThat(allocationRepository.findByHoldIdOrderByCreatedAtAsc(result.hold().getId())).hasSize(3);
    }

    @Test
    void invalidOriginSequenceIsRejected() throws Exception {
        TripData trip = createTrip("HOLD-REG-08", "HOLD-RT-08");
        assertThatThrownBy(() -> seatHoldService.createHold(
                        trip.tripId(),
                        9,
                        10,
                        Instant.now().plusSeconds(120),
                        List.of(trip.availableSeatIds().get(0))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Origin sequence");
    }

    @Test
    void invalidDestinationSequenceIsRejected() throws Exception {
        TripData trip = createTrip("HOLD-REG-09", "HOLD-RT-09");
        assertThatThrownBy(() -> seatHoldService.createHold(
                        trip.tripId(),
                        1,
                        9,
                        Instant.now().plusSeconds(120),
                        List.of(trip.availableSeatIds().get(0))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Destination sequence");
    }

    @Test
    void originNotBeforeDestinationIsRejected() throws Exception {
        TripData trip = createTrip("HOLD-REG-10", "HOLD-RT-10");
        assertThatThrownBy(() -> seatHoldService.createHold(
                        trip.tripId(),
                        3,
                        3,
                        Instant.now().plusSeconds(120),
                        List.of(trip.availableSeatIds().get(0))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("destination must be greater than origin");
        assertThatThrownBy(() -> seatHoldService.createHold(
                        trip.tripId(),
                        4,
                        2,
                        Instant.now().plusSeconds(120),
                        List.of(trip.availableSeatIds().get(0))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("destination must be greater than origin");
    }

    @Test
    void pastExpirationIsRejected() throws Exception {
        TripData trip = createTrip("HOLD-REG-11", "HOLD-RT-11");
        assertThatThrownBy(() -> seatHoldService.createHold(
                        trip.tripId(),
                        1,
                        3,
                        Instant.now().minusSeconds(30),
                        List.of(trip.availableSeatIds().get(0))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expires_at must be in the future");
    }

    @Test
    void emptyInventoryListIsRejected() throws Exception {
        TripData trip = createTrip("HOLD-REG-12", "HOLD-RT-12");
        assertThatThrownBy(() -> seatHoldService.createHold(
                        trip.tripId(), 1, 3, Instant.now().plusSeconds(120), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one inventory");
    }

    @Test
    void inventoryFromAnotherTripIsRejected() throws Exception {
        TripData tripA = createTrip("HOLD-REG-13A", "HOLD-RT-13A");
        TripData tripB = createTrip("HOLD-REG-13B", "HOLD-RT-13B");
        assertThatThrownBy(() -> seatHoldService.createHold(
                        tripA.tripId(),
                        1,
                        3,
                        Instant.now().plusSeconds(120),
                        List.of(tripB.availableSeatIds().get(0))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not belong to the trip");
    }

    @Test
    void blockedPhysicalInventoryIsRejected() throws Exception {
        TripData trip = createTrip("HOLD-REG-14", "HOLD-RT-14");
        assertThatThrownBy(() -> seatHoldService.createHold(
                        trip.tripId(),
                        1,
                        3,
                        Instant.now().plusSeconds(120),
                        List.of(trip.blockedSeatId())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not physically AVAILABLE");
    }

    @Test
    void overlappingActiveAllocationCausesHoldFailure() throws Exception {
        TripData trip = createTrip("HOLD-REG-15", "HOLD-RT-15");
        UUID seat = trip.availableSeatIds().get(0);
        allocationService.allocate(
                trip.tripId(), seat, 1, 3, TripSeatAllocationState.BOOKED, null);

        assertThatThrownBy(() -> seatHoldService.createHold(
                        trip.tripId(), 2, 4, Instant.now().plusSeconds(180), List.of(seat)))
                .isInstanceOf(ApplicationConflictException.class);
    }

    @Test
    void adjacentSegmentHoldSucceeds() throws Exception {
        TripData trip = createTrip("HOLD-REG-16", "HOLD-RT-16");
        UUID seat = trip.availableSeatIds().get(0);
        allocationService.allocate(
                trip.tripId(), seat, 1, 3, TripSeatAllocationState.BOOKED, null);

        SeatHoldService.SeatHoldResult result = seatHoldService.createHold(
                trip.tripId(), 3, 4, Instant.now().plusSeconds(180), List.of(seat));
        assertThat(result.hold().getStatus()).isEqualTo(SeatHoldStatus.ACTIVE);
    }

    @Test
    void differentSeatHoldSucceeds() throws Exception {
        TripData trip = createTrip("HOLD-REG-17", "HOLD-RT-17");
        allocationService.allocate(
                trip.tripId(),
                trip.availableSeatIds().get(0),
                1,
                3,
                TripSeatAllocationState.BOOKED,
                null);

        SeatHoldService.SeatHoldResult result = seatHoldService.createHold(
                trip.tripId(),
                1,
                3,
                Instant.now().plusSeconds(180),
                List.of(trip.availableSeatIds().get(1)));
        assertThat(result.allocations()).hasSize(1);
    }

    @Test
    void exactSameSegmentConflicts() throws Exception {
        TripData trip = createTrip("HOLD-REG-18", "HOLD-RT-18");
        UUID seat = trip.availableSeatIds().get(0);
        seatHoldService.createHold(
                trip.tripId(), 1, 3, Instant.now().plusSeconds(180), List.of(seat));

        assertThatThrownBy(() -> seatHoldService.createHold(
                        trip.tripId(), 1, 3, Instant.now().plusSeconds(240), List.of(seat)))
                .isInstanceOf(ApplicationConflictException.class);
    }

    @Test
    void partialMultiSeatConflictRollsBackEntireHold() throws Exception {
        TripData trip = createTrip("HOLD-REG-19", "HOLD-RT-19");
        UUID seat1 = trip.availableSeatIds().get(0);
        UUID seat2 = trip.availableSeatIds().get(1);
        UUID seat3 = trip.availableSeatIds().get(2);
        allocationService.allocate(
                trip.tripId(), seat3, 1, 3, TripSeatAllocationState.BOOKED, null);

        long holdsBefore = count("seat_holds");
        long allocationsBefore = count("trip_seat_allocations");

        assertThatThrownBy(() -> seatHoldService.createHold(
                        trip.tripId(),
                        1,
                        3,
                        Instant.now().plusSeconds(300),
                        List.of(seat1, seat2, seat3)))
                .isInstanceOf(ApplicationConflictException.class);

        assertThat(count("seat_holds")).isEqualTo(holdsBefore);
        assertThat(count("trip_seat_allocations")).isEqualTo(allocationsBefore);
        assertThat(allocationRepository.findByInventory_IdAndStateInOrderByOriginSequenceAsc(
                        seat1, List.of(TripSeatAllocationState.HELD)))
                .isEmpty();
        assertThat(allocationRepository.findByInventory_IdAndStateInOrderByOriginSequenceAsc(
                        seat2, List.of(TripSeatAllocationState.HELD)))
                .isEmpty();
    }

    @Test
    void activeHoldCanBeConsumedExpiredAndCancelled() throws Exception {
        TripData trip = createTrip("HOLD-REG-20", "HOLD-RT-20");

        SeatHoldService.SeatHoldResult consumed = seatHoldService.createHold(
                trip.tripId(),
                1,
                3,
                Instant.now().plusSeconds(180),
                List.of(trip.availableSeatIds().get(0)));
        SeatHoldService.SeatHoldResult afterConsume = seatHoldService.consume(consumed.hold().getId());
        assertThat(afterConsume.hold().getStatus()).isEqualTo(SeatHoldStatus.CONSUMED);
        assertThat(afterConsume.allocations()).allMatch(a -> a.getState() == TripSeatAllocationState.HELD);
        assertThatThrownBy(() -> seatHoldService.expire(consumed.hold().getId()))
                .isInstanceOf(IllegalArgumentException.class);

        SeatHoldService.SeatHoldResult toExpire = seatHoldService.createHold(
                trip.tripId(),
                1,
                3,
                Instant.now().plusSeconds(180),
                List.of(trip.availableSeatIds().get(1)));
        SeatHoldService.SeatHoldResult afterExpire = seatHoldService.expire(toExpire.hold().getId());
        assertThat(afterExpire.hold().getStatus()).isEqualTo(SeatHoldStatus.EXPIRED);
        assertThat(afterExpire.allocations()).allMatch(a -> a.getState() == TripSeatAllocationState.EXPIRED);
        assertThatThrownBy(() -> seatHoldService.consume(toExpire.hold().getId()))
                .isInstanceOf(IllegalArgumentException.class);

        SeatHoldService.SeatHoldResult toCancel = seatHoldService.createHold(
                trip.tripId(),
                1,
                3,
                Instant.now().plusSeconds(180),
                List.of(trip.availableSeatIds().get(2)));
        SeatHoldService.SeatHoldResult afterCancel = seatHoldService.cancel(toCancel.hold().getId());
        assertThat(afterCancel.hold().getStatus()).isEqualTo(SeatHoldStatus.CANCELLED);
        assertThat(afterCancel.allocations()).allMatch(a -> a.getState() == TripSeatAllocationState.CANCELLED);
        assertThatThrownBy(() -> seatHoldService.cancel(toCancel.hold().getId()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void expiredOrCancelledAllocationsDoNotBlockNewHold() throws Exception {
        TripData trip = createTrip("HOLD-REG-24", "HOLD-RT-24");
        UUID seat = trip.availableSeatIds().get(0);

        SeatHoldService.SeatHoldResult first = seatHoldService.createHold(
                trip.tripId(), 1, 3, Instant.now().plusSeconds(180), List.of(seat));
        seatHoldService.expire(first.hold().getId());

        SeatHoldService.SeatHoldResult second = seatHoldService.createHold(
                trip.tripId(), 1, 3, Instant.now().plusSeconds(240), List.of(seat));
        assertThat(second.hold().getStatus()).isEqualTo(SeatHoldStatus.ACTIVE);

        seatHoldService.cancel(second.hold().getId());
        SeatHoldService.SeatHoldResult third = seatHoldService.createHold(
                trip.tripId(), 1, 3, Instant.now().plusSeconds(300), List.of(seat));
        assertThat(third.hold().getStatus()).isEqualTo(SeatHoldStatus.ACTIVE);
    }

    @Test
    void bookedAndHeldAllocationsBlockOverlappingHolds() throws Exception {
        TripData trip = createTrip("HOLD-REG-25", "HOLD-RT-25");
        UUID bookedSeat = trip.availableSeatIds().get(0);
        UUID heldSeat = trip.availableSeatIds().get(1);

        allocationService.allocate(
                trip.tripId(), bookedSeat, 1, 3, TripSeatAllocationState.BOOKED, null);
        seatHoldService.createHold(
                trip.tripId(), 1, 3, Instant.now().plusSeconds(180), List.of(heldSeat));

        assertThatThrownBy(() -> seatHoldService.createHold(
                        trip.tripId(), 2, 4, Instant.now().plusSeconds(180), List.of(bookedSeat)))
                .isInstanceOf(ApplicationConflictException.class);
        assertThatThrownBy(() -> seatHoldService.createHold(
                        trip.tripId(), 2, 4, Instant.now().plusSeconds(180), List.of(heldSeat)))
                .isInstanceOf(ApplicationConflictException.class);
    }

    @Test
    void concurrentOverlappingHoldsAllowOnlyOneSuccess() throws Exception {
        TripData trip = createTrip("HOLD-REG-27", "HOLD-RT-27");
        UUID tripId = trip.tripId();
        UUID seat = trip.availableSeatIds().get(0);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        seatHoldService.createHold(
                                tripId, 1, 3, Instant.now().plusSeconds(300), List.of(seat));
                        successes.incrementAndGet();
                    } catch (ApplicationConflictException exception) {
                        conflicts.incrementAndGet();
                    }
                    return null;
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> future : futures) {
                future.get(20, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(successes.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(1);
        assertThat(seatHoldRepository.findByTripIdAndStatusOrderByCreatedAtDesc(
                        tripId, SeatHoldStatus.ACTIVE))
                .hasSize(1);
    }

    @Test
    void authenticatedIdempotencyKeyReturnsSameHold() throws Exception {
        TripData trip = createTrip("HOLD-REG-IDEM", "HOLD-RT-IDEM");
        UUID userId = UUID.randomUUID();
        Instant expiresAt = Instant.now().plusSeconds(400);

        SeatHoldService.SeatHoldResult first = seatHoldService.createHold(
                trip.tripId(),
                1,
                3,
                expiresAt,
                List.of(trip.availableSeatIds().get(0)),
                userId,
                "checkout-1",
                "fp-a");
        SeatHoldService.SeatHoldResult second = seatHoldService.createHold(
                trip.tripId(),
                1,
                3,
                expiresAt,
                List.of(trip.availableSeatIds().get(0)),
                userId,
                "checkout-1",
                "fp-a");

        assertThat(second.hold().getId()).isEqualTo(first.hold().getId());
        assertThatThrownBy(() -> seatHoldService.createHold(
                        trip.tripId(),
                        1,
                        3,
                        expiresAt,
                        List.of(trip.availableSeatIds().get(0)),
                        userId,
                        "checkout-1",
                        "fp-b"))
                .isInstanceOf(ApplicationConflictException.class)
                .hasMessageContaining("fingerprint");
    }

    private long count(String table) {
        Long value = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return value == null ? 0L : value;
    }

    private TripData createTrip(String registration, String routeCode) throws Exception {
        Fixture fixture = createFixture(registration, routeCode, 4);
        Instant departure = Instant.parse("2026-11-10T12:30:00Z");
        Instant arrival = departure.plusSeconds(8 * 3600);
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
        UUID operatorId = createOperator("Hold Op " + registration, "Hold Co " + registration);
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

    private record Fixture(UUID busId, UUID routeId) { }

    private record TripData(UUID tripId, List<UUID> availableSeatIds, UUID blockedSeatId) { }
}
