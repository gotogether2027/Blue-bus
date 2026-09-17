package in.bluebustickets.bluebus.scheduling.api;

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
import in.bluebustickets.bluebus.scheduling.application.JourneySeatAvailability;
import in.bluebustickets.bluebus.scheduling.application.SeatHoldExpiryService;
import in.bluebustickets.bluebus.scheduling.application.SeatHoldService;
import in.bluebustickets.bluebus.scheduling.application.TripSeatAllocationService;
import in.bluebustickets.bluebus.scheduling.domain.SeatHoldStatus;
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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CustomerSeatHoldApiPostgresIntegrationTest {

    private static final long HOLD_TTL_SECONDS = 600L;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("blue-bus.seat-holds.expiry.enabled", () -> "false");
        registry.add("blue-bus.seat-holds.create.ttl-seconds", () -> String.valueOf(HOLD_TTL_SECONDS));
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TripSeatAllocationService allocationService;
    @Autowired private SeatHoldService seatHoldService;
    @Autowired private SeatHoldExpiryService seatHoldExpiryService;
    @Autowired private SeatHoldRepository seatHoldRepository;
    @Autowired private TripSeatAllocationRepository allocationRepository;
    @Autowired private TestAccessTokenFactory testAccessTokenFactory;
    private String adminToken;

    @BeforeEach
    void seedPlatformAdmin() {
        adminToken = testAccessTokenFactory.issuePlatformAdmin().accessToken();
    }

    @Test
    @WithMockUser
    void createsValidSingleAndMultiSeatHolds() throws Exception {
        TripFixture trip = createTrip("HOLD-API-01", "HOLD-API-RT-01");
        Instant before = Instant.now();

        MvcResult single = mockMvc.perform(post("/api/v1/trips/{tripId}/holds", trip.tripId())
                        .with(anonymous())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(holdBody(
                                trip.stopId(1),
                                trip.stopId(3),
                                List.of(trip.availableSeatIds().get(0)),
                                null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tripId").value(trip.tripId().toString()))
                .andExpect(jsonPath("$.originStopId").value(trip.stopId(1).toString()))
                .andExpect(jsonPath("$.destinationStopId").value(trip.stopId(3).toString()))
                .andExpect(jsonPath("$.originSequence").value(1))
                .andExpect(jsonPath("$.destinationSequence").value(3))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.seatInventoryIds.length()").value(1))
                .andReturn();

        JsonNode singleBody = objectMapper.readTree(single.getResponse().getContentAsString());
        Instant expiresAt = Instant.parse(singleBody.get("expiresAt").asText());
        assertThat(expiresAt).isAfter(before.plusSeconds(HOLD_TTL_SECONDS - 5));
        assertThat(expiresAt).isBefore(before.plusSeconds(HOLD_TTL_SECONDS + 30));
        assertThat(singleBody.has("userId")).isFalse();

        UUID s1 = trip.availableSeatIds().get(1);
        UUID s2 = trip.availableSeatIds().get(2);
        MvcResult multi = mockMvc.perform(post("/api/v1/trips/{tripId}/holds", trip.tripId())
                        .with(anonymous())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(holdBody(trip.stopId(1), trip.stopId(4), List.of(s1, s2), null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.seatInventoryIds.length()").value(2))
                .andReturn();

        UUID multiHoldId = UUID.fromString(
                objectMapper.readTree(multi.getResponse().getContentAsString()).get("holdId").asText());
        assertThat(allocationRepository.findByHoldIdOrderByCreatedAtAsc(multiHoldId))
                .hasSize(2)
                .allMatch(a -> a.getState() == TripSeatAllocationState.HELD);
    }

    @Test
    @WithMockUser
    void rejectsHoldsOnUnsaleableTripsWithConflict() throws Exception {
        TripFixture saleable = createTrip("HOLD-API-SALE", "HOLD-API-RT-SALE");
        mockMvc.perform(post("/api/v1/trips/{tripId}/holds", saleable.tripId())
                        .with(anonymous())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(holdBody(
                                saleable.stopId(1),
                                saleable.stopId(3),
                                List.of(saleable.availableSeatIds().get(0)),
                                null)))
                .andExpect(status().isCreated());

        Instant now = Instant.now();
        TripFixture beforeOpen = createTrip("HOLD-API-OPEN", "HOLD-API-RT-OPEN");
        jdbcTemplate.update(
                "UPDATE trips SET booking_opens_at = ? WHERE id = ?",
                java.sql.Timestamp.from(now.plusSeconds(3600)),
                beforeOpen.tripId());
        expectHoldConflict(beforeOpen, "Booking is not open.");

        TripFixture afterClose = createTrip("HOLD-API-CLOSE", "HOLD-API-RT-CLOSE");
        jdbcTemplate.update(
                "UPDATE trips SET booking_closes_at = ? WHERE id = ?",
                java.sql.Timestamp.from(now.minusSeconds(60)),
                afterClose.tripId());
        expectHoldConflict(afterClose, "Booking is closed.");

        TripFixture departed = createTrip("HOLD-API-DEP", "HOLD-API-RT-DEP");
        Instant past = now.minusSeconds(120);
        jdbcTemplate.update(
                """
                        UPDATE trips
                        SET scheduled_departure_at = ?,
                            scheduled_arrival_at = ?,
                            booking_closes_at = ?
                        WHERE id = ?
                        """,
                java.sql.Timestamp.from(past),
                java.sql.Timestamp.from(past.plusSeconds(3600)),
                java.sql.Timestamp.from(past.minusSeconds(60)),
                departed.tripId());
        mockMvc.perform(post("/api/v1/trips/{tripId}/holds", departed.tripId())
                        .with(anonymous())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(holdBody(
                                departed.stopId(1),
                                departed.stopId(3),
                                List.of(departed.availableSeatIds().get(0)),
                                null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));

        TripFixture cancelled = createTrip("HOLD-API-CAN", "HOLD-API-RT-CAN");
        jdbcTemplate.update("UPDATE trips SET status = 'CANCELLED' WHERE id = ?", cancelled.tripId());
        expectHoldConflict(cancelled, "Trip is not on sale.");

        TripFixture draft = createTrip("HOLD-API-DRAFT", "HOLD-API-RT-DRAFT");
        jdbcTemplate.update("UPDATE trips SET status = 'DRAFT' WHERE id = ?", draft.tripId());
        expectHoldConflict(draft, "Trip is not on sale.");
    }

    private void expectHoldConflict(TripFixture trip, String message) throws Exception {
        mockMvc.perform(post("/api/v1/trips/{tripId}/holds", trip.tripId())
                        .with(anonymous())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(holdBody(
                                trip.stopId(1),
                                trip.stopId(3),
                                List.of(trip.availableSeatIds().get(0)),
                                null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value(message));
    }

    @Test
    @WithMockUser
    void rejectsInvalidCreateRequests() throws Exception {
        TripFixture trip = createTrip("HOLD-API-02", "HOLD-API-RT-02");
        TripFixture other = createTrip("HOLD-API-02B", "HOLD-API-RT-02B");
        UUID seat = trip.availableSeatIds().get(0);

        mockMvc.perform(post("/api/v1/trips/{tripId}/holds", trip.tripId())
                        .with(anonymous())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"destinationStopId":"%s","seatInventoryIds":["%s"]}
                                """.formatted(trip.stopId(3), seat)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        mockMvc.perform(post("/api/v1/trips/{tripId}/holds", trip.tripId())
                        .with(anonymous())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"originStopId":"%s","seatInventoryIds":["%s"]}
                                """.formatted(trip.stopId(1), seat)))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/trips/{tripId}/holds", trip.tripId())
                        .with(anonymous())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(holdBody(trip.stopId(1), trip.stopId(3), List.of(), null)))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/trips/{tripId}/holds", trip.tripId())
                        .with(anonymous())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(holdBody(trip.stopId(1), trip.stopId(3), List.of(seat, seat), null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Seat hold inventory ids must be unique"));

        mockMvc.perform(post("/api/v1/trips/{tripId}/holds", trip.tripId())
                        .with(anonymous())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(holdBody(trip.stopId(3), trip.stopId(1), List.of(seat), null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Destination stop must be after the origin stop"));

        mockMvc.perform(post("/api/v1/trips/{tripId}/holds", trip.tripId())
                        .with(anonymous())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(holdBody(trip.stopId(2), trip.stopId(2), List.of(seat), null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Destination stop must be after the origin stop"));

        mockMvc.perform(post("/api/v1/trips/{tripId}/holds", UUID.randomUUID())
                        .with(anonymous())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(holdBody(trip.stopId(1), trip.stopId(3), List.of(seat), null)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Trip was not found."));

        mockMvc.perform(post("/api/v1/trips/{tripId}/holds", trip.tripId())
                        .with(anonymous())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(holdBody(other.stopId(1), trip.stopId(3), List.of(seat), null)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Origin trip stop was not found for this trip."));

        mockMvc.perform(post("/api/v1/trips/{tripId}/holds", trip.tripId())
                        .with(anonymous())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(holdBody(trip.stopId(1), other.stopId(3), List.of(seat), null)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Destination trip stop was not found for this trip."));

        mockMvc.perform(post("/api/v1/trips/{tripId}/holds", trip.tripId())
                        .with(anonymous())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(holdBody(
                                trip.stopId(1),
                                trip.stopId(3),
                                List.of(other.availableSeatIds().get(0)),
                                null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Trip seat inventory does not belong to the trip"));

        mockMvc.perform(post("/api/v1/trips/{tripId}/holds", trip.tripId())
                        .with(anonymous())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(holdBody(
                                trip.stopId(1),
                                trip.stopId(3),
                                List.of(trip.blockedSeatId()),
                                null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Trip seat inventory is not physically AVAILABLE"));

        mockMvc.perform(post("/api/v1/trips/{tripId}/holds", trip.tripId())
                        .with(anonymous())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(holdBody(
                                trip.stopId(1),
                                trip.stopId(3),
                                List.of(UUID.randomUUID()),
                                null)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Trip seat inventory was not found."));
    }

    @Test
    @WithMockUser
    void conflictsHistoricalStatesAndAdjacentSegments() throws Exception {
        TripFixture trip = createTrip("HOLD-API-03", "HOLD-API-RT-03");
        UUID heldSeat = trip.availableSeatIds().get(0);
        UUID bookedSeat = trip.availableSeatIds().get(1);
        UUID freeSeat = trip.availableSeatIds().get(2);

        createHoldViaApi(trip, trip.stopId(1), trip.stopId(3), List.of(heldSeat));
        allocationService.allocate(trip.tripId(), bookedSeat, 1, 3, TripSeatAllocationState.BOOKED, null);

        mockMvc.perform(post("/api/v1/trips/{tripId}/holds", trip.tripId())
                        .with(anonymous())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(holdBody(trip.stopId(2), trip.stopId(4), List.of(heldSeat), null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));

        mockMvc.perform(post("/api/v1/trips/{tripId}/holds", trip.tripId())
                        .with(anonymous())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(holdBody(trip.stopId(2), trip.stopId(4), List.of(bookedSeat), null)))
                .andExpect(status().isConflict());

        TripFixture hist = createTrip("HOLD-API-03H", "HOLD-API-RT-03H");
        UUID seat = hist.availableSeatIds().get(0);

        SeatHoldService.SeatHoldResult expired = seatHoldService.createHold(
                hist.tripId(), 1, 3, Instant.now().plusSeconds(180), List.of(seat));
        seatHoldService.expire(expired.hold().getId());
        mockMvc.perform(post("/api/v1/trips/{tripId}/holds", hist.tripId())
                        .with(anonymous())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(holdBody(hist.stopId(1), hist.stopId(3), List.of(seat), null)))
                .andExpect(status().isCreated());

        UUID cancelledSeat = hist.availableSeatIds().get(1);
        SeatHoldService.SeatHoldResult cancelled = seatHoldService.createHold(
                hist.tripId(), 1, 3, Instant.now().plusSeconds(180), List.of(cancelledSeat));
        seatHoldService.cancel(cancelled.hold().getId());
        mockMvc.perform(post("/api/v1/trips/{tripId}/holds", hist.tripId())
                        .with(anonymous())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(holdBody(hist.stopId(1), hist.stopId(3), List.of(cancelledSeat), null)))
                .andExpect(status().isCreated());

        UUID releasedSeat = hist.availableSeatIds().get(2);
        allocationService.allocate(
                hist.tripId(), releasedSeat, 1, 3, TripSeatAllocationState.RELEASED, null);
        mockMvc.perform(post("/api/v1/trips/{tripId}/holds", hist.tripId())
                        .with(anonymous())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(holdBody(hist.stopId(1), hist.stopId(3), List.of(releasedSeat), null)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/trips/{tripId}/holds", trip.tripId())
                        .with(anonymous())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(holdBody(trip.stopId(3), trip.stopId(4), List.of(freeSeat), null)))
                .andExpect(status().isCreated());
    }

    @Test
    @WithMockUser
    void multiSeatConflictRollsBackEntireHold() throws Exception {
        TripFixture trip = createTrip("HOLD-API-04", "HOLD-API-RT-04");
        UUID free = trip.availableSeatIds().get(0);
        UUID conflicted = trip.availableSeatIds().get(1);
        allocationService.allocate(
                trip.tripId(), conflicted, 1, 3, TripSeatAllocationState.BOOKED, null);

        long holdsBefore = count("seat_holds");
        long allocationsBefore = count("trip_seat_allocations");

        mockMvc.perform(post("/api/v1/trips/{tripId}/holds", trip.tripId())
                        .with(anonymous())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(holdBody(
                                trip.stopId(1),
                                trip.stopId(3),
                                List.of(free, conflicted),
                                null)))
                .andExpect(status().isConflict());

        assertThat(count("seat_holds")).isEqualTo(holdsBefore);
        assertThat(count("trip_seat_allocations")).isEqualTo(allocationsBefore);
        assertThat(jdbcTemplate.queryForObject(
                        """
                        SELECT COUNT(*) FROM trip_seat_allocations
                        WHERE inventory_id = ? AND state = 'HELD'
                        """,
                        Long.class,
                        free))
                .isZero();
    }

    @Test
    @WithMockUser
    void anonymousIdempotencyIsNotDbEnforcedAndAuthenticatedSemanticsRemain() throws Exception {
        TripFixture trip = createTrip("HOLD-API-05", "HOLD-API-RT-05");
        UUID seatA = trip.availableSeatIds().get(0);
        UUID seatB = trip.availableSeatIds().get(1);

        MvcResult first = createHoldViaApi(trip, trip.stopId(1), trip.stopId(3), List.of(seatA), "anon-key-1");
        MvcResult second = createHoldViaApi(trip, trip.stopId(1), trip.stopId(3), List.of(seatB), "anon-key-1");
        String firstId = objectMapper.readTree(first.getResponse().getContentAsString()).get("holdId").asText();
        String secondId = objectMapper.readTree(second.getResponse().getContentAsString()).get("holdId").asText();
        // V7 does not uniquely enforce (NULL user_id, idempotency_key)
        assertThat(secondId).isNotEqualTo(firstId);

        UUID userId = UUID.randomUUID();
        Instant expiresAt = Instant.now().plusSeconds(400);
        SeatHoldService.SeatHoldResult authFirst = seatHoldService.createHold(
                trip.tripId(),
                2,
                4,
                expiresAt,
                List.of(trip.availableSeatIds().get(2)),
                userId,
                "checkout-api",
                "fp-same");
        SeatHoldService.SeatHoldResult authSecond = seatHoldService.createHold(
                trip.tripId(),
                2,
                4,
                expiresAt,
                List.of(trip.availableSeatIds().get(2)),
                userId,
                "checkout-api",
                "fp-same");
        assertThat(authSecond.hold().getId()).isEqualTo(authFirst.hold().getId());
    }

    @Test
    @WithMockUser
    void getHoldReturnsCurrentStateWithoutMutation() throws Exception {
        TripFixture trip = createTrip("HOLD-API-06", "HOLD-API-RT-06");
        MvcResult created = createHoldViaApi(
                trip, trip.stopId(1), trip.stopId(3), List.of(trip.availableSeatIds().get(0)));
        UUID holdId = UUID.fromString(
                objectMapper.readTree(created.getResponse().getContentAsString()).get("holdId").asText());

        mockMvc.perform(get("/api/v1/holds/{holdId}", holdId).with(anonymous()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.holdId").value(holdId.toString()))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.originStopId").value(trip.stopId(1).toString()))
                .andExpect(jsonPath("$.destinationStopId").value(trip.stopId(3).toString()));

        assertThat(seatHoldRepository.findById(holdId).orElseThrow().getStatus())
                .isEqualTo(SeatHoldStatus.ACTIVE);

        seatHoldService.expire(holdId);
        mockMvc.perform(get("/api/v1/holds/{holdId}", holdId).with(anonymous()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXPIRED"));

        UUID cancelId = UUID.fromString(objectMapper
                .readTree(createHoldViaApi(
                                trip,
                                trip.stopId(1),
                                trip.stopId(3),
                                List.of(trip.availableSeatIds().get(1)))
                        .getResponse()
                        .getContentAsString())
                .get("holdId")
                .asText());
        seatHoldService.cancel(cancelId);
        mockMvc.perform(get("/api/v1/holds/{holdId}", cancelId).with(anonymous()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        UUID consumeId = UUID.fromString(objectMapper
                .readTree(createHoldViaApi(
                                trip,
                                trip.stopId(1),
                                trip.stopId(3),
                                List.of(trip.availableSeatIds().get(2)))
                        .getResponse()
                        .getContentAsString())
                .get("holdId")
                .asText());
        seatHoldService.consume(consumeId);
        mockMvc.perform(get("/api/v1/holds/{holdId}", consumeId).with(anonymous()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONSUMED"));

        mockMvc.perform(get("/api/v1/holds/{holdId}", UUID.randomUUID()).with(anonymous()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Seat hold was not found."));

        // Past-due ACTIVE remains ACTIVE until reaper (GET must not mutate)
        TripFixture pastDueTrip = createTrip("HOLD-API-06P", "HOLD-API-RT-06P");
        UUID pastDueHold = UUID.fromString(objectMapper
                .readTree(createHoldViaApi(
                                pastDueTrip,
                                pastDueTrip.stopId(1),
                                pastDueTrip.stopId(3),
                                List.of(pastDueTrip.availableSeatIds().get(0)))
                        .getResponse()
                        .getContentAsString())
                .get("holdId")
                .asText());
        jdbcTemplate.update(
                "UPDATE seat_holds SET expires_at = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(60)),
                pastDueHold);
        mockMvc.perform(get("/api/v1/holds/{holdId}", pastDueHold).with(anonymous()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        assertThat(seatHoldRepository.findById(pastDueHold).orElseThrow().getStatus())
                .isEqualTo(SeatHoldStatus.ACTIVE);
    }

    @Test
    @WithMockUser
    void cancelTransitionsAndRejectsTerminalStates() throws Exception {
        TripFixture trip = createTrip("HOLD-API-07", "HOLD-API-RT-07");
        UUID holdId = UUID.fromString(objectMapper
                .readTree(createHoldViaApi(
                                trip,
                                trip.stopId(1),
                                trip.stopId(3),
                                List.of(trip.availableSeatIds().get(0)))
                        .getResponse()
                        .getContentAsString())
                .get("holdId")
                .asText());

        mockMvc.perform(delete("/api/v1/holds/{holdId}", holdId).with(anonymous()))
                .andExpect(status().isNoContent());
        assertThat(seatHoldRepository.findById(holdId).orElseThrow().getStatus())
                .isEqualTo(SeatHoldStatus.CANCELLED);
        assertThat(allocationRepository.findByHoldIdOrderByCreatedAtAsc(holdId))
                .allMatch(a -> a.getState() == TripSeatAllocationState.CANCELLED);
        assertThat(count("seat_holds")).isGreaterThanOrEqualTo(1);

        mockMvc.perform(delete("/api/v1/holds/{holdId}", holdId).with(anonymous()))
                .andExpect(status().isNoContent());

        UUID expiredId = UUID.fromString(objectMapper
                .readTree(createHoldViaApi(
                                trip,
                                trip.stopId(1),
                                trip.stopId(3),
                                List.of(trip.availableSeatIds().get(1)))
                        .getResponse()
                        .getContentAsString())
                .get("holdId")
                .asText());
        seatHoldService.expire(expiredId);
        mockMvc.perform(delete("/api/v1/holds/{holdId}", expiredId).with(anonymous()))
                .andExpect(status().isConflict());

        UUID consumedId = UUID.fromString(objectMapper
                .readTree(createHoldViaApi(
                                trip,
                                trip.stopId(1),
                                trip.stopId(3),
                                List.of(trip.availableSeatIds().get(2)))
                        .getResponse()
                        .getContentAsString())
                .get("holdId")
                .asText());
        seatHoldService.consume(consumedId);
        mockMvc.perform(delete("/api/v1/holds/{holdId}", consumedId).with(anonymous()))
                .andExpect(status().isConflict());

        mockMvc.perform(delete("/api/v1/holds/{holdId}", UUID.randomUUID()).with(anonymous()))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser
    void concurrentOverlappingHoldsAllowExactlyOneSuccess() throws Exception {
        TripFixture trip = createTrip("HOLD-API-08", "HOLD-API-RT-08");
        UUID tripId = trip.tripId();
        UUID seat = trip.availableSeatIds().get(0);
        String body = holdBody(trip.stopId(1), trip.stopId(3), List.of(seat), null);

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
                    MvcResult result = mockMvc.perform(post("/api/v1/trips/{tripId}/holds", tripId)
                                    .with(anonymous())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body))
                            .andReturn();
                    if (result.getResponse().getStatus() == 201) {
                        successes.incrementAndGet();
                    } else if (result.getResponse().getStatus() == 409) {
                        conflicts.incrementAndGet();
                    }
                    return null;
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(successes.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(1);
        assertThat(seatHoldRepository.findByTripIdAndStatusOrderByCreatedAtDesc(tripId, SeatHoldStatus.ACTIVE))
                .hasSize(1);
    }

    @Test
    @WithMockUser
    void concurrentMultiSeatOverlapsHaveNoPartialSuccess() throws Exception {
        TripFixture trip = createTrip("HOLD-API-09", "HOLD-API-RT-09");
        UUID tripId = trip.tripId();
        List<UUID> seats = List.of(trip.availableSeatIds().get(0), trip.availableSeatIds().get(1));
        String body = holdBody(trip.stopId(1), trip.stopId(3), seats, null);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    MvcResult result = mockMvc.perform(post("/api/v1/trips/{tripId}/holds", tripId)
                                    .with(anonymous())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body))
                            .andReturn();
                    if (result.getResponse().getStatus() == 201) {
                        successes.incrementAndGet();
                    }
                    return null;
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(successes.get()).isEqualTo(1);
        assertThat(seatHoldRepository.findByTripIdAndStatusOrderByCreatedAtDesc(tripId, SeatHoldStatus.ACTIVE))
                .hasSize(1);
        Long heldCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM trip_seat_allocations a
                JOIN seat_holds h ON h.id = a.hold_id
                WHERE h.trip_id = ? AND a.state = 'HELD' AND h.status = 'ACTIVE'
                """,
                Long.class,
                tripId);
        assertThat(heldCount).isEqualTo(2L);
    }

    @Test
    @WithMockUser
    void availabilityAndExpiryRegressionThroughHoldApi() throws Exception {
        TripFixture trip = createTrip("HOLD-API-10", "HOLD-API-RT-10");
        UUID seat = trip.availableSeatIds().get(0);

        assertThat(availabilityOf(trip, seat)).isEqualTo(JourneySeatAvailability.AVAILABLE.name());

        UUID holdId = UUID.fromString(objectMapper
                .readTree(createHoldViaApi(trip, trip.stopId(1), trip.stopId(3), List.of(seat))
                        .getResponse()
                        .getContentAsString())
                .get("holdId")
                .asText());
        assertThat(availabilityOf(trip, seat)).isEqualTo(JourneySeatAvailability.UNAVAILABLE.name());

        mockMvc.perform(delete("/api/v1/holds/{holdId}", holdId).with(anonymous()))
                .andExpect(status().isNoContent());
        assertThat(availabilityOf(trip, seat)).isEqualTo(JourneySeatAvailability.AVAILABLE.name());

        UUID secondHoldId = UUID.fromString(objectMapper
                .readTree(createHoldViaApi(trip, trip.stopId(1), trip.stopId(3), List.of(seat))
                        .getResponse()
                        .getContentAsString())
                .get("holdId")
                .asText());
        jdbcTemplate.update(
                "UPDATE seat_holds SET expires_at = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(30)),
                secondHoldId);
        jdbcTemplate.update(
                "UPDATE trip_seat_allocations SET expires_at = ? WHERE hold_id = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(30)),
                secondHoldId);
        assertThat(seatHoldExpiryService.expireDueHolds(Instant.now()).holdsExpired()).isGreaterThanOrEqualTo(1);
        assertThat(seatHoldRepository.findById(secondHoldId).orElseThrow().getStatus())
                .isEqualTo(SeatHoldStatus.EXPIRED);
        assertThat(availabilityOf(trip, seat)).isEqualTo(JourneySeatAvailability.AVAILABLE.name());

        // Phase 7.5 availability still works
        mockMvc.perform(get("/api/v1/trips/{tripId}/seat-availability", trip.tripId())
                        .with(anonymous())
                        .param("originStopId", trip.stopId(1).toString())
                        .param("destinationStopId", trip.stopId(3).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seats.length()").value(4));
    }

    @Test
    @WithMockUser
    void holdApisArePublicAndAdminRemainsProtected() throws Exception {
        TripFixture trip = createTrip("HOLD-API-11", "HOLD-API-RT-11");

        mockMvc.perform(get("/api/v1/trips/{tripId}/seat-availability", trip.tripId())
                        .with(anonymous())
                        .param("originStopId", trip.stopId(1).toString())
                        .param("destinationStopId", trip.stopId(3).toString()))
                .andExpect(status().isOk());

        MvcResult created = mockMvc.perform(post("/api/v1/trips/{tripId}/holds", trip.tripId())
                        .with(anonymous())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(holdBody(
                                trip.stopId(1),
                                trip.stopId(3),
                                List.of(trip.availableSeatIds().get(0)),
                                null)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID holdId = UUID.fromString(
                objectMapper.readTree(created.getResponse().getContentAsString()).get("holdId").asText());

        mockMvc.perform(get("/api/v1/holds/{holdId}", holdId).with(anonymous()))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/holds/{holdId}", holdId).with(anonymous()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/admin/trips/{id}", trip.tripId()).with(anonymous()))
                .andExpect(status().isUnauthorized());
    }

    private String availabilityOf(TripFixture trip, UUID inventoryId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/trips/{tripId}/seat-availability", trip.tripId())
                        .param("originStopId", trip.stopId(1).toString())
                        .param("destinationStopId", trip.stopId(3).toString()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        for (JsonNode seat : body.get("seats")) {
            if (inventoryId.toString().equals(seat.get("inventoryId").asText())) {
                return seat.get("availability").asText();
            }
        }
        throw new AssertionError("Seat not found: " + inventoryId);
    }

    private MvcResult createHoldViaApi(
            TripFixture trip, UUID originStopId, UUID destinationStopId, List<UUID> seats) throws Exception {
        return createHoldViaApi(trip, originStopId, destinationStopId, seats, null);
    }

    private MvcResult createHoldViaApi(
            TripFixture trip,
            UUID originStopId,
            UUID destinationStopId,
            List<UUID> seats,
            String idempotencyKey) throws Exception {
        return mockMvc.perform(post("/api/v1/trips/{tripId}/holds", trip.tripId())
                        .with(anonymous())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(holdBody(originStopId, destinationStopId, seats, idempotencyKey)))
                .andExpect(status().isCreated())
                .andReturn();
    }

    private static String holdBody(
            UUID originStopId, UUID destinationStopId, List<UUID> seats, String idempotencyKey) {
        StringBuilder seatJson = new StringBuilder("[");
        for (int i = 0; i < seats.size(); i++) {
            if (i > 0) {
                seatJson.append(',');
            }
            seatJson.append('"').append(seats.get(i)).append('"');
        }
        seatJson.append(']');
        if (idempotencyKey == null) {
            return """
                    {
                      "originStopId":"%s",
                      "destinationStopId":"%s",
                      "seatInventoryIds":%s
                    }
                    """.formatted(originStopId, destinationStopId, seatJson);
        }
        return """
                {
                  "originStopId":"%s",
                  "destinationStopId":"%s",
                  "seatInventoryIds":%s,
                  "idempotencyKey":"%s"
                }
                """.formatted(originStopId, destinationStopId, seatJson, idempotencyKey);
    }

    private long count(String table) {
        Long value = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return value == null ? 0L : value;
    }

    private TripFixture createTrip(String registration, String routeCode) throws Exception {
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
