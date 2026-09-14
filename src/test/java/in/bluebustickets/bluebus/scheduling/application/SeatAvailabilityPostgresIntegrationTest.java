package in.bluebustickets.bluebus.scheduling.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import in.bluebustickets.bluebus.foundation.api.error.ResourceNotFoundException;
import in.bluebustickets.bluebus.scheduling.domain.TripSeatAllocationState;
import in.bluebustickets.bluebus.scheduling.domain.TripSeatInventoryStatus;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
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
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser
class SeatAvailabilityPostgresIntegrationTest {

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
    @Autowired private SeatAvailabilityService availabilityService;
    @Autowired private TripSeatAllocationService allocationService;
    @Autowired private EntityManagerFactory entityManagerFactory;

    @Test
    void projectsAllPhysicalSeatsWithCorrectAvailabilityRules() throws Exception {
        TripData trip = createTrip("AVAIL-REG-01", "AVAIL-RT-01");
        UUID s1 = trip.availableSeatIds().get(0);
        UUID s2 = trip.availableSeatIds().get(1);
        UUID s3 = trip.availableSeatIds().get(2);
        UUID blocked = trip.blockedSeatId();

        allocationService.allocate(trip.tripId(), s1, 1, 3, TripSeatAllocationState.BOOKED, null);
        allocationService.allocate(trip.tripId(), s2, 3, 4, TripSeatAllocationState.BOOKED, null);
        allocationService.allocate(
                trip.tripId(), s3, 2, 4, TripSeatAllocationState.HELD, Instant.now().plusSeconds(300));

        List<SeatAvailabilityResult> results =
                availabilityService.getSeatAvailability(trip.tripId(), 1, 3);

        assertThat(results).hasSize(4);
        Map<UUID, SeatAvailabilityResult> byId = results.stream()
                .collect(Collectors.toMap(SeatAvailabilityResult::inventoryId, Function.identity()));

        assertThat(byId.get(s1).journeyAvailability()).isEqualTo(JourneySeatAvailability.UNAVAILABLE);
        assertThat(byId.get(s2).journeyAvailability()).isEqualTo(JourneySeatAvailability.AVAILABLE);
        assertThat(byId.get(s3).journeyAvailability()).isEqualTo(JourneySeatAvailability.UNAVAILABLE);
        assertThat(byId.get(blocked).physicalStatus()).isEqualTo(TripSeatInventoryStatus.BLOCKED);
        assertThat(byId.get(blocked).journeyAvailability()).isEqualTo(JourneySeatAvailability.UNAVAILABLE);
        assertThat(results).extracting(SeatAvailabilityResult::seatNumber).isSorted();
    }

    @Test
    void availableSeatWithNoAllocationsIsAvailable() throws Exception {
        TripData trip = createTrip("AVAIL-REG-02", "AVAIL-RT-02");
        SeatAvailabilityResult seat = findSeat(
                availabilityService.getSeatAvailability(trip.tripId(), 1, 3),
                trip.availableSeatIds().get(0));
        assertThat(seat.journeyAvailability()).isEqualTo(JourneySeatAvailability.AVAILABLE);
        assertThat(seat.physicalStatus()).isEqualTo(TripSeatInventoryStatus.AVAILABLE);
    }

    @Test
    void blockedPhysicalInventoryIsUnavailable() throws Exception {
        TripData trip = createTrip("AVAIL-REG-03", "AVAIL-RT-03");
        SeatAvailabilityResult seat = findSeat(
                availabilityService.getSeatAvailability(trip.tripId(), 1, 4),
                trip.blockedSeatId());
        assertThat(seat.journeyAvailability()).isEqualTo(JourneySeatAvailability.UNAVAILABLE);
    }

    @Test
    void bookedHeldAndBlockedAllocationsMakeSeatUnavailable() throws Exception {
        TripData trip = createTrip("AVAIL-REG-04", "AVAIL-RT-04");
        UUID booked = trip.availableSeatIds().get(0);
        UUID held = trip.availableSeatIds().get(1);
        UUID blockedAlloc = trip.availableSeatIds().get(2);

        allocationService.allocate(trip.tripId(), booked, 1, 3, TripSeatAllocationState.BOOKED, null);
        allocationService.allocate(
                trip.tripId(), held, 1, 3, TripSeatAllocationState.HELD, Instant.now().plusSeconds(120));
        allocationService.allocate(trip.tripId(), blockedAlloc, 1, 3, TripSeatAllocationState.BLOCKED, null);

        List<SeatAvailabilityResult> results =
                availabilityService.getSeatAvailability(trip.tripId(), 1, 3);
        assertThat(findSeat(results, booked).journeyAvailability())
                .isEqualTo(JourneySeatAvailability.UNAVAILABLE);
        assertThat(findSeat(results, held).journeyAvailability())
                .isEqualTo(JourneySeatAvailability.UNAVAILABLE);
        assertThat(findSeat(results, blockedAlloc).journeyAvailability())
                .isEqualTo(JourneySeatAvailability.UNAVAILABLE);
    }

    @Test
    void expiredCancelledAndReleasedAllocationsDoNotBlock() throws Exception {
        TripData trip = createTrip("AVAIL-REG-05", "AVAIL-RT-05");
        UUID expired = trip.availableSeatIds().get(0);
        UUID cancelled = trip.availableSeatIds().get(1);
        UUID released = trip.availableSeatIds().get(2);

        allocationService.allocate(trip.tripId(), expired, 1, 3, TripSeatAllocationState.EXPIRED, null);
        allocationService.allocate(trip.tripId(), cancelled, 1, 3, TripSeatAllocationState.CANCELLED, null);
        allocationService.allocate(trip.tripId(), released, 1, 3, TripSeatAllocationState.RELEASED, null);

        List<SeatAvailabilityResult> results =
                availabilityService.getSeatAvailability(trip.tripId(), 1, 3);
        assertThat(findSeat(results, expired).journeyAvailability())
                .isEqualTo(JourneySeatAvailability.AVAILABLE);
        assertThat(findSeat(results, cancelled).journeyAvailability())
                .isEqualTo(JourneySeatAvailability.AVAILABLE);
        assertThat(findSeat(results, released).journeyAvailability())
                .isEqualTo(JourneySeatAvailability.AVAILABLE);
    }

    @Test
    void adjacentBookedAndHeldAllocationsRemainAvailable() throws Exception {
        TripData trip = createTrip("AVAIL-REG-06", "AVAIL-RT-06");
        UUID booked = trip.availableSeatIds().get(0);
        UUID held = trip.availableSeatIds().get(1);

        allocationService.allocate(trip.tripId(), booked, 3, 4, TripSeatAllocationState.BOOKED, null);
        allocationService.allocate(
                trip.tripId(), held, 3, 4, TripSeatAllocationState.HELD, Instant.now().plusSeconds(180));

        List<SeatAvailabilityResult> results =
                availabilityService.getSeatAvailability(trip.tripId(), 1, 3);
        assertThat(findSeat(results, booked).journeyAvailability())
                .isEqualTo(JourneySeatAvailability.AVAILABLE);
        assertThat(findSeat(results, held).journeyAvailability())
                .isEqualTo(JourneySeatAvailability.AVAILABLE);
    }

    @Test
    void exactSameSegmentAndPartialOverlapsAreUnavailable() throws Exception {
        TripData trip = createTrip("AVAIL-REG-07", "AVAIL-RT-07");
        UUID exact = trip.availableSeatIds().get(0);
        UUID left = trip.availableSeatIds().get(1);
        UUID right = trip.availableSeatIds().get(2);

        allocationService.allocate(trip.tripId(), exact, 1, 3, TripSeatAllocationState.BOOKED, null);
        // Existing [1,3) overlaps requested [2,4) from the left.
        allocationService.allocate(trip.tripId(), left, 1, 3, TripSeatAllocationState.BOOKED, null);
        // Existing [2,4) overlaps requested [1,3) from the right.
        allocationService.allocate(trip.tripId(), right, 2, 4, TripSeatAllocationState.BOOKED, null);

        assertThat(findSeat(availabilityService.getSeatAvailability(trip.tripId(), 1, 3), exact)
                        .journeyAvailability())
                .isEqualTo(JourneySeatAvailability.UNAVAILABLE);
        assertThat(findSeat(availabilityService.getSeatAvailability(trip.tripId(), 2, 4), left)
                        .journeyAvailability())
                .isEqualTo(JourneySeatAvailability.UNAVAILABLE);
        assertThat(findSeat(availabilityService.getSeatAvailability(trip.tripId(), 1, 3), right)
                        .journeyAvailability())
                .isEqualTo(JourneySeatAvailability.UNAVAILABLE);
    }

    @Test
    void containingAndContainedRangesAreUnavailable() throws Exception {
        TripData trip = createTrip("AVAIL-REG-08", "AVAIL-RT-08");
        UUID contained = trip.availableSeatIds().get(0);
        UUID container = trip.availableSeatIds().get(1);

        allocationService.allocate(trip.tripId(), contained, 1, 4, TripSeatAllocationState.BOOKED, null);
        allocationService.allocate(trip.tripId(), container, 2, 3, TripSeatAllocationState.BOOKED, null);

        assertThat(findSeat(
                        availabilityService.getSeatAvailability(trip.tripId(), 2, 3),
                        contained)
                        .journeyAvailability())
                .isEqualTo(JourneySeatAvailability.UNAVAILABLE);
        assertThat(findSeat(
                        availabilityService.getSeatAvailability(trip.tripId(), 1, 4),
                        container)
                        .journeyAvailability())
                .isEqualTo(JourneySeatAvailability.UNAVAILABLE);
    }

    @Test
    void heldAllocationWithPastExpiresAtStillBlocksUntilExplicitExpiry() throws Exception {
        TripData trip = createTrip("AVAIL-REG-HELD-PAST", "AVAIL-RT-HELD-PAST");
        UUID seat = trip.availableSeatIds().get(0);
        allocationService.allocate(
                trip.tripId(),
                seat,
                1,
                3,
                TripSeatAllocationState.HELD,
                Instant.now().minusSeconds(60));

        assertThat(findSeat(availabilityService.getSeatAvailability(trip.tripId(), 1, 3), seat)
                        .journeyAvailability())
                .isEqualTo(JourneySeatAvailability.UNAVAILABLE);
    }

    @Test
    void validationRejectsInvalidTripAndSequences() throws Exception {
        TripData trip = createTrip("AVAIL-REG-09", "AVAIL-RT-09");

        assertThatThrownBy(() -> availabilityService.getSeatAvailability(UUID.randomUUID(), 1, 3))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> availabilityService.getSeatAvailability(trip.tripId(), 9, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Origin sequence");
        assertThatThrownBy(() -> availabilityService.getSeatAvailability(trip.tripId(), 1, 9))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Destination sequence");
        assertThatThrownBy(() -> availabilityService.getSeatAvailability(trip.tripId(), 3, 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("destination must be greater than origin");
        assertThatThrownBy(() -> availabilityService.getSeatAvailability(trip.tripId(), 4, 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("destination must be greater than origin");
    }

    @Test
    void projectionUsesBoundedQueryCountWithoutPerSeatAllocationLookups() throws Exception {
        TripData trip = createTrip("AVAIL-REG-10", "AVAIL-RT-10");
        allocationService.allocate(
                trip.tripId(),
                trip.availableSeatIds().get(0),
                1,
                3,
                TripSeatAllocationState.BOOKED,
                null);
        allocationService.allocate(
                trip.tripId(),
                trip.availableSeatIds().get(1),
                2,
                4,
                TripSeatAllocationState.HELD,
                Instant.now().plusSeconds(200));

        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        List<SeatAvailabilityResult> results =
                availabilityService.getSeatAvailability(trip.tripId(), 1, 3);
        long statements = statistics.getPrepareStatementCount();

        assertThat(results).hasSize(4);
        // exists(trip) + origin stop + destination stop + inventory + overlapping allocations
        assertThat(statements).isLessThanOrEqualTo(6);
        assertThat(statements).isGreaterThanOrEqualTo(4);
    }

    private static SeatAvailabilityResult findSeat(List<SeatAvailabilityResult> results, UUID inventoryId) {
        return results.stream()
                .filter(result -> result.inventoryId().equals(inventoryId))
                .findFirst()
                .orElseThrow();
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
        UUID operatorId = createOperator("Avail Op " + registration, "Avail Co " + registration);
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
