package in.bluebustickets.bluebus.scheduling.api.operator;

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
import in.bluebustickets.bluebus.foundation.security.TestAccessTokenFactory.IssuedOperatorMember;
import in.bluebustickets.bluebus.foundation.security.TestAccessTokenFactory.IssuedUser;
import in.bluebustickets.bluebus.identity.domain.RoleCode;
import in.bluebustickets.bluebus.identity.domain.UserStatus;
import in.bluebustickets.bluebus.operator.domain.Operator;
import in.bluebustickets.bluebus.operator.repository.OperatorRepository;
import in.bluebustickets.bluebus.operator.repository.OperatorUserRepository;
import in.bluebustickets.bluebus.scheduling.application.OperatorTripInventoryAdminService;
import in.bluebustickets.bluebus.scheduling.application.SeatHoldExpiryService;
import in.bluebustickets.bluebus.scheduling.domain.SeatHoldStatus;
import in.bluebustickets.bluebus.scheduling.domain.TripSeatAllocationState;
import in.bluebustickets.bluebus.scheduling.domain.TripSeatInventoryStatus;
import in.bluebustickets.bluebus.scheduling.repository.SeatHoldRepository;
import in.bluebustickets.bluebus.scheduling.repository.TripSeatAllocationRepository;
import in.bluebustickets.bluebus.scheduling.repository.TripSeatInventoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static in.bluebustickets.bluebus.foundation.security.TestAccessTokenFactory.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OperatorTripInventoryAdminPostgresIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("blue-bus.outbox.processor.enabled", () -> "false");
        registry.add("blue-bus.bookings.expiry.enabled", () -> "false");
        registry.add("blue-bus.seat-holds.expiry.enabled", () -> "false");
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TestAccessTokenFactory tokens;
    @Autowired private OperatorTripInventoryAdminService inventoryAdminService;
    @Autowired private OperatorUserRepository operatorUserRepository;
    @Autowired private OperatorRepository operatorRepository;
    @Autowired private TripSeatInventoryRepository tripSeatInventoryRepository;
    @Autowired private TripSeatAllocationRepository allocationRepository;
    @Autowired private SeatHoldRepository seatHoldRepository;
    @Autowired private SeatHoldExpiryService seatHoldExpiryService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private String platformAdminToken;

    @BeforeEach
    void seedPlatformAdmin() {
        platformAdminToken = tokens.issuePlatformAdmin().accessToken();
    }

    @Test
    void adminCanReadWriteAndStaffIsReadOnly() throws Exception {
        IssuedOperatorMember admin = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_ADMIN, List.of("OPERATOR_ADMIN"));
        IssuedUser staffUser = tokens.issueCustomer();
        tokens.attachMembership(admin.operator(), staffUser.user(), RoleCode.OPERATOR_STAFF);
        IssuedUser staffToken = tokens.issueToken(staffUser.user(), List.of("CUSTOMER"));

        UUID operatorId = admin.operator().getId();
        TripContext trip = createScheduledTrip(admin, "2028-01-01T10:00:00Z", "2028-01-01T18:00:00Z");
        UUID inventoryId = trip.seatIds().get(0);

        mockMvc.perform(get("/api/v1/operator/{operatorId}/trips/{tripId}/inventory",
                        operatorId, trip.tripId())
                        .with(bearer(staffToken.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        mockMvc.perform(get("/api/v1/operator/{operatorId}/trips/{tripId}/inventory/{inventoryId}",
                        operatorId, trip.tripId(), inventoryId)
                        .with(bearer(staffToken.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.physicalStatus").value("AVAILABLE"));

        mockMvc.perform(post("/api/v1/operator/{operatorId}/trips/{tripId}/inventory/{inventoryId}/block",
                        operatorId, trip.tripId(), inventoryId)
                        .with(bearer(staffToken.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Staff blocked\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/operator/{operatorId}/trips/{tripId}/inventory/{inventoryId}/block",
                        operatorId, trip.tripId(), inventoryId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Maintenance\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.physicalStatus").value("BLOCKED"))
                .andExpect(jsonPath("$.blockReason").value("Maintenance"));
    }

    @Test
    void authorizationBoundariesAreEnforced() throws Exception {
        IssuedOperatorMember admin = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_ADMIN, List.of("OPERATOR_ADMIN"));
        IssuedOperatorMember other = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_ADMIN, List.of("OPERATOR_ADMIN"));
        IssuedUser inactive = tokens.issuePlatformUser(
                RoleCode.CUSTOMER, List.of("CUSTOMER"), UserStatus.INACTIVE);
        IssuedUser platformAdmin = tokens.issuePlatformAdmin();

        UUID operatorId = admin.operator().getId();
        TripContext trip = createScheduledTrip(admin, "2028-01-02T10:00:00Z", "2028-01-02T18:00:00Z");
        TripContext otherTrip = createScheduledTrip(other, "2028-01-02T10:00:00Z", "2028-01-02T18:00:00Z");
        UUID inventoryId = trip.seatIds().get(0);
        UUID foreignInventoryId = otherTrip.seatIds().get(0);

        mockMvc.perform(get("/api/v1/operator/{operatorId}/trips/{tripId}/inventory",
                        operatorId, trip.tripId()).with(anonymous()))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/operator/{operatorId}/trips/{tripId}/inventory/{inventoryId}/block",
                        operatorId, trip.tripId(), inventoryId)
                        .with(bearer(inactive.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"x\"}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/operator/{operatorId}/trips/{tripId}/inventory",
                        other.operator().getId(), trip.tripId())
                        .with(bearer(admin.accessToken())))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/operator/{operatorId}/trips/{tripId}/inventory/{inventoryId}",
                        operatorId, trip.tripId(), foreignInventoryId)
                        .with(bearer(admin.accessToken())))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/v1/operator/{operatorId}/trips/{tripId}/inventory/{inventoryId}/block",
                        operatorId, otherTrip.tripId(), inventoryId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"x\"}"))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/v1/operator/{operatorId}/trips/{tripId}/inventory/{inventoryId}/block",
                        operatorId, trip.tripId(), inventoryId)
                        .with(bearer(platformAdmin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"x\"}"))
                .andExpect(status().isNotFound());

        Operator inactiveOperator = tokens.persistActiveOperator();
        IssuedUser inactiveOpAdminUser = tokens.issueCustomer();
        tokens.attachMembership(inactiveOperator, inactiveOpAdminUser.user(), RoleCode.OPERATOR_ADMIN);
        IssuedUser inactiveOpToken = tokens.issueToken(inactiveOpAdminUser.user(), List.of("CUSTOMER"));
        inactiveOperator.deactivate();
        operatorRepository.saveAndFlush(inactiveOperator);

        mockMvc.perform(get("/api/v1/operator/{operatorId}/trips/{tripId}/inventory",
                        inactiveOperator.getId(), trip.tripId())
                        .with(bearer(inactiveOpToken.accessToken())))
                .andExpect(status().isForbidden());

        var membership = operatorUserRepository
                .findByOperatorIdAndUserId(operatorId, admin.user().getId())
                .orElseThrow();
        membership.deactivate();
        operatorUserRepository.saveAndFlush(membership);

        mockMvc.perform(post("/api/v1/operator/{operatorId}/trips/{tripId}/inventory/{inventoryId}/block",
                        operatorId, trip.tripId(), inventoryId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"x\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void inventoryReadsExposePhysicalStateWithoutCustomerPii() throws Exception {
        IssuedOperatorMember admin = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_ADMIN, List.of("OPERATOR_ADMIN"));
        TripContext trip = createScheduledTrip(admin, "2028-01-03T10:00:00Z", "2028-01-03T18:00:00Z");
        UUID inventoryId = trip.seatIds().get(0);

        JsonNode list = objectMapper.readTree(mockMvc.perform(
                        get("/api/v1/operator/{operatorId}/trips/{tripId}/inventory",
                                admin.operator().getId(), trip.tripId())
                                .with(bearer(admin.accessToken())))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(list).hasSize(2);
        JsonNode seat = list.get(0);
        assertThat(seat.has("id")).isTrue();
        assertThat(seat.has("tripId")).isTrue();
        assertThat(seat.has("seatNumber")).isTrue();
        assertThat(seat.has("seatType")).isTrue();
        assertThat(seat.has("deckNumber")).isTrue();
        assertThat(seat.has("rowNumber")).isTrue();
        assertThat(seat.has("columnNumber")).isTrue();
        assertThat(seat.has("physicalStatus")).isTrue();
        assertThat(seat.has("email")).isFalse();
        assertThat(seat.has("phone")).isFalse();
        assertThat(seat.has("payment")).isFalse();
        assertThat(seat.has("customer")).isFalse();

        mockMvc.perform(get("/api/v1/operator/{operatorId}/trips/{tripId}/inventory/{inventoryId}",
                        admin.operator().getId(), trip.tripId(), inventoryId)
                        .with(bearer(admin.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.physicalStatus").value("AVAILABLE"))
                .andExpect(jsonPath("$.email").doesNotExist());
    }

    @Test
    void blockAndUnblockAreIdempotentAndRequireReason() throws Exception {
        IssuedOperatorMember admin = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_ADMIN, List.of("OPERATOR_ADMIN"));
        UUID operatorId = admin.operator().getId();
        TripContext trip = createScheduledTrip(admin, "2028-01-04T10:00:00Z", "2028-01-04T18:00:00Z");
        UUID inventoryId = trip.seatIds().get(0);

        mockMvc.perform(post("/api/v1/operator/{operatorId}/trips/{tripId}/inventory/{inventoryId}/block",
                        operatorId, trip.tripId(), inventoryId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/operator/{operatorId}/trips/{tripId}/inventory/{inventoryId}/block",
                        operatorId, trip.tripId(), inventoryId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"  \"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/operator/{operatorId}/trips/{tripId}/inventory/{inventoryId}/block",
                        operatorId, trip.tripId(), inventoryId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Broken recliner\",\"operatorId\":\"%s\"}"
                                .formatted(operatorId)))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/operator/{operatorId}/trips/{tripId}/inventory/{inventoryId}/block",
                        operatorId, trip.tripId(), inventoryId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Broken recliner\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.physicalStatus").value("BLOCKED"))
                .andExpect(jsonPath("$.blockReason").value("Broken recliner"));

        mockMvc.perform(post("/api/v1/operator/{operatorId}/trips/{tripId}/inventory/{inventoryId}/block",
                        operatorId, trip.tripId(), inventoryId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Different reason\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.physicalStatus").value("BLOCKED"))
                .andExpect(jsonPath("$.blockReason").value("Broken recliner"));

        mockMvc.perform(post("/api/v1/operator/{operatorId}/trips/{tripId}/inventory/{inventoryId}/unblock",
                        operatorId, trip.tripId(), inventoryId)
                        .with(bearer(admin.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.physicalStatus").value("AVAILABLE"))
                .andExpect(jsonPath("$.blockReason").value(org.hamcrest.Matchers.nullValue()));

        mockMvc.perform(post("/api/v1/operator/{operatorId}/trips/{tripId}/inventory/{inventoryId}/unblock",
                        operatorId, trip.tripId(), inventoryId)
                        .with(bearer(admin.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.physicalStatus").value("AVAILABLE"));
    }

    @Test
    void blockPreservesActiveHoldAndBookingAndRejectsNewHolds() throws Exception {
        IssuedOperatorMember admin = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_ADMIN, List.of("OPERATOR_ADMIN"));
        IssuedUser customer = tokens.issueCustomer();
        UUID operatorId = admin.operator().getId();
        TripContext trip = createScheduledTrip(admin, "2028-01-05T10:00:00Z", "2028-01-05T18:00:00Z");
        UUID heldSeat = trip.seatIds().get(0);
        UUID bookedSeat = trip.seatIds().get(1);

        JsonNode hold = createHold(trip, List.of(heldSeat), customer.accessToken());
        UUID holdId = UUID.fromString(hold.get("holdId").asText());

        JsonNode bookingHold = createHold(trip, List.of(bookedSeat), customer.accessToken());
        mockMvc.perform(post("/api/v1/bookings")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customer.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingBody(
                                UUID.fromString(bookingHold.get("holdId").asText()),
                                trip.stopId(1),
                                trip.stopId(3),
                                "inv-book-" + shortId(),
                                List.of(passenger(bookedSeat, "Booked Rider", 30)))))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/operator/{operatorId}/trips/{tripId}/inventory/{inventoryId}/block",
                        operatorId, trip.tripId(), heldSeat)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Held seat block\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.physicalStatus").value("BLOCKED"));

        mockMvc.perform(post("/api/v1/operator/{operatorId}/trips/{tripId}/inventory/{inventoryId}/block",
                        operatorId, trip.tripId(), bookedSeat)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Booked seat block\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.physicalStatus").value("BLOCKED"));

        assertThat(seatHoldRepository.findById(holdId).orElseThrow().getStatus())
                .isEqualTo(SeatHoldStatus.ACTIVE);
        assertThat(allocationRepository.findByHoldIdOrderByCreatedAtAsc(holdId))
                .allMatch(a -> a.getState() == TripSeatAllocationState.HELD);
        assertThat(allocationRepository.findByHoldIdOrderByCreatedAtAsc(
                        UUID.fromString(bookingHold.get("holdId").asText())))
                .allMatch(a -> a.getState() == TripSeatAllocationState.BOOKED);

        mockMvc.perform(post("/api/v1/trips/{tripId}/holds", trip.tripId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customer.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(holdBody(trip.stopId(1), trip.stopId(3), List.of(heldSeat))))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/bookings")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customer.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingBody(
                                holdId,
                                trip.stopId(1),
                                trip.stopId(3),
                                "inv-convert-" + shortId(),
                                List.of(passenger(heldSeat, "Held Rider", 28)))))
                .andExpect(status().isCreated());
        assertThat(allocationRepository.findByHoldIdOrderByCreatedAtAsc(holdId))
                .allMatch(a -> a.getState() == TripSeatAllocationState.BOOKED);
        assertThat(tripSeatInventoryRepository.findById(heldSeat).orElseThrow().getPhysicalStatus())
                .isEqualTo(TripSeatInventoryStatus.BLOCKED);
    }

    @Test
    void blockAllowedAfterReleasedExpiredOrCancelledAllocationsAndUnblockRestoresHolds()
            throws Exception {
        IssuedOperatorMember admin = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_ADMIN, List.of("OPERATOR_ADMIN"));
        IssuedUser customer = tokens.issueCustomer();
        UUID operatorId = admin.operator().getId();
        TripContext trip = createScheduledTrip(admin, "2028-01-06T10:00:00Z", "2028-01-06T18:00:00Z");
        UUID seat = trip.seatIds().get(0);

        JsonNode cancelledHold = createHold(trip, List.of(seat), customer.accessToken());
        mockMvc.perform(delete("/api/v1/holds/{holdId}", cancelledHold.get("holdId").asText()))
                .andExpect(status().isNoContent());
        assertThat(allocationRepository.findByHoldIdOrderByCreatedAtAsc(
                        UUID.fromString(cancelledHold.get("holdId").asText())))
                .allMatch(a -> a.getState() == TripSeatAllocationState.CANCELLED);

        mockMvc.perform(post("/api/v1/operator/{operatorId}/trips/{tripId}/inventory/{inventoryId}/block",
                        operatorId, trip.tripId(), seat)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"After cancel\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/operator/{operatorId}/trips/{tripId}/inventory/{inventoryId}/unblock",
                        operatorId, trip.tripId(), seat)
                        .with(bearer(admin.accessToken())))
                .andExpect(status().isOk());

        JsonNode expiredHold = createHold(trip, List.of(seat), customer.accessToken());
        UUID expiredHoldId = UUID.fromString(expiredHold.get("holdId").asText());
        java.sql.Timestamp past = java.sql.Timestamp.from(Instant.now().minusSeconds(60));
        jdbcTemplate.update(
                "UPDATE seat_holds SET expires_at = ? WHERE id = ?",
                past,
                expiredHoldId);
        jdbcTemplate.update(
                "UPDATE trip_seat_allocations SET expires_at = ? WHERE hold_id = ?",
                past,
                expiredHoldId);
        seatHoldExpiryService.expireDueHolds(Instant.now());
        assertThat(seatHoldRepository.findById(expiredHoldId).orElseThrow().getStatus())
                .isEqualTo(SeatHoldStatus.EXPIRED);
        assertThat(allocationRepository.findByHoldIdOrderByCreatedAtAsc(expiredHoldId))
                .allMatch(a -> a.getState() == TripSeatAllocationState.EXPIRED);

        mockMvc.perform(post("/api/v1/operator/{operatorId}/trips/{tripId}/inventory/{inventoryId}/block",
                        operatorId, trip.tripId(), seat)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"After expire\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/operator/{operatorId}/trips/{tripId}/inventory/{inventoryId}/unblock",
                        operatorId, trip.tripId(), seat)
                        .with(bearer(admin.accessToken())))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/trips/{tripId}/holds", trip.tripId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customer.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(holdBody(trip.stopId(1), trip.stopId(3), List.of(seat))))
                .andExpect(status().isCreated());
    }

    @Test
    void invalidTripStatesRejectInventoryMutation() throws Exception {
        IssuedOperatorMember admin = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_ADMIN, List.of("OPERATOR_ADMIN"));
        UUID operatorId = admin.operator().getId();
        TripContext trip = createScheduledTrip(admin, "2028-01-07T10:00:00Z", "2028-01-07T18:00:00Z");
        UUID inventoryId = trip.seatIds().get(0);

        for (String status : List.of("CLOSED", "DEPARTED", "COMPLETED", "CANCELLED")) {
            jdbcTemplate.update("UPDATE trips SET status = ? WHERE id = ?", status, trip.tripId());
            mockMvc.perform(post("/api/v1/operator/{operatorId}/trips/{tripId}/inventory/{inventoryId}/block",
                            operatorId, trip.tripId(), inventoryId)
                            .with(bearer(admin.accessToken()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"late\"}"))
                    .andExpect(status().isBadRequest());
            mockMvc.perform(post("/api/v1/operator/{operatorId}/trips/{tripId}/inventory/{inventoryId}/unblock",
                            operatorId, trip.tripId(), inventoryId)
                            .with(bearer(admin.accessToken())))
                    .andExpect(status().isBadRequest());
        }

        jdbcTemplate.update("UPDATE trips SET status = ? WHERE id = ?", "ON_SALE", trip.tripId());
        mockMvc.perform(post("/api/v1/operator/{operatorId}/trips/{tripId}/inventory/{inventoryId}/block",
                        operatorId, trip.tripId(), inventoryId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"on sale ok\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.physicalStatus").value("BLOCKED"));
    }

    @Test
    void blockedSeatStaysUnavailableForAllSegmentsWhileNonOverlapRemainsValid() throws Exception {
        IssuedOperatorMember admin = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_ADMIN, List.of("OPERATOR_ADMIN"));
        IssuedUser customer = tokens.issueCustomer();
        UUID operatorId = admin.operator().getId();
        TripContext trip = createScheduledTrip(admin, "2028-01-08T10:00:00Z", "2028-01-08T18:00:00Z");
        UUID seatA = trip.seatIds().get(0);
        UUID seatB = trip.seatIds().get(1);

        createHold(trip, List.of(seatA), customer.accessToken(), trip.stopId(1), trip.stopId(2));

        mockMvc.perform(post("/api/v1/operator/{operatorId}/trips/{tripId}/inventory/{inventoryId}/block",
                        operatorId, trip.tripId(), seatB)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Global block\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/trips/{tripId}/holds", trip.tripId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customer.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(holdBody(trip.stopId(2), trip.stopId(3), List.of(seatB))))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/trips/{tripId}/holds", trip.tripId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customer.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(holdBody(trip.stopId(2), trip.stopId(3), List.of(seatA))))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/trips/{tripId}/seat-availability", trip.tripId())
                        .param("originStopId", trip.stopId(1).toString())
                        .param("destinationStopId", trip.stopId(3).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seats[?(@.inventoryId=='%s')].physicalStatus".formatted(seatB))
                        .value(org.hamcrest.Matchers.hasItem("BLOCKED")))
                .andExpect(jsonPath("$.seats[?(@.inventoryId=='%s')].availability".formatted(seatB))
                        .value(org.hamcrest.Matchers.hasItem("UNAVAILABLE")));
    }

    @Test
    void blockingDoesNotRewriteSeatGeometryOrLayoutSnapshot() throws Exception {
        IssuedOperatorMember admin = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_ADMIN, List.of("OPERATOR_ADMIN"));
        UUID operatorId = admin.operator().getId();
        TripContext trip = createScheduledTrip(admin, "2028-01-09T10:00:00Z", "2028-01-09T18:00:00Z");
        UUID inventoryId = trip.seatIds().get(0);

        JsonNode before = objectMapper.readTree(mockMvc.perform(
                        get("/api/v1/operator/{operatorId}/trips/{tripId}/inventory/{inventoryId}",
                                operatorId, trip.tripId(), inventoryId)
                                .with(bearer(admin.accessToken())))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());

        mockMvc.perform(post("/api/v1/operator/{operatorId}/trips/{tripId}/inventory/{inventoryId}/block",
                        operatorId, trip.tripId(), inventoryId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"geometry check\"}"))
                .andExpect(status().isOk());

        JsonNode after = objectMapper.readTree(mockMvc.perform(
                        get("/api/v1/operator/{operatorId}/trips/{tripId}/inventory/{inventoryId}",
                                operatorId, trip.tripId(), inventoryId)
                                .with(bearer(admin.accessToken())))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());

        assertThat(after.get("layoutSeatId").asText()).isEqualTo(before.get("layoutSeatId").asText());
        assertThat(after.get("seatLayoutId").asText()).isEqualTo(before.get("seatLayoutId").asText());
        assertThat(after.get("seatLayoutVersion").asInt()).isEqualTo(before.get("seatLayoutVersion").asInt());
        assertThat(after.get("seatNumber").asText()).isEqualTo(before.get("seatNumber").asText());
        assertThat(after.get("deckNumber").asInt()).isEqualTo(before.get("deckNumber").asInt());
        assertThat(after.get("rowNumber").asInt()).isEqualTo(before.get("rowNumber").asInt());
        assertThat(after.get("columnNumber").asInt()).isEqualTo(before.get("columnNumber").asInt());
        assertThat(after.get("physicalStatus").asText()).isEqualTo("BLOCKED");
    }

    @Test
    void concurrentBlocksSerializeSafely() throws Exception {
        IssuedOperatorMember admin = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_ADMIN, List.of("OPERATOR_ADMIN"));
        UUID operatorId = admin.operator().getId();
        TripContext trip = createScheduledTrip(admin, "2028-01-10T10:00:00Z", "2028-01-10T18:00:00Z");
        UUID inventoryId = trip.seatIds().get(0);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (String reason : List.of("A", "B")) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    int statusCode = mockMvc.perform(post(
                                    "/api/v1/operator/{operatorId}/trips/{tripId}/inventory/{inventoryId}/block",
                                    operatorId, trip.tripId(), inventoryId)
                                    .with(bearer(admin.accessToken()))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"reason\":\"%s\"}".formatted(reason)))
                            .andReturn()
                            .getResponse()
                            .getStatus();
                    if (statusCode == 200) {
                        ok.incrementAndGet();
                    }
                    return null;
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> future : futures) {
                future.get(45, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }
        assertThat(ok.get()).isEqualTo(2);
        assertThat(tripSeatInventoryRepository.findById(inventoryId).orElseThrow().getPhysicalStatus())
                .isEqualTo(TripSeatInventoryStatus.BLOCKED);
        assertThat(tripSeatInventoryRepository.findById(inventoryId).orElseThrow().getBlockReason())
                .isIn("A", "B");
    }

    @Test
    void blockVersusHoldCreationRaceIsSafe() throws Exception {
        IssuedOperatorMember admin = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_ADMIN, List.of("OPERATOR_ADMIN"));
        IssuedUser customer = tokens.issueCustomer();
        UUID operatorId = admin.operator().getId();
        TripContext trip = createScheduledTrip(admin, "2028-01-11T10:00:00Z", "2028-01-11T18:00:00Z");
        UUID inventoryId = trip.seatIds().get(0);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger blockStatus = new AtomicInteger();
        AtomicInteger holdStatus = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> blockFuture = executor.submit(() -> {
                ready.countDown();
                start.await();
                blockStatus.set(mockMvc.perform(post(
                                "/api/v1/operator/{operatorId}/trips/{tripId}/inventory/{inventoryId}/block",
                                operatorId, trip.tripId(), inventoryId)
                                .with(bearer(admin.accessToken()))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"race block\"}"))
                        .andReturn()
                        .getResponse()
                        .getStatus());
                return null;
            });
            Future<?> holdFuture = executor.submit(() -> {
                ready.countDown();
                start.await();
                holdStatus.set(mockMvc.perform(post("/api/v1/trips/{tripId}/holds", trip.tripId())
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + customer.accessToken())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(holdBody(trip.stopId(1), trip.stopId(3), List.of(inventoryId))))
                        .andReturn()
                        .getResponse()
                        .getStatus());
                return null;
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            blockFuture.get(45, TimeUnit.SECONDS);
            holdFuture.get(45, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(blockStatus.get()).isEqualTo(200);
        assertThat(tripSeatInventoryRepository.findById(inventoryId).orElseThrow().getPhysicalStatus())
                .isEqualTo(TripSeatInventoryStatus.BLOCKED);
        assertThat(holdStatus.get()).isIn(201, 400);
        if (holdStatus.get() == 201) {
            assertThat(allocationRepository.findAll().stream()
                            .filter(a -> a.getInventory().getId().equals(inventoryId)
                                    && a.getState() == TripSeatAllocationState.HELD)
                            .count())
                    .isEqualTo(1);
        }
    }

    @Test
    void blockVersusBookingConversionRaceIsSafe() throws Exception {
        IssuedOperatorMember admin = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_ADMIN, List.of("OPERATOR_ADMIN"));
        IssuedUser customer = tokens.issueCustomer();
        UUID operatorId = admin.operator().getId();
        TripContext trip = createScheduledTrip(admin, "2028-01-12T10:00:00Z", "2028-01-12T18:00:00Z");
        UUID inventoryId = trip.seatIds().get(0);
        JsonNode hold = createHold(trip, List.of(inventoryId), customer.accessToken());
        UUID holdId = UUID.fromString(hold.get("holdId").asText());

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger blockStatus = new AtomicInteger();
        AtomicInteger bookStatus = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> blockFuture = executor.submit(() -> {
                ready.countDown();
                start.await();
                blockStatus.set(mockMvc.perform(post(
                                "/api/v1/operator/{operatorId}/trips/{tripId}/inventory/{inventoryId}/block",
                                operatorId, trip.tripId(), inventoryId)
                                .with(bearer(admin.accessToken()))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"vs book\"}"))
                        .andReturn()
                        .getResponse()
                        .getStatus());
                return null;
            });
            Future<?> bookFuture = executor.submit(() -> {
                ready.countDown();
                start.await();
                bookStatus.set(mockMvc.perform(post("/api/v1/bookings")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + customer.accessToken())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(bookingBody(
                                        holdId,
                                        trip.stopId(1),
                                        trip.stopId(3),
                                        "race-book-" + shortId(),
                                        List.of(passenger(inventoryId, "Race Rider", 31)))))
                        .andReturn()
                        .getResponse()
                        .getStatus());
                return null;
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            blockFuture.get(45, TimeUnit.SECONDS);
            bookFuture.get(45, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(blockStatus.get()).isEqualTo(200);
        assertThat(bookStatus.get()).isEqualTo(201);
        assertThat(tripSeatInventoryRepository.findById(inventoryId).orElseThrow().getPhysicalStatus())
                .isEqualTo(TripSeatInventoryStatus.BLOCKED);
        assertThat(allocationRepository.findByHoldIdOrderByCreatedAtAsc(holdId))
                .allMatch(a -> a.getState() == TripSeatAllocationState.BOOKED);
    }

    @Test
    void blockVersusHoldExpiryRaceIsSafe() throws Exception {
        IssuedOperatorMember admin = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_ADMIN, List.of("OPERATOR_ADMIN"));
        IssuedUser customer = tokens.issueCustomer();
        UUID operatorId = admin.operator().getId();
        TripContext trip = createScheduledTrip(admin, "2028-01-13T10:00:00Z", "2028-01-13T18:00:00Z");
        UUID inventoryId = trip.seatIds().get(0);
        JsonNode hold = createHold(trip, List.of(inventoryId), customer.accessToken());
        UUID holdId = UUID.fromString(hold.get("holdId").asText());
        java.sql.Timestamp past = java.sql.Timestamp.from(Instant.now().minusSeconds(30));
        jdbcTemplate.update("UPDATE seat_holds SET expires_at = ? WHERE id = ?", past, holdId);
        jdbcTemplate.update(
                "UPDATE trip_seat_allocations SET expires_at = ? WHERE hold_id = ?", past, holdId);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger blockStatus = new AtomicInteger();
        AtomicReference<String> expiryError = new AtomicReference<>();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> blockFuture = executor.submit(() -> {
                ready.countDown();
                start.await();
                blockStatus.set(mockMvc.perform(post(
                                "/api/v1/operator/{operatorId}/trips/{tripId}/inventory/{inventoryId}/block",
                                operatorId, trip.tripId(), inventoryId)
                                .with(bearer(admin.accessToken()))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"vs expiry\"}"))
                        .andReturn()
                        .getResponse()
                        .getStatus());
                return null;
            });
            Future<?> expiryFuture = executor.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    seatHoldExpiryService.expireDueHolds(Instant.now());
                } catch (Exception exception) {
                    expiryError.set(exception.getClass().getSimpleName());
                }
                return null;
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            blockFuture.get(45, TimeUnit.SECONDS);
            expiryFuture.get(45, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(expiryError.get()).isNull();
        assertThat(blockStatus.get()).isEqualTo(200);
        assertThat(tripSeatInventoryRepository.findById(inventoryId).orElseThrow().getPhysicalStatus())
                .isEqualTo(TripSeatInventoryStatus.BLOCKED);
        assertThat(seatHoldRepository.findById(holdId).orElseThrow().getStatus())
                .isEqualTo(SeatHoldStatus.EXPIRED);
        assertThat(allocationRepository.findByHoldIdOrderByCreatedAtAsc(holdId))
                .allMatch(a -> a.getState() == TripSeatAllocationState.EXPIRED);
    }

    @Test
    void concurrentDemotionInvalidatesStaleInventoryMutation() throws Exception {
        IssuedOperatorMember adminA = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_ADMIN, List.of("OPERATOR_ADMIN"));
        UUID operatorId = adminA.operator().getId();
        IssuedUser adminBUser = tokens.issueCustomer();
        tokens.attachMembership(adminA.operator(), adminBUser.user(), RoleCode.OPERATOR_ADMIN);
        IssuedUser adminBToken = tokens.issueToken(adminBUser.user(), List.of("CUSTOMER"));

        TripContext trip = createScheduledTrip(adminA, "2028-01-14T10:00:00Z", "2028-01-14T18:00:00Z");
        UUID inventoryId = trip.seatIds().get(0);

        CountDownLatch authorized = new CountDownLatch(1);
        CountDownLatch demoted = new CountDownLatch(1);
        AtomicInteger barrierHits = new AtomicInteger();
        ReflectionTestUtils.setField(
                inventoryAdminService,
                "afterAuthorizeBeforeLockForTests",
                (Runnable) () -> {
                    if (barrierHits.getAndIncrement() != 0) {
                        return;
                    }
                    authorized.countDown();
                    try {
                        assertThat(demoted.await(20, TimeUnit.SECONDS)).isTrue();
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(exception);
                    }
                });

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<MvcResult> blockFuture = executor.submit(() -> mockMvc.perform(
                            post("/api/v1/operator/{operatorId}/trips/{tripId}/inventory/{inventoryId}/block",
                                    operatorId, trip.tripId(), inventoryId)
                                    .with(bearer(adminA.accessToken()))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"reason\":\"stale\"}"))
                    .andReturn());

            assertThat(authorized.await(20, TimeUnit.SECONDS)).isTrue();
            mockMvc.perform(patch("/api/v1/operator/{operatorId}/members/{userId}",
                            operatorId, adminA.user().getId())
                            .with(bearer(adminBToken.accessToken()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"role\":\"OPERATOR_STAFF\"}"))
                    .andExpect(status().isOk());
            demoted.countDown();

            MvcResult blockResult = blockFuture.get(30, TimeUnit.SECONDS);
            assertThat(blockResult.getResponse().getStatus()).isEqualTo(403);
            assertThat(tripSeatInventoryRepository.findById(inventoryId).orElseThrow().getPhysicalStatus())
                    .isEqualTo(TripSeatInventoryStatus.AVAILABLE);
        } finally {
            ReflectionTestUtils.setField(inventoryAdminService, "afterAuthorizeBeforeLockForTests", null);
            executor.shutdownNow();
        }
    }

    private record TripContext(
            UUID tripId,
            List<UUID> seatIds,
            List<UUID> stopIdsBySequence) {
        UUID stopId(int sequence) {
            return stopIdsBySequence.get(sequence - 1);
        }
    }

    private TripContext createScheduledTrip(
            IssuedOperatorMember admin, String departure, String arrival) throws Exception {
        UUID operatorId = admin.operator().getId();
        Fixture fx = createFixture(operatorId);
        MvcResult created = mockMvc.perform(post("/api/v1/operator/{operatorId}/trips", operatorId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createTripBody(fx.busId, fx.routeId, departure, arrival)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode tripJson = objectMapper.readTree(created.getResponse().getContentAsString());
        UUID tripId = UUID.fromString(tripJson.get("id").asText());
        mockMvc.perform(post("/api/v1/operator/{operatorId}/trips/{tripId}/schedule", operatorId, tripId)
                        .with(bearer(admin.accessToken())))
                .andExpect(status().isOk());

        List<UUID> seats = new ArrayList<>();
        for (JsonNode seat : tripJson.get("seatInventory")) {
            seats.add(UUID.fromString(seat.get("id").asText()));
        }
        List<UUID> stops = new ArrayList<>();
        for (JsonNode stop : tripJson.get("stops")) {
            stops.add(UUID.fromString(stop.get("id").asText()));
        }
        return new TripContext(tripId, seats, stops);
    }

    private record Fixture(UUID busId, UUID routeId, UUID layoutId) {
    }

    private Fixture createFixture(UUID operatorId) throws Exception {
        UUID origin = createLocation("Telangana", "Hyd-" + shortId());
        UUID via = createLocation("Telangana", "Via-" + shortId());
        UUID destination = createLocation("Andhra Pradesh", "Vja-" + shortId());
        UUID routeId = createRouteWithStops(operatorId, origin, via, destination);
        UUID busTypeId = createBusType("BT-" + shortId(), "Type");
        UUID layoutId = createPublishedLayout(operatorId, "L-" + shortId(), 1);
        UUID busId = createBus(operatorId, busTypeId, layoutId);
        return new Fixture(busId, routeId, layoutId);
    }

    private JsonNode createHold(TripContext trip, List<UUID> seats, String bearerToken) throws Exception {
        return createHold(trip, seats, bearerToken, trip.stopId(1), trip.stopId(3));
    }

    private JsonNode createHold(
            TripContext trip,
            List<UUID> seats,
            String bearerToken,
            UUID originStopId,
            UUID destinationStopId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/trips/{tripId}/holds", trip.tripId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(holdBody(originStopId, destinationStopId, seats)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String holdBody(UUID originStopId, UUID destinationStopId, List<UUID> seats) {
        StringBuilder seatJson = new StringBuilder("[");
        for (int i = 0; i < seats.size(); i++) {
            if (i > 0) {
                seatJson.append(',');
            }
            seatJson.append('"').append(seats.get(i)).append('"');
        }
        seatJson.append(']');
        return """
                {
                  "originStopId":"%s",
                  "destinationStopId":"%s",
                  "seatInventoryIds":%s
                }
                """.formatted(originStopId, destinationStopId, seatJson);
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

    private String createTripBody(UUID busId, UUID routeId, String departure, String arrival) {
        Instant dep = Instant.parse(departure);
        return """
                {
                  "busId":"%s",
                  "routeId":"%s",
                  "scheduledDepartureAt":"%s",
                  "scheduledArrivalAt":"%s",
                  "baseFare":500.00,
                  "bookingOpensAt":"%s",
                  "bookingClosesAt":"%s"
                }
                """.formatted(
                busId,
                routeId,
                departure,
                arrival,
                Instant.parse("2020-01-01T00:00:00Z"),
                dep.minusSeconds(3600));
    }

    private UUID createLocation(String state, String city) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/admin/locations")
                        .with(bearer(platformAdminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"countryCode":"IN","state":"%s","city":"%s","timeZone":"Asia/Kolkata"}
                                """.formatted(state, city)))
                .andExpect(status().isCreated())
                .andReturn();
        return idOf(result);
    }

    private UUID createRouteWithStops(UUID operatorId, UUID origin, UUID via, UUID destination)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/admin/routes")
                        .with(bearer(platformAdminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "operatorId":"%s",
                                  "code":"R-%s",
                                  "name":"Route %s",
                                  "sourceLocationId":"%s",
                                  "destinationLocationId":"%s",
                                  "stops":[
                                    {"locationId":"%s","sequenceNumber":1,"stopKind":"SOURCE",
                                     "departureOffsetMinutes":0,"distanceKm":0,
                                     "points":[{"name":"Origin","pointType":"BOARDING"}]},
                                    {"locationId":"%s","sequenceNumber":2,"stopKind":"INTERMEDIATE",
                                     "arrivalOffsetMinutes":90,"departureOffsetMinutes":100,"distanceKm":140,
                                     "points":[{"name":"Via","pointType":"BOTH"}]},
                                    {"locationId":"%s","sequenceNumber":3,"stopKind":"DESTINATION",
                                     "arrivalOffsetMinutes":270,"distanceKm":340,
                                     "points":[{"name":"Dest","pointType":"DROPPING"}]}
                                  ]
                                }
                                """.formatted(
                                operatorId, shortId(), shortId(), origin, destination,
                                origin, via, destination)))
                .andExpect(status().isCreated())
                .andReturn();
        return idOf(result);
    }

    private UUID createBusType(String code, String displayName) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/admin/bus-types")
                        .with(bearer(platformAdminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"%s\",\"displayName\":\"%s\"}".formatted(code, displayName)))
                .andExpect(status().isCreated())
                .andReturn();
        return idOf(result);
    }

    private UUID createPublishedLayout(UUID operatorId, String name, int version) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/admin/seat-layouts")
                        .with(bearer(platformAdminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "operatorId":"%s",
                                  "name":"%s",
                                  "version":%d,
                                  "deckCount":1,
                                  "rowCount":2,
                                  "columnCount":2,
                                  "seats":[
                                    {"seatNumber":"A1","deckNumber":1,"rowNumber":1,"columnNumber":1,"seatType":"SEATER"},
                                    {"seatNumber":"A2","deckNumber":1,"rowNumber":1,"columnNumber":2,"seatType":"SEATER"}
                                  ]
                                }
                                """.formatted(operatorId, name, version)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID layoutId = idOf(created);
        mockMvc.perform(post("/api/v1/admin/seat-layouts/{id}/activate", layoutId)
                        .with(bearer(platformAdminToken)))
                .andExpect(status().isOk());
        return layoutId;
    }

    private UUID createBus(UUID operatorId, UUID busTypeId, UUID layoutId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/admin/buses")
                        .with(bearer(platformAdminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "operatorId":"%s",
                                  "busTypeId":"%s",
                                  "seatLayoutId":"%s",
                                  "registrationNumber":"TS09%s"
                                }
                                """.formatted(operatorId, busTypeId, layoutId, shortId().substring(0, 4))))
                .andExpect(status().isCreated())
                .andReturn();
        return idOf(result);
    }

    private UUID idOf(MvcResult result) throws Exception {
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
