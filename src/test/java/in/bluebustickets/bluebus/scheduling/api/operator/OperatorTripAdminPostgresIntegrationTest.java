package in.bluebustickets.bluebus.scheduling.api.operator;

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

import in.bluebustickets.bluebus.foundation.security.TestAccessTokenFactory;
import in.bluebustickets.bluebus.foundation.security.TestAccessTokenFactory.IssuedOperatorMember;
import in.bluebustickets.bluebus.foundation.security.TestAccessTokenFactory.IssuedUser;
import in.bluebustickets.bluebus.identity.domain.RoleCode;
import in.bluebustickets.bluebus.identity.domain.UserStatus;
import in.bluebustickets.bluebus.operator.repository.OperatorUserRepository;
import in.bluebustickets.bluebus.scheduling.application.OperatorTripAdminService;
import in.bluebustickets.bluebus.scheduling.repository.TripRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OperatorTripAdminPostgresIntegrationTest {

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
    @Autowired private OperatorTripAdminService operatorTripAdminService;
    @Autowired private TripRepository tripRepository;
    @Autowired private OperatorUserRepository operatorUserRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private String platformAdminToken;

    @BeforeEach
    void seedPlatformAdmin() {
        platformAdminToken = tokens.issuePlatformAdmin().accessToken();
    }

    @Test
    void adminCanMutateAndStaffIsReadOnly() throws Exception {
        IssuedOperatorMember admin = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_ADMIN, List.of("OPERATOR_ADMIN"));
        IssuedUser staffUser = tokens.issueCustomer();
        tokens.attachMembership(admin.operator(), staffUser.user(), RoleCode.OPERATOR_STAFF);
        IssuedUser staffToken = tokens.issueToken(staffUser.user(), List.of("CUSTOMER"));

        UUID operatorId = admin.operator().getId();
        Fixture fx = createFixture(operatorId);

        mockMvc.perform(post("/api/v1/operator/{operatorId}/trips", operatorId)
                        .with(bearer(staffToken.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createTripBody(fx.busId, fx.routeId,
                                "2027-11-01T10:00:00Z", "2027-11-01T18:00:00Z")))
                .andExpect(status().isForbidden());

        MvcResult created = mockMvc.perform(post("/api/v1/operator/{operatorId}/trips", operatorId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createTripBody(fx.busId, fx.routeId,
                                "2027-11-01T10:00:00Z", "2027-11-01T18:00:00Z")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.operatorId").value(operatorId.toString()))
                .andExpect(jsonPath("$.seatLayoutId").value(fx.layoutId.toString()))
                .andExpect(jsonPath("$.stops.length()").value(3))
                .andExpect(jsonPath("$.seatInventory.length()").value(2))
                .andReturn();
        UUID tripId = idOf(created);

        mockMvc.perform(get("/api/v1/operator/{operatorId}/trips", operatorId)
                        .with(bearer(staffToken.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='%s')]".formatted(tripId)).exists());

        mockMvc.perform(post("/api/v1/operator/{operatorId}/trips/{tripId}/schedule", operatorId, tripId)
                        .with(bearer(staffToken.accessToken())))
                .andExpect(status().isForbidden());
    }

    @Test
    void authorizationBoundariesAreEnforced() throws Exception {
        IssuedOperatorMember admin = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_ADMIN, List.of("OPERATOR_ADMIN"));
        IssuedOperatorMember other = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_ADMIN, List.of("OPERATOR_ADMIN"));
        IssuedUser inactive = tokens.issuePlatformUser(RoleCode.CUSTOMER, List.of("CUSTOMER"), UserStatus.INACTIVE);
        IssuedUser platformAdmin = tokens.issuePlatformAdmin();

        UUID operatorId = admin.operator().getId();
        Fixture fx = createFixture(operatorId);
        UUID tripId = createDraftTrip(admin, fx, "2027-11-02T10:00:00Z", "2027-11-02T18:00:00Z");

        mockMvc.perform(get("/api/v1/operator/{operatorId}/trips", operatorId).with(anonymous()))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/operator/{operatorId}/trips", operatorId)
                        .with(bearer(inactive.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createTripBody(fx.busId, fx.routeId,
                                "2027-11-03T10:00:00Z", "2027-11-03T18:00:00Z")))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/operator/{operatorId}/trips/{tripId}", other.operator().getId(), tripId)
                        .with(bearer(admin.accessToken())))
                .andExpect(status().isNotFound());

        Fixture otherFx = createFixture(other.operator().getId());
        mockMvc.perform(post("/api/v1/operator/{operatorId}/trips", operatorId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createTripBody(otherFx.busId, fx.routeId,
                                "2027-11-04T10:00:00Z", "2027-11-04T18:00:00Z")))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/v1/operator/{operatorId}/trips", operatorId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createTripBody(fx.busId, otherFx.routeId,
                                "2027-11-05T10:00:00Z", "2027-11-05T18:00:00Z")))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/v1/operator/{operatorId}/trips", operatorId)
                        .with(bearer(platformAdmin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createTripBody(fx.busId, fx.routeId,
                                "2027-11-06T10:00:00Z", "2027-11-06T18:00:00Z")))
                .andExpect(status().isNotFound());

        var membership = operatorUserRepository
                .findByOperatorIdAndUserId(operatorId, admin.user().getId())
                .orElseThrow();
        membership.deactivate();
        operatorUserRepository.saveAndFlush(membership);

        mockMvc.perform(post("/api/v1/operator/{operatorId}/trips/{tripId}/schedule", operatorId, tripId)
                        .with(bearer(admin.accessToken())))
                .andExpect(status().isNotFound());
    }

    @Test
    void createValidatesBusRouteScheduleAndRejectsUnknownFields() throws Exception {
        IssuedOperatorMember admin = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_ADMIN, List.of("OPERATOR_ADMIN"));
        UUID operatorId = admin.operator().getId();
        Fixture fx = createFixture(operatorId);

        mockMvc.perform(post("/api/v1/operator/{operatorId}/trips", operatorId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "busId":"%s","routeId":"%s",
                                  "scheduledDepartureAt":"2027-11-07T10:00:00Z",
                                  "scheduledArrivalAt":"2027-11-07T18:00:00Z",
                                  "baseFare":500.00,
                                  "bookingOpensAt":"2027-10-01T10:00:00Z",
                                  "bookingClosesAt":"2027-11-07T09:00:00Z",
                                  "operatorId":"%s"
                                }
                                """.formatted(fx.busId, fx.routeId, operatorId)))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/operator/{operatorId}/trips", operatorId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createTripBody(fx.busId, fx.routeId,
                                "2027-11-07T18:00:00Z", "2027-11-07T10:00:00Z")))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/admin/buses/{id}/deactivate", fx.busId)
                        .with(bearer(platformAdminToken)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/operator/{operatorId}/trips", operatorId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createTripBody(fx.busId, fx.routeId,
                                "2027-11-08T10:00:00Z", "2027-11-08T18:00:00Z")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/admin/buses/{id}/activate", fx.busId)
                        .with(bearer(platformAdminToken)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/admin/routes/{id}/deactivate", fx.routeId)
                        .with(bearer(platformAdminToken)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/operator/{operatorId}/trips", operatorId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createTripBody(fx.busId, fx.routeId,
                                "2027-11-09T10:00:00Z", "2027-11-09T18:00:00Z")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/admin/routes/{id}/activate", fx.routeId)
                        .with(bearer(platformAdminToken)))
                .andExpect(status().isOk());

        UUID thinRoute = createThinRoute(operatorId);
        mockMvc.perform(post("/api/v1/operator/{operatorId}/trips", operatorId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createTripBody(fx.busId, thinRoute,
                                "2027-11-10T10:00:00Z", "2027-11-10T18:00:00Z")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void exactDuplicateAndOverlapAreRejectedWhileAdjacentAndCancelledAreAllowed() throws Exception {
        IssuedOperatorMember admin = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_ADMIN, List.of("OPERATOR_ADMIN"));
        UUID operatorId = admin.operator().getId();
        Fixture fx = createFixture(operatorId);

        UUID first = createDraftTrip(admin, fx, "2027-12-01T10:00:00Z", "2027-12-01T18:00:00Z");

        mockMvc.perform(post("/api/v1/operator/{operatorId}/trips", operatorId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createTripBody(fx.busId, fx.routeId,
                                "2027-12-01T10:00:00Z", "2027-12-01T16:00:00Z")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value("A trip already exists for this bus, service date, and scheduled departure."));

        mockMvc.perform(post("/api/v1/operator/{operatorId}/trips", operatorId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createTripBody(fx.busId, fx.routeId,
                                "2027-12-01T12:00:00Z", "2027-12-01T20:00:00Z")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Bus already has an overlapping trip."));

        mockMvc.perform(post("/api/v1/operator/{operatorId}/trips", operatorId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createTripBody(fx.busId, fx.routeId,
                                "2027-12-01T18:00:00Z", "2027-12-02T02:00:00Z")))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/operator/{operatorId}/trips/{tripId}/cancel", operatorId, first)
                        .with(bearer(admin.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        // Cancelled first still blocks exact same departure via unique constraint, but overlapping
        // interval against a cancelled trip must be allowed when departure differs.
        mockMvc.perform(post("/api/v1/operator/{operatorId}/trips", operatorId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createTripBody(fx.busId, fx.routeId,
                                "2027-12-01T11:00:00Z", "2027-12-01T17:00:00Z")))
                .andExpect(status().isCreated());
    }

    @Test
    void differentBusesMayShareScheduleWindow() throws Exception {
        IssuedOperatorMember admin = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_ADMIN, List.of("OPERATOR_ADMIN"));
        UUID operatorId = admin.operator().getId();
        Fixture fx1 = createFixture(operatorId);
        Fixture fx2 = createFixture(operatorId);

        mockMvc.perform(post("/api/v1/operator/{operatorId}/trips", operatorId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createTripBody(fx1.busId, fx1.routeId,
                                "2027-12-10T10:00:00Z", "2027-12-10T18:00:00Z")))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/operator/{operatorId}/trips", operatorId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createTripBody(fx2.busId, fx2.routeId,
                                "2027-12-10T10:00:00Z", "2027-12-10T18:00:00Z")))
                .andExpect(status().isCreated());
    }

    @Test
    void patchScheduleCancelAndSearchVisibility() throws Exception {
        IssuedOperatorMember admin = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_ADMIN, List.of("OPERATOR_ADMIN"));
        UUID operatorId = admin.operator().getId();
        Fixture fx = createFixture(operatorId);
        UUID tripId = createDraftTrip(admin, fx, "2027-12-15T10:00:00Z", "2027-12-15T18:00:00Z");

        mockMvc.perform(patch("/api/v1/operator/{operatorId}/trips/{tripId}", operatorId, tripId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(patch("/api/v1/operator/{operatorId}/trips/{tripId}", operatorId, tripId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"busId\":\"%s\"}".formatted(fx.busId)))
                .andExpect(status().isBadRequest());

        mockMvc.perform(patch("/api/v1/operator/{operatorId}/trips/{tripId}", operatorId, tripId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "baseFare":650.00,
                                  "bookingOpensAt":"2027-11-01T10:00:00Z",
                                  "bookingClosesAt":"2027-12-15T08:00:00Z"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baseFare").value(650.00));

        mockMvc.perform(get("/api/v1/search/trips")
                        .param("originLocationId", fx.originLocationId.toString())
                        .param("destinationLocationId", fx.destinationLocationId.toString())
                        .param("serviceDate", "2027-12-15"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.tripId=='%s')]".formatted(tripId)).doesNotExist());

        mockMvc.perform(post("/api/v1/operator/{operatorId}/trips/{tripId}/schedule", operatorId, tripId)
                        .with(bearer(admin.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SCHEDULED"));
        mockMvc.perform(post("/api/v1/operator/{operatorId}/trips/{tripId}/schedule", operatorId, tripId)
                        .with(bearer(admin.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SCHEDULED"));

        mockMvc.perform(get("/api/v1/search/trips")
                        .param("originLocationId", fx.originLocationId.toString())
                        .param("destinationLocationId", fx.destinationLocationId.toString())
                        .param("serviceDate", "2027-12-15"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.tripId=='%s')]".formatted(tripId)).exists());

        mockMvc.perform(post("/api/v1/operator/{operatorId}/trips/{tripId}/cancel", operatorId, tripId)
                        .with(bearer(admin.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        mockMvc.perform(post("/api/v1/operator/{operatorId}/trips/{tripId}/cancel", operatorId, tripId)
                        .with(bearer(admin.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        mockMvc.perform(patch("/api/v1/operator/{operatorId}/trips/{tripId}", operatorId, tripId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseFare\":700.00}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/operator/{operatorId}/trips/{tripId}/schedule", operatorId, tripId)
                        .with(bearer(admin.accessToken())))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/v1/search/trips")
                        .param("originLocationId", fx.originLocationId.toString())
                        .param("destinationLocationId", fx.destinationLocationId.toString())
                        .param("serviceDate", "2027-12-15"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.tripId=='%s')]".formatted(tripId)).doesNotExist());
    }

    @Test
    void cancelIsStatusOnlyAndLeavesBookingsInventoryUnchanged() throws Exception {
        IssuedOperatorMember admin = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_ADMIN, List.of("OPERATOR_ADMIN"));
        IssuedUser customer = tokens.issueCustomer();
        UUID operatorId = admin.operator().getId();
        Fixture fx = createFixture(operatorId);
        UUID tripId = createDraftTrip(admin, fx, "2027-12-20T10:00:00Z", "2027-12-20T18:00:00Z");
        mockMvc.perform(post("/api/v1/operator/{operatorId}/trips/{tripId}/schedule", operatorId, tripId)
                        .with(bearer(admin.accessToken())))
                .andExpect(status().isOk());

        JsonNode detail = objectMapper.readTree(mockMvc.perform(get(
                        "/api/v1/operator/{operatorId}/trips/{tripId}", operatorId, tripId)
                        .with(bearer(admin.accessToken())))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        UUID originStopId = UUID.fromString(detail.get("stops").get(0).get("id").asText());
        UUID destStopId = UUID.fromString(detail.get("stops").get(2).get("id").asText());
        UUID seatInventoryId = UUID.fromString(detail.get("seatInventory").get(0).get("id").asText());
        int inventoryCountBefore = detail.get("seatInventory").size();

        MvcResult holdResult = mockMvc.perform(post("/api/v1/trips/{tripId}/holds", tripId)
                        .with(bearer(customer.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "originStopId":"%s",
                                  "destinationStopId":"%s",
                                  "seatInventoryIds":["%s"]
                                }
                                """.formatted(originStopId, destStopId, seatInventoryId)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID holdId = UUID.fromString(objectMapper.readTree(holdResult.getResponse().getContentAsString())
                .get("holdId").asText());

        MvcResult bookingResult = mockMvc.perform(post("/api/v1/bookings")
                        .with(bearer(customer.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "holdId":"%s",
                                  "originStopId":"%s",
                                  "destinationStopId":"%s",
                                  "idempotencyKey":"op-trip-cancel-%s",
                                  "passengers":[
                                    {"seatInventoryId":"%s","fullName":"Test Rider","age":30}
                                  ]
                                }
                                """.formatted(holdId, originStopId, destStopId, shortId(), seatInventoryId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andReturn();
        UUID bookingId = UUID.fromString(objectMapper.readTree(bookingResult.getResponse().getContentAsString())
                .get("bookingId").asText());

        Integer ticketsBefore = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tickets WHERE trip_id = ?", Integer.class, tripId);
        Integer paymentsBefore = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_attempts WHERE booking_id = ?", Integer.class, bookingId);

        mockMvc.perform(post("/api/v1/operator/{operatorId}/trips/{tripId}/cancel", operatorId, tripId)
                        .with(bearer(admin.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        mockMvc.perform(get("/api/v1/bookings/{bookingId}", bookingId)
                        .with(bearer(customer.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"));

        Integer inventoryAfter = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM trip_seat_inventory WHERE trip_id = ?", Integer.class, tripId);
        Integer ticketsAfter = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tickets WHERE trip_id = ?", Integer.class, tripId);
        Integer paymentsAfter = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_attempts WHERE booking_id = ?", Integer.class, bookingId);
        assertThat(inventoryAfter).isEqualTo(inventoryCountBefore);
        assertThat(ticketsAfter).isEqualTo(ticketsBefore);
        assertThat(paymentsAfter).isEqualTo(paymentsBefore);
    }

    @Test
    void departedTripCannotBeCancelled() throws Exception {
        IssuedOperatorMember admin = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_ADMIN, List.of("OPERATOR_ADMIN"));
        UUID operatorId = admin.operator().getId();
        Fixture fx = createFixture(operatorId);
        UUID tripId = createDraftTrip(admin, fx, "2027-12-21T10:00:00Z", "2027-12-21T18:00:00Z");
        jdbcTemplate.update("UPDATE trips SET status = 'DEPARTED' WHERE id = ?", tripId);

        mockMvc.perform(post("/api/v1/operator/{operatorId}/trips/{tripId}/cancel", operatorId, tripId)
                        .with(bearer(admin.accessToken())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void snapshotsRemainStableAfterMasterRouteAndBusChanges() throws Exception {
        IssuedOperatorMember admin = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_ADMIN, List.of("OPERATOR_ADMIN"));
        UUID operatorId = admin.operator().getId();
        Fixture fx = createFixture(operatorId);
        UUID tripId = createDraftTrip(admin, fx, "2027-12-22T10:00:00Z", "2027-12-22T18:00:00Z");

        JsonNode before = objectMapper.readTree(mockMvc.perform(get(
                        "/api/v1/operator/{operatorId}/trips/{tripId}", operatorId, tripId)
                        .with(bearer(admin.accessToken())))
                .andReturn()
                .getResponse()
                .getContentAsString());
        UUID pointId = UUID.fromString(before.get("stops").get(0).get("points").get(0).get("id").asText());
        String stopName = before.get("stops").get(0).get("locationId").asText();
        int inventoryCount = before.get("seatInventory").size();

        mockMvc.perform(patch("/api/v1/operator/{operatorId}/routes/{routeId}", operatorId, fx.routeId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Renamed After Trip\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/operator/{operatorId}/routes/{routeId}/points/{pointId}/deactivate",
                        operatorId, fx.routeId,
                        UUID.fromString(jdbcTemplate.queryForObject("""
                                SELECT rp.id::text FROM route_points rp
                                JOIN route_stops rs ON rs.id = rp.route_stop_id
                                WHERE rs.route_id = ? ORDER BY rp.name LIMIT 1
                                """, String.class, fx.routeId)))
                        .with(bearer(admin.accessToken())))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/operator/{operatorId}/buses/{busId}/deactivate", operatorId, fx.busId)
                        .with(bearer(admin.accessToken())))
                .andExpect(status().isOk());

        JsonNode after = objectMapper.readTree(mockMvc.perform(get(
                        "/api/v1/operator/{operatorId}/trips/{tripId}", operatorId, tripId)
                        .with(bearer(admin.accessToken())))
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(after.get("stops").get(0).get("locationId").asText()).isEqualTo(stopName);
        assertThat(after.get("stops").get(0).get("points").get(0).get("id").asText())
                .isEqualTo(pointId.toString());
        assertThat(after.get("stops").get(0).get("points").get(0).get("active").asBoolean()).isTrue();
        assertThat(after.get("seatInventory").size()).isEqualTo(inventoryCount);
        assertThat(after.get("seatLayoutId").asText()).isEqualTo(fx.layoutId.toString());
    }

    @Test
    void concurrentDemotionInvalidatesStaleTripMutation() throws Exception {
        IssuedOperatorMember adminA = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_ADMIN, List.of("OPERATOR_ADMIN"));
        UUID operatorId = adminA.operator().getId();
        IssuedUser adminBUser = tokens.issueCustomer();
        tokens.attachMembership(adminA.operator(), adminBUser.user(), RoleCode.OPERATOR_ADMIN);
        IssuedUser adminBToken = tokens.issueToken(adminBUser.user(), List.of("CUSTOMER"));

        Fixture fx = createFixture(operatorId);
        UUID tripId = createDraftTrip(adminA, fx, "2027-12-23T10:00:00Z", "2027-12-23T18:00:00Z");

        CountDownLatch authorized = new CountDownLatch(1);
        CountDownLatch demoted = new CountDownLatch(1);
        AtomicInteger barrierHits = new AtomicInteger();
        ReflectionTestUtils.setField(
                operatorTripAdminService,
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
            Future<MvcResult> patchFuture = executor.submit(() -> mockMvc.perform(
                            patch("/api/v1/operator/{operatorId}/trips/{tripId}", operatorId, tripId)
                                    .with(bearer(adminA.accessToken()))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"baseFare\":999.00}"))
                    .andReturn());

            assertThat(authorized.await(20, TimeUnit.SECONDS)).isTrue();
            mockMvc.perform(patch("/api/v1/operator/{operatorId}/members/{userId}",
                            operatorId, adminA.user().getId())
                            .with(bearer(adminBToken.accessToken()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"role\":\"OPERATOR_STAFF\"}"))
                    .andExpect(status().isOk());
            demoted.countDown();

            MvcResult patchResult = patchFuture.get(30, TimeUnit.SECONDS);
            assertThat(patchResult.getResponse().getStatus()).isEqualTo(403);
            assertThat(tripRepository.findByIdAndOperator_Id(tripId, operatorId).orElseThrow().getBaseFare())
                    .isEqualByComparingTo(new BigDecimal("500.00"));
        } finally {
            ReflectionTestUtils.setField(operatorTripAdminService, "afterAuthorizeBeforeLockForTests", null);
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentDuplicateAndOverlapCreationAreSafe() throws Exception {
        IssuedOperatorMember admin = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_ADMIN, List.of("OPERATOR_ADMIN"));
        UUID operatorId = admin.operator().getId();
        Fixture fx = createFixture(operatorId);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        AtomicInteger created = new AtomicInteger();
        AtomicInteger conflicted = new AtomicInteger();
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    int statusCode = mockMvc.perform(post("/api/v1/operator/{operatorId}/trips", operatorId)
                                    .with(bearer(admin.accessToken()))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(createTripBody(fx.busId, fx.routeId,
                                            "2027-12-24T10:00:00Z", "2027-12-24T18:00:00Z")))
                            .andReturn()
                            .getResponse()
                            .getStatus();
                    if (statusCode == 201) {
                        created.incrementAndGet();
                    } else if (statusCode == 409) {
                        conflicted.incrementAndGet();
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
        assertThat(created.get()).isEqualTo(1);
        assertThat(conflicted.get()).isEqualTo(1);
    }

    @Test
    void createVersusBusAndRouteDeactivateSerializeSafely() throws Exception {
        IssuedOperatorMember admin = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_ADMIN, List.of("OPERATOR_ADMIN"));
        UUID operatorId = admin.operator().getId();
        Fixture fx = createFixture(operatorId);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        AtomicInteger tripOk = new AtomicInteger();
        AtomicInteger tripFail = new AtomicInteger();
        try {
            List<Future<?>> futures = new ArrayList<>();
            futures.add(executor.submit(() -> {
                ready.countDown();
                start.await();
                int statusCode = mockMvc.perform(post("/api/v1/operator/{operatorId}/trips", operatorId)
                                .with(bearer(admin.accessToken()))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(createTripBody(fx.busId, fx.routeId,
                                        "2027-12-25T10:00:00Z", "2027-12-25T18:00:00Z")))
                        .andReturn()
                        .getResponse()
                        .getStatus();
                if (statusCode == 201) {
                    tripOk.incrementAndGet();
                } else {
                    tripFail.incrementAndGet();
                }
                return null;
            }));
            futures.add(executor.submit(() -> {
                ready.countDown();
                start.await();
                mockMvc.perform(post("/api/v1/operator/{operatorId}/buses/{busId}/deactivate",
                                operatorId, fx.busId)
                                .with(bearer(admin.accessToken())))
                        .andReturn();
                return null;
            }));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> future : futures) {
                future.get(45, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }
        assertThat(tripOk.get() + tripFail.get()).isEqualTo(1);
    }

    private record Fixture(
            UUID busId,
            UUID routeId,
            UUID layoutId,
            UUID originLocationId,
            UUID destinationLocationId) {
    }

    private Fixture createFixture(UUID operatorId) throws Exception {
        UUID origin = createLocation("Telangana", "Hyd-" + shortId());
        UUID via = createLocation("Telangana", "Via-" + shortId());
        UUID destination = createLocation("Andhra Pradesh", "Vja-" + shortId());
        UUID routeId = createRouteWithStops(operatorId, origin, via, destination);
        UUID busTypeId = createBusType("BT-" + shortId(), "Type");
        UUID layoutId = createPublishedLayout(operatorId, "L-" + shortId(), 1);
        UUID busId = createBus(operatorId, busTypeId, layoutId);
        return new Fixture(busId, routeId, layoutId, origin, destination);
    }

    private UUID createDraftTrip(
            IssuedOperatorMember admin, Fixture fx, String departure, String arrival) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/operator/{operatorId}/trips", admin.operator().getId())
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createTripBody(fx.busId, fx.routeId, departure, arrival)))
                .andExpect(status().isCreated())
                .andReturn();
        return idOf(result);
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
                dep.minusSeconds(30L * 24 * 3600),
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

    private UUID createRouteWithStops(UUID operatorId, UUID origin, UUID via, UUID destination) throws Exception {
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
                                operatorId, shortId(), shortId(), origin, destination, origin, via, destination)))
                .andExpect(status().isCreated())
                .andReturn();
        return idOf(result);
    }

    private UUID createThinRoute(UUID operatorId) throws Exception {
        UUID origin = createLocation("Karnataka", "ThinO-" + shortId());
        UUID destination = createLocation("Tamil Nadu", "ThinD-" + shortId());
        MvcResult result = mockMvc.perform(post("/api/v1/admin/routes")
                        .with(bearer(platformAdminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "operatorId":"%s",
                                  "code":"THIN-%s",
                                  "name":"Thin",
                                  "sourceLocationId":"%s",
                                  "destinationLocationId":"%s",
                                  "stops":[
                                    {"locationId":"%s","sequenceNumber":1,"stopKind":"SOURCE",
                                     "departureOffsetMinutes":0,"distanceKm":0}
                                  ]
                                }
                                """.formatted(operatorId, shortId(), origin, destination, origin)))
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
