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

import in.bluebustickets.bluebus.foundation.security.TestAccessTokenFactory;
import in.bluebustickets.bluebus.foundation.api.error.ApplicationConflictException;
import in.bluebustickets.bluebus.scheduling.domain.Int4Range;
import in.bluebustickets.bluebus.scheduling.domain.TripSeatAllocation;
import in.bluebustickets.bluebus.scheduling.domain.TripSeatAllocationState;
import in.bluebustickets.bluebus.scheduling.repository.TripSeatAllocationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
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
class TripSeatAllocationPostgresIntegrationTest {

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
    @Autowired private TripSeatAllocationService allocationService;
    @Autowired private TripSeatAllocationRepository allocationRepository;
    @Autowired private TestAccessTokenFactory testAccessTokenFactory;
    private String adminToken;

    @BeforeEach
    void seedPlatformAdmin() {
        adminToken = testAccessTokenFactory.issuePlatformAdmin().accessToken();
    }

    @Test
    void createValidAllocationHalfOpenRange() throws Exception {
        TripData trip = createTrip("ALLOC-REG-01", "ALLOC-RT-01");
        TripSeatAllocation allocation = allocationService.allocate(
                trip.tripId(),
                trip.availableSeatIds().get(0),
                1,
                3,
                TripSeatAllocationState.BOOKED,
                null);

        assertThat(allocation.getId()).isNotNull();
        assertThat(allocation.getOriginSequence()).isEqualTo(1);
        assertThat(allocation.getDestinationSequence()).isEqualTo(3);
        assertThat(allocation.getSegmentRange()).isEqualTo(Int4Range.halfOpen(1, 3));
        assertThat(allocation.getState()).isEqualTo(TripSeatAllocationState.BOOKED);
        assertThat(allocationRepository.findByTripIdOrderByOriginSequenceAsc(trip.tripId())).hasSize(1);
    }

    @Test
    void adjacentAllocationOnSameInventorySucceeds() throws Exception {
        TripData trip = createTrip("ALLOC-REG-02", "ALLOC-RT-02");
        UUID inventoryId = trip.availableSeatIds().get(0);

        allocationService.allocate(
                trip.tripId(), inventoryId, 1, 3, TripSeatAllocationState.BOOKED, null);
        TripSeatAllocation adjacent = allocationService.allocate(
                trip.tripId(), inventoryId, 3, 4, TripSeatAllocationState.BOOKED, null);

        assertThat(adjacent.getSegmentRange()).isEqualTo(Int4Range.halfOpen(3, 4));
        assertThat(allocationRepository.findByInventory_IdOrderByOriginSequenceAsc(inventoryId)).hasSize(2);
    }

    @Test
    void overlappingAllocationConflicts() throws Exception {
        TripData trip = createTrip("ALLOC-REG-03", "ALLOC-RT-03");
        UUID inventoryId = trip.availableSeatIds().get(0);

        allocationService.allocate(
                trip.tripId(), inventoryId, 1, 3, TripSeatAllocationState.BOOKED, null);

        assertThatThrownBy(() -> allocationService.allocate(
                        trip.tripId(), inventoryId, 2, 4, TripSeatAllocationState.BOOKED, null))
                .isInstanceOf(ApplicationConflictException.class);
    }

    @Test
    void exactSameAllocationConflicts() throws Exception {
        TripData trip = createTrip("ALLOC-REG-04", "ALLOC-RT-04");
        UUID inventoryId = trip.availableSeatIds().get(0);

        allocationService.allocate(
                trip.tripId(), inventoryId, 1, 3, TripSeatAllocationState.BOOKED, null);

        assertThatThrownBy(() -> allocationService.allocate(
                        trip.tripId(), inventoryId, 1, 3, TripSeatAllocationState.HELD, Instant.now().plusSeconds(60)))
                .isInstanceOf(ApplicationConflictException.class);
    }

    @Test
    void differentSeatsSameSegmentSucceed() throws Exception {
        TripData trip = createTrip("ALLOC-REG-05", "ALLOC-RT-05");

        allocationService.allocate(
                trip.tripId(),
                trip.availableSeatIds().get(0),
                1,
                3,
                TripSeatAllocationState.BOOKED,
                null);
        TripSeatAllocation second = allocationService.allocate(
                trip.tripId(),
                trip.availableSeatIds().get(1),
                1,
                3,
                TripSeatAllocationState.BOOKED,
                null);

        assertThat(second.getInventory().getId()).isEqualTo(trip.availableSeatIds().get(1));
    }

    @Test
    void differentTripsAreIsolatedForSameSegment() throws Exception {
        TripData tripA = createTrip("ALLOC-REG-06A", "ALLOC-RT-06A");
        TripData tripB = createTrip("ALLOC-REG-06B", "ALLOC-RT-06B");

        allocationService.allocate(
                tripA.tripId(),
                tripA.availableSeatIds().get(0),
                1,
                3,
                TripSeatAllocationState.BOOKED,
                null);
        TripSeatAllocation onB = allocationService.allocate(
                tripB.tripId(),
                tripB.availableSeatIds().get(0),
                1,
                3,
                TripSeatAllocationState.BOOKED,
                null);

        assertThat(onB.getTripId()).isEqualTo(tripB.tripId());
        assertThat(onB.getInventory().getId()).isNotEqualTo(tripA.availableSeatIds().get(0));
    }

    @Test
    void originEqualsDestinationIsRejected() throws Exception {
        TripData trip = createTrip("ALLOC-REG-07", "ALLOC-RT-07");

        assertThatThrownBy(() -> allocationService.allocate(
                        trip.tripId(),
                        trip.availableSeatIds().get(0),
                        2,
                        2,
                        TripSeatAllocationState.BOOKED,
                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("destination must be greater than origin");
    }

    @Test
    void originGreaterThanDestinationIsRejected() throws Exception {
        TripData trip = createTrip("ALLOC-REG-08", "ALLOC-RT-08");

        assertThatThrownBy(() -> allocationService.allocate(
                        trip.tripId(),
                        trip.availableSeatIds().get(0),
                        3,
                        1,
                        TripSeatAllocationState.BOOKED,
                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("destination must be greater than origin");
    }

    @Test
    void unknownOriginSequenceIsRejected() throws Exception {
        TripData trip = createTrip("ALLOC-REG-09", "ALLOC-RT-09");

        assertThatThrownBy(() -> allocationService.allocate(
                        trip.tripId(),
                        trip.availableSeatIds().get(0),
                        9,
                        10,
                        TripSeatAllocationState.BOOKED,
                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Origin sequence");
    }

    @Test
    void unknownDestinationSequenceIsRejected() throws Exception {
        TripData trip = createTrip("ALLOC-REG-10", "ALLOC-RT-10");

        assertThatThrownBy(() -> allocationService.allocate(
                        trip.tripId(),
                        trip.availableSeatIds().get(0),
                        1,
                        9,
                        TripSeatAllocationState.BOOKED,
                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Destination sequence");
    }

    @Test
    void blockedPhysicalInventoryIsRejected() throws Exception {
        TripData trip = createTrip("ALLOC-REG-11", "ALLOC-RT-11");

        assertThatThrownBy(() -> allocationService.allocate(
                        trip.tripId(),
                        trip.blockedSeatId(),
                        1,
                        3,
                        TripSeatAllocationState.BOOKED,
                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not physically AVAILABLE");
    }

    @Test
    void inventoryFromAnotherTripIsRejected() throws Exception {
        TripData tripA = createTrip("ALLOC-REG-12A", "ALLOC-RT-12A");
        TripData tripB = createTrip("ALLOC-REG-12B", "ALLOC-RT-12B");

        assertThatThrownBy(() -> allocationService.allocate(
                        tripA.tripId(),
                        tripB.availableSeatIds().get(0),
                        1,
                        3,
                        TripSeatAllocationState.BOOKED,
                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not belong to the trip");
    }

    @Test
    void expiredAllocationDoesNotBlockFutureAllocation() throws Exception {
        TripData trip = createTrip("ALLOC-REG-13", "ALLOC-RT-13");
        UUID inventoryId = trip.availableSeatIds().get(0);

        allocationService.allocate(
                trip.tripId(), inventoryId, 1, 3, TripSeatAllocationState.EXPIRED, null);
        TripSeatAllocation next = allocationService.allocate(
                trip.tripId(), inventoryId, 1, 3, TripSeatAllocationState.BOOKED, null);

        assertThat(next.getState()).isEqualTo(TripSeatAllocationState.BOOKED);
    }

    @Test
    void cancelledAllocationDoesNotBlockFutureAllocation() throws Exception {
        TripData trip = createTrip("ALLOC-REG-14", "ALLOC-RT-14");
        UUID inventoryId = trip.availableSeatIds().get(0);

        allocationService.allocate(
                trip.tripId(), inventoryId, 1, 3, TripSeatAllocationState.CANCELLED, null);
        TripSeatAllocation next = allocationService.allocate(
                trip.tripId(), inventoryId, 1, 3, TripSeatAllocationState.BOOKED, null);

        assertThat(next.getState()).isEqualTo(TripSeatAllocationState.BOOKED);
    }

    @Test
    void releasedAllocationDoesNotBlockFutureAllocation() throws Exception {
        TripData trip = createTrip("ALLOC-REG-15", "ALLOC-RT-15");
        UUID inventoryId = trip.availableSeatIds().get(0);

        allocationService.allocate(
                trip.tripId(), inventoryId, 1, 3, TripSeatAllocationState.RELEASED, null);
        TripSeatAllocation next = allocationService.allocate(
                trip.tripId(), inventoryId, 1, 3, TripSeatAllocationState.BOOKED, null);

        assertThat(next.getState()).isEqualTo(TripSeatAllocationState.BOOKED);
    }

    @Test
    void bookedAllocationBlocksOverlappingAllocation() throws Exception {
        TripData trip = createTrip("ALLOC-REG-16", "ALLOC-RT-16");
        UUID inventoryId = trip.availableSeatIds().get(0);

        allocationService.allocate(
                trip.tripId(), inventoryId, 1, 3, TripSeatAllocationState.BOOKED, null);

        assertThatThrownBy(() -> allocationService.allocate(
                        trip.tripId(), inventoryId, 2, 4, TripSeatAllocationState.HELD, Instant.now().plusSeconds(120)))
                .isInstanceOf(ApplicationConflictException.class);
    }

    @Test
    void heldAllocationBlocksOverlappingAllocation() throws Exception {
        TripData trip = createTrip("ALLOC-REG-17", "ALLOC-RT-17");
        UUID inventoryId = trip.availableSeatIds().get(0);

        allocationService.allocate(
                trip.tripId(),
                inventoryId,
                1,
                3,
                TripSeatAllocationState.HELD,
                Instant.now().plusSeconds(120));

        assertThatThrownBy(() -> allocationService.allocate(
                        trip.tripId(), inventoryId, 2, 4, TripSeatAllocationState.BOOKED, null))
                .isInstanceOf(ApplicationConflictException.class);
    }

    @Test
    void bookedAllocationCanCoexistWithAdjacentAllocation() throws Exception {
        TripData trip = createTrip("ALLOC-REG-18", "ALLOC-RT-18");
        UUID inventoryId = trip.availableSeatIds().get(0);

        allocationService.allocate(
                trip.tripId(), inventoryId, 1, 3, TripSeatAllocationState.BOOKED, null);
        TripSeatAllocation adjacent = allocationService.allocate(
                trip.tripId(), inventoryId, 3, 4, TripSeatAllocationState.HELD, Instant.now().plusSeconds(90));

        assertThat(adjacent.getSegmentRange()).isEqualTo(Int4Range.halfOpen(3, 4));
        assertThat(allocationRepository.findByInventory_IdAndStateInOrderByOriginSequenceAsc(
                        inventoryId,
                        List.of(TripSeatAllocationState.HELD, TripSeatAllocationState.BOOKED, TripSeatAllocationState.BLOCKED)))
                .hasSize(2);
    }

    @Test
    void concurrentOverlappingAllocationsAllowOnlyOneSuccess() throws Exception {
        TripData trip = createTrip("ALLOC-REG-20", "ALLOC-RT-20");
        UUID tripId = trip.tripId();
        UUID inventoryId = trip.availableSeatIds().get(0);

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
                        allocationService.allocate(
                                tripId,
                                inventoryId,
                                1,
                                3,
                                TripSeatAllocationState.HELD,
                                Instant.now().plusSeconds(180));
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
        assertThat(allocationRepository.findByInventory_IdAndStateInOrderByOriginSequenceAsc(
                        inventoryId, List.of(TripSeatAllocationState.HELD)))
                .hasSize(1);
    }

    private TripData createTrip(String registration, String routeCode) throws Exception {
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
        assertThat(available).hasSizeGreaterThanOrEqualTo(2);
        assertThat(blocked).isNotNull();
        return new TripData(tripId, available, blocked);
    }

    private Fixture createFixture(String registration, String routeCode, int seatCount) throws Exception {
        UUID operatorId = createOperator("Alloc Op " + registration, "Alloc Co " + registration);
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
