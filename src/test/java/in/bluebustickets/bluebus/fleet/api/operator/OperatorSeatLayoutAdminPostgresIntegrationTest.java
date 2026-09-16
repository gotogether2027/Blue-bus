package in.bluebustickets.bluebus.fleet.api.operator;

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

import in.bluebustickets.bluebus.fleet.application.OperatorSeatLayoutAdminService;
import in.bluebustickets.bluebus.fleet.repository.SeatLayoutRepository;
import in.bluebustickets.bluebus.foundation.security.TestAccessTokenFactory;
import in.bluebustickets.bluebus.foundation.security.TestAccessTokenFactory.IssuedOperatorMember;
import in.bluebustickets.bluebus.foundation.security.TestAccessTokenFactory.IssuedUser;
import in.bluebustickets.bluebus.identity.domain.RoleCode;
import in.bluebustickets.bluebus.identity.domain.UserStatus;
import in.bluebustickets.bluebus.operator.domain.Operator;
import in.bluebustickets.bluebus.operator.repository.OperatorRepository;
import in.bluebustickets.bluebus.operator.repository.OperatorUserRepository;
import in.bluebustickets.bluebus.scheduling.domain.TripSeatInventoryStatus;
import in.bluebustickets.bluebus.scheduling.repository.TripSeatInventoryRepository;
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
class OperatorSeatLayoutAdminPostgresIntegrationTest {

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
    @Autowired private OperatorSeatLayoutAdminService operatorSeatLayoutAdminService;
    @Autowired private OperatorUserRepository operatorUserRepository;
    @Autowired private OperatorRepository operatorRepository;
    @Autowired private SeatLayoutRepository seatLayoutRepository;
    @Autowired private TripSeatInventoryRepository tripSeatInventoryRepository;
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
        UUID layoutId = createDraftLayout(admin, "StaffRead", 1);

        mockMvc.perform(get("/api/v1/operator/{operatorId}/seat-layouts", operatorId)
                        .with(bearer(staffToken.accessToken())))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/operator/{operatorId}/seat-layouts/{layoutId}/activate",
                        operatorId, layoutId)
                        .with(bearer(staffToken.accessToken())))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/operator/{operatorId}/seat-layouts/{layoutId}/activate",
                        operatorId, layoutId)
                        .with(bearer(admin.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"));
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
        UUID layoutId = createDraftLayout(admin, "AuthBound", 1);

        mockMvc.perform(get("/api/v1/operator/{operatorId}/seat-layouts", operatorId).with(anonymous()))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/operator/{operatorId}/seat-layouts/{layoutId}",
                        other.operator().getId(), layoutId)
                        .with(bearer(admin.accessToken())))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/operator/{operatorId}/seat-layouts/{layoutId}",
                        operatorId, layoutId)
                        .with(bearer(platformAdmin.accessToken())))
                .andExpect(status().isNotFound());

        Operator inactiveOperator = tokens.persistActiveOperator();
        IssuedUser inactiveOpUser = tokens.issueCustomer();
        tokens.attachMembership(inactiveOperator, inactiveOpUser.user(), RoleCode.OPERATOR_ADMIN);
        IssuedUser inactiveOpToken = tokens.issueToken(inactiveOpUser.user(), List.of("CUSTOMER"));
        inactiveOperator.deactivate();
        operatorRepository.saveAndFlush(inactiveOperator);

        mockMvc.perform(get("/api/v1/operator/{operatorId}/seat-layouts",
                        inactiveOperator.getId())
                        .with(bearer(inactiveOpToken.accessToken())))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/operator/{operatorId}/seat-layouts", operatorId)
                        .with(bearer(inactive.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(minimalLayoutBody("X", 99)))
                .andExpect(status().isUnauthorized());

        var membership = operatorUserRepository
                .findByOperatorIdAndUserId(operatorId, admin.user().getId())
                .orElseThrow();
        membership.deactivate();
        operatorUserRepository.saveAndFlush(membership);

        mockMvc.perform(patch("/api/v1/operator/{operatorId}/seat-layouts/{layoutId}",
                        operatorId, layoutId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Late\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void createValidatesLayoutAndSeats() throws Exception {
        IssuedOperatorMember admin = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_ADMIN, List.of("OPERATOR_ADMIN"));
        UUID operatorId = admin.operator().getId();

        mockMvc.perform(post("/api/v1/operator/{operatorId}/seat-layouts", operatorId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":"Empty",
                                  "version":1,
                                  "deckCount":1,
                                  "rowCount":1,
                                  "columnCount":1,
                                  "seats":[]
                                }
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/operator/{operatorId}/seat-layouts", operatorId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":"BadSeat",
                                  "version":1,
                                  "deckCount":1,
                                  "rowCount":1,
                                  "columnCount":1,
                                  "seats":[
                                    {"seatNumber":"A1","deckNumber":2,"rowNumber":1,"columnNumber":1,"seatType":"SEATER"}
                                  ]
                                }
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/operator/{operatorId}/seat-layouts", operatorId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":"DupNum",
                                  "version":1,
                                  "deckCount":1,
                                  "rowCount":2,
                                  "columnCount":1,
                                  "seats":[
                                    {"seatNumber":"A1","deckNumber":1,"rowNumber":1,"columnNumber":1,"seatType":"SEATER"},
                                    {"seatNumber":"a1","deckNumber":1,"rowNumber":2,"columnNumber":1,"seatType":"SEATER"}
                                  ]
                                }
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/operator/{operatorId}/seat-layouts", operatorId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":"DupPos",
                                  "version":1,
                                  "deckCount":1,
                                  "rowCount":2,
                                  "columnCount":2,
                                  "seats":[
                                    {"seatNumber":"A1","deckNumber":1,"rowNumber":1,"columnNumber":1,"seatType":"SEATER"},
                                    {"seatNumber":"B1","deckNumber":1,"rowNumber":1,"columnNumber":1,"seatType":"SEATER"}
                                  ]
                                }
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/operator/{operatorId}/seat-layouts", operatorId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":"",
                                  "version":1,
                                  "deckCount":1,
                                  "rowCount":1,
                                  "columnCount":1,
                                  "seats":[
                                    {"seatNumber":"A1","deckNumber":1,"rowNumber":1,"columnNumber":1,"seatType":"SEATER"}
                                  ]
                                }
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/operator/{operatorId}/seat-layouts", operatorId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":"Unknown",
                                  "version":1,
                                  "deckCount":1,
                                  "rowCount":1,
                                  "columnCount":1,
                                  "operatorId":"%s",
                                  "seats":[
                                    {"seatNumber":"A1","deckNumber":1,"rowNumber":1,"columnNumber":1,"seatType":"SEATER"}
                                  ]
                                }
                                """.formatted(operatorId)))
                .andExpect(status().isBadRequest());

        MvcResult created = mockMvc.perform(post("/api/v1/operator/{operatorId}/seat-layouts", operatorId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(minimalLayoutBody("ValidLayout", 1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.seats.length()").value(1))
                .andReturn();
        assertThat(objectMapper.readTree(created.getResponse().getContentAsString()).get("email"))
                .isNull();
    }

    @Test
    void patchPublishAndArchiveLifecycle() throws Exception {
        IssuedOperatorMember admin = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_ADMIN, List.of("OPERATOR_ADMIN"));
        UUID operatorId = admin.operator().getId();
        UUID layoutId = createDraftLayout(admin, "Lifecycle", 1);

        mockMvc.perform(patch("/api/v1/operator/{operatorId}/seat-layouts/{layoutId}",
                        operatorId, layoutId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Lifecycle Premium\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Lifecycle Premium"));

        MvcResult shrinkCreated = mockMvc.perform(post("/api/v1/operator/{operatorId}/seat-layouts", operatorId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":"ShrinkTest",
                                  "version":2,
                                  "deckCount":1,
                                  "rowCount":2,
                                  "columnCount":1,
                                  "seats":[
                                    {"seatNumber":"B1","deckNumber":1,"rowNumber":2,"columnNumber":1,"seatType":"SEATER"}
                                  ]
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        UUID shrinkId = UUID.fromString(objectMapper.readTree(shrinkCreated.getResponse().getContentAsString())
                .get("id")
                .asText());

        mockMvc.perform(patch("/api/v1/operator/{operatorId}/seat-layouts/{layoutId}",
                        operatorId, shrinkId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rowCount\":1}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(patch("/api/v1/operator/{operatorId}/seat-layouts/{layoutId}",
                        operatorId, layoutId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seats\":[]}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/operator/{operatorId}/seat-layouts/{layoutId}/activate",
                        operatorId, layoutId)
                        .with(bearer(admin.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"));

        mockMvc.perform(post("/api/v1/operator/{operatorId}/seat-layouts/{layoutId}/activate",
                        operatorId, layoutId)
                        .with(bearer(admin.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"));

        mockMvc.perform(patch("/api/v1/operator/{operatorId}/seat-layouts/{layoutId}",
                        operatorId, layoutId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Too Late\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/operator/{operatorId}/seat-layouts/{layoutId}/deactivate",
                        operatorId, layoutId)
                        .with(bearer(admin.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARCHIVED"));

        mockMvc.perform(post("/api/v1/operator/{operatorId}/seat-layouts/{layoutId}/deactivate",
                        operatorId, layoutId)
                        .with(bearer(admin.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARCHIVED"));

        mockMvc.perform(post("/api/v1/operator/{operatorId}/seat-layouts/{layoutId}/activate",
                        operatorId, layoutId)
                        .with(bearer(admin.accessToken())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void caseInsensitiveNameVersionUniquenessAndConcurrentCreate() throws Exception {
        IssuedOperatorMember admin = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_ADMIN, List.of("OPERATOR_ADMIN"));
        UUID operatorId = admin.operator().getId();

        createDraftLayout(admin, "UniqueName", 1);

        mockMvc.perform(post("/api/v1/operator/{operatorId}/seat-layouts", operatorId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(minimalLayoutBody("UniqueName", 1)))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/v1/operator/{operatorId}/seat-layouts", operatorId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(minimalLayoutBody("uniquename", 1)))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/v1/operator/{operatorId}/seat-layouts", operatorId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(minimalLayoutBody("UniqueName", 2)))
                .andExpect(status().isCreated());

        IssuedOperatorMember other = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_ADMIN, List.of("OPERATOR_ADMIN"));
        mockMvc.perform(post("/api/v1/operator/{operatorId}/seat-layouts", other.operator().getId())
                        .with(bearer(other.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(minimalLayoutBody("UniqueName", 1)))
                .andExpect(status().isCreated());

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger created = new AtomicInteger();
        AtomicInteger conflicted = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    int statusCode = mockMvc.perform(post(
                                    "/api/v1/operator/{operatorId}/seat-layouts", operatorId)
                                    .with(bearer(admin.accessToken()))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(minimalLayoutBody("RaceLayout", 7)))
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
    void busIntegrationAndArchiveDoesNotDetachBus() throws Exception {
        IssuedOperatorMember admin = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_ADMIN, List.of("OPERATOR_ADMIN"));
        UUID operatorId = admin.operator().getId();
        UUID layoutId = createDraftLayout(admin, "BusLayout", 1);

        mockMvc.perform(post("/api/v1/operator/{operatorId}/seat-layouts/{layoutId}/activate",
                        operatorId, layoutId)
                        .with(bearer(admin.accessToken())))
                .andExpect(status().isOk());

        UUID busTypeId = createBusType();
        UUID busId = createBusWithAdminToken(operatorId, busTypeId, layoutId, admin);

        mockMvc.perform(post("/api/v1/operator/{operatorId}/seat-layouts/{layoutId}/deactivate",
                        operatorId, layoutId)
                        .with(bearer(admin.accessToken())))
                .andExpect(status().isOk());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT seat_layout_id FROM buses WHERE id = ?", UUID.class, busId))
                .isEqualTo(layoutId);

        mockMvc.perform(post("/api/v1/operator/{operatorId}/buses", operatorId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "busTypeId":"%s",
                                  "seatLayoutId":"%s",
                                  "registrationNumber":"TS09AR%s"
                                }
                                """.formatted(busTypeId, layoutId, shortId().substring(0, 4))))
                .andExpect(status().isConflict());
    }

    @Test
    void archiveDoesNotAlterTripInventorySnapshot() throws Exception {
        IssuedOperatorMember admin = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_ADMIN, List.of("OPERATOR_ADMIN"));
        UUID operatorId = admin.operator().getId();
        UUID layoutId = createDraftLayout(admin, "SnapLayout", 3);
        activateLayout(admin, layoutId);

        TripFixture trip = createScheduledTrip(admin, layoutId);
        JsonNode inventoryBefore = objectMapper.readTree(mockMvc.perform(
                        get("/api/v1/operator/{operatorId}/trips/{tripId}/inventory/{inventoryId}",
                                operatorId, trip.tripId(), trip.inventoryId())
                                .with(bearer(admin.accessToken())))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());

        mockMvc.perform(patch("/api/v1/operator/{operatorId}/seat-layouts/{layoutId}",
                        operatorId, layoutId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Renamed After Trip\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/operator/{operatorId}/seat-layouts/{layoutId}/deactivate",
                        operatorId, layoutId)
                        .with(bearer(admin.accessToken())))
                .andExpect(status().isOk());

        JsonNode inventoryAfter = objectMapper.readTree(mockMvc.perform(
                        get("/api/v1/operator/{operatorId}/trips/{tripId}/inventory/{inventoryId}",
                                operatorId, trip.tripId(), trip.inventoryId())
                                .with(bearer(admin.accessToken())))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());

        assertThat(inventoryAfter.get("layoutSeatId").asText())
                .isEqualTo(inventoryBefore.get("layoutSeatId").asText());
        assertThat(inventoryAfter.get("seatLayoutId").asText())
                .isEqualTo(inventoryBefore.get("seatLayoutId").asText());
        assertThat(inventoryAfter.get("seatNumber").asText())
                .isEqualTo(inventoryBefore.get("seatNumber").asText());

        mockMvc.perform(post("/api/v1/operator/{operatorId}/trips/{tripId}/inventory/{inventoryId}/block",
                        operatorId, trip.tripId(), trip.inventoryId())
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"still works\"}"))
                .andExpect(status().isOk());
        assertThat(tripSeatInventoryRepository.findById(trip.inventoryId()).orElseThrow().getPhysicalStatus())
                .isEqualTo(TripSeatInventoryStatus.BLOCKED);
    }

    @Test
    void concurrentDemotionInvalidatesStaleLayoutMutation() throws Exception {
        IssuedOperatorMember adminA = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_ADMIN, List.of("OPERATOR_ADMIN"));
        UUID operatorId = adminA.operator().getId();
        IssuedUser adminBUser = tokens.issueCustomer();
        tokens.attachMembership(adminA.operator(), adminBUser.user(), RoleCode.OPERATOR_ADMIN);
        IssuedUser adminBToken = tokens.issueToken(adminBUser.user(), List.of("CUSTOMER"));

        UUID layoutId = createDraftLayout(adminA, "Toctou", 1);

        CountDownLatch authorized = new CountDownLatch(1);
        CountDownLatch demoted = new CountDownLatch(1);
        AtomicInteger barrierHits = new AtomicInteger();
        ReflectionTestUtils.setField(
                operatorSeatLayoutAdminService,
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
                            patch("/api/v1/operator/{operatorId}/seat-layouts/{layoutId}",
                                    operatorId, layoutId)
                                    .with(bearer(adminA.accessToken()))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"name\":\"Denied\"}"))
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
            assertThat(seatLayoutRepository.findByIdAndOperator_Id(layoutId, operatorId).orElseThrow().getName())
                    .isNotEqualTo("Denied");
        } finally {
            ReflectionTestUtils.setField(
                    operatorSeatLayoutAdminService, "afterAuthorizeBeforeLockForTests", null);
            executor.shutdownNow();
        }
    }

    private UUID createDraftLayout(IssuedOperatorMember admin, String name, int version) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/operator/{operatorId}/seat-layouts",
                        admin.operator().getId())
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(minimalLayoutBody(name, version)))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private void activateLayout(IssuedOperatorMember admin, UUID layoutId) throws Exception {
        mockMvc.perform(post("/api/v1/operator/{operatorId}/seat-layouts/{layoutId}/activate",
                        admin.operator().getId(), layoutId)
                        .with(bearer(admin.accessToken())))
                .andExpect(status().isOk());
    }

    private static String minimalLayoutBody(String name, int version) {
        return """
                {
                  "name":"%s",
                  "version":%d,
                  "deckCount":1,
                  "rowCount":1,
                  "columnCount":1,
                  "seats":[
                    {"seatNumber":"A1","deckNumber":1,"rowNumber":1,"columnNumber":1,"seatType":"SEATER"}
                  ]
                }
                """.formatted(name, version);
    }

    private UUID createBusType() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/admin/bus-types")
                        .with(bearer(platformAdminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"BT-%s\",\"displayName\":\"Type\"}".formatted(shortId())))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private record TripFixture(UUID tripId, UUID inventoryId) {
    }

    private TripFixture createScheduledTrip(IssuedOperatorMember admin, UUID layoutId) throws Exception {
        UUID operatorId = admin.operator().getId();
        UUID origin = createLocation("Telangana", "O-" + shortId());
        UUID via = createLocation("Telangana", "V-" + shortId());
        UUID destination = createLocation("Andhra Pradesh", "D-" + shortId());
        UUID routeId = createRoute(operatorId, origin, via, destination);
        UUID busTypeId = createBusType();
        UUID busId = createBusWithAdminToken(operatorId, busTypeId, layoutId, admin);

        MvcResult trip = mockMvc.perform(post("/api/v1/operator/{operatorId}/trips", operatorId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "busId":"%s",
                                  "routeId":"%s",
                                  "scheduledDepartureAt":"2029-06-01T10:00:00Z",
                                  "scheduledArrivalAt":"2029-06-01T18:00:00Z",
                                  "baseFare":500.00,
                                  "bookingOpensAt":"2029-05-01T10:00:00Z",
                                  "bookingClosesAt":"2029-06-01T09:00:00Z"
                                }
                                """.formatted(busId, routeId)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode tripJson = objectMapper.readTree(trip.getResponse().getContentAsString());
        UUID tripId = UUID.fromString(tripJson.get("id").asText());
        UUID inventoryId = UUID.fromString(tripJson.get("seatInventory").get(0).get("id").asText());
        mockMvc.perform(post("/api/v1/operator/{operatorId}/trips/{tripId}/schedule", operatorId, tripId)
                        .with(bearer(admin.accessToken())))
                .andExpect(status().isOk());
        return new TripFixture(tripId, inventoryId);
    }

    private UUID createBusWithAdminToken(
            UUID operatorId, UUID busTypeId, UUID layoutId, IssuedOperatorMember admin) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/operator/{operatorId}/buses", operatorId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "busTypeId":"%s",
                                  "seatLayoutId":"%s",
                                  "registrationNumber":"TS09%s"
                                }
                                """.formatted(busTypeId, layoutId, shortId().substring(0, 4))))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
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
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private UUID createRoute(UUID operatorId, UUID origin, UUID via, UUID destination) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/admin/routes")
                        .with(bearer(platformAdminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "operatorId":"%s",
                                  "code":"R-%s",
                                  "name":"Route",
                                  "sourceLocationId":"%s",
                                  "destinationLocationId":"%s",
                                  "stops":[
                                    {"locationId":"%s","sequenceNumber":1,"stopKind":"SOURCE",
                                     "departureOffsetMinutes":0,"distanceKm":0},
                                    {"locationId":"%s","sequenceNumber":2,"stopKind":"INTERMEDIATE",
                                     "arrivalOffsetMinutes":90,"departureOffsetMinutes":100,"distanceKm":140},
                                    {"locationId":"%s","sequenceNumber":3,"stopKind":"DESTINATION",
                                     "arrivalOffsetMinutes":270,"distanceKm":340}
                                  ]
                                }
                                """.formatted(operatorId, shortId(), origin, destination, origin, via, destination)))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
