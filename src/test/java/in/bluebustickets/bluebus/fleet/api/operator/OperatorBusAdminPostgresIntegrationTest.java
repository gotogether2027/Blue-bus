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

import in.bluebustickets.bluebus.fleet.application.OperatorBusAdminService;
import in.bluebustickets.bluebus.fleet.repository.BusRepository;
import in.bluebustickets.bluebus.foundation.security.TestAccessTokenFactory;
import in.bluebustickets.bluebus.foundation.security.TestAccessTokenFactory.IssuedOperatorMember;
import in.bluebustickets.bluebus.foundation.security.TestAccessTokenFactory.IssuedUser;
import in.bluebustickets.bluebus.identity.domain.RoleCode;
import in.bluebustickets.bluebus.identity.domain.UserStatus;
import in.bluebustickets.bluebus.operator.repository.OperatorUserRepository;
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
class OperatorBusAdminPostgresIntegrationTest {

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
    @Autowired private OperatorBusAdminService operatorBusAdminService;
    @Autowired private BusRepository busRepository;
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
        UUID busTypeId = createBusType("OP_AC_" + shortId(), "Operator AC");
        UUID layoutId = createPublishedLayout(operatorId, "Op Layout", 1);

        mockMvc.perform(post("/api/v1/operator/{operatorId}/buses", operatorId)
                        .with(bearer(staffToken.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(busTypeId, layoutId, "TS09ST" + shortId().substring(0, 4))))
                .andExpect(status().isForbidden());

        MvcResult created = mockMvc.perform(post("/api/v1/operator/{operatorId}/buses", operatorId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(busTypeId, layoutId, "TS09AD" + shortId().substring(0, 4), "Fleet One")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.operatorId").value(operatorId.toString()))
                .andExpect(jsonPath("$.displayName").value("Fleet One"))
                .andReturn();
        UUID busId = idOf(created);

        mockMvc.perform(get("/api/v1/operator/{operatorId}/buses", operatorId)
                        .with(bearer(staffToken.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='%s')]".formatted(busId)).exists());

        mockMvc.perform(patch("/api/v1/operator/{operatorId}/buses/{busId}", operatorId, busId)
                        .with(bearer(staffToken.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Nope\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/operator/{operatorId}/buses/{busId}/deactivate", operatorId, busId)
                        .with(bearer(staffToken.accessToken())))
                .andExpect(status().isForbidden());
    }

    @Test
    void operatorMembersCanListOnlyActiveBusTypeReferenceData() throws Exception {
        IssuedOperatorMember admin = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_ADMIN, List.of("OPERATOR_ADMIN"));
        IssuedUser staffUser = tokens.issueCustomer();
        tokens.attachMembership(admin.operator(), staffUser.user(), RoleCode.OPERATOR_STAFF);
        IssuedUser staffToken = tokens.issueToken(staffUser.user(), List.of("CUSTOMER"));
        IssuedOperatorMember other = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_ADMIN, List.of("OPERATOR_ADMIN"));

        String activeCode = "REF_" + shortId();
        UUID activeTypeId = createBusType(activeCode, "Reference Type");
        UUID inactiveTypeId = createBusType("REF_OFF_" + shortId(), "Inactive Reference Type");
        mockMvc.perform(post("/api/v1/admin/bus-types/{id}/deactivate", inactiveTypeId)
                        .with(bearer(platformAdminToken)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/operator/{operatorId}/bus-types", admin.operator().getId())
                        .with(bearer(admin.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='%s' && @.code=='%s')]"
                        .formatted(activeTypeId, activeCode)).exists())
                .andExpect(jsonPath("$[?(@.id=='%s' && @.displayName=='Reference Type')]"
                        .formatted(activeTypeId)).exists())
                .andExpect(jsonPath("$[?(@.id=='%s' && @.active==true)]"
                        .formatted(activeTypeId)).exists())
                .andExpect(jsonPath("$[?(@.id=='%s')]".formatted(inactiveTypeId)).isEmpty());

        mockMvc.perform(get("/api/v1/operator/{operatorId}/bus-types", admin.operator().getId())
                        .with(bearer(staffToken.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='%s')]".formatted(activeTypeId)).exists());

        mockMvc.perform(get("/api/v1/operator/{operatorId}/bus-types", admin.operator().getId())
                        .with(bearer(other.accessToken())))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/operator/{operatorId}/bus-types", admin.operator().getId())
                        .with(anonymous()))
                .andExpect(status().isUnauthorized());
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
        UUID busTypeId = createBusType("AUTH_T_" + shortId(), "Auth Type");
        UUID layoutId = createPublishedLayout(operatorId, "Auth Layout", 1);
        UUID busId = createOperatorBus(admin, busTypeId, layoutId, "TS09AU" + shortId().substring(0, 4));

        mockMvc.perform(get("/api/v1/operator/{operatorId}/buses", operatorId).with(anonymous()))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/operator/{operatorId}/buses", operatorId)
                        .with(bearer(inactive.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(busTypeId, layoutId, "TS09IN" + shortId().substring(0, 4))))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/operator/{operatorId}/buses/{busId}", other.operator().getId(), busId)
                        .with(bearer(admin.accessToken())))
                .andExpect(status().isNotFound());

        UUID otherBusTypeId = createBusType("OTH_" + shortId(), "Other");
        UUID otherLayoutId = createPublishedLayout(other.operator().getId(), "Other L", 1);
        UUID otherBusId = createOperatorBus(other, otherBusTypeId, otherLayoutId, "TS09OT" + shortId().substring(0, 4));

        mockMvc.perform(patch("/api/v1/operator/{operatorId}/buses/{busId}",
                        admin.operator().getId(), otherBusId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"x\"}"))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/v1/operator/{operatorId}/buses", operatorId)
                        .with(bearer(platformAdmin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(busTypeId, layoutId, "TS09PA" + shortId().substring(0, 4))))
                .andExpect(status().isNotFound());

        var membership = operatorUserRepository
                .findByOperatorIdAndUserId(operatorId, admin.user().getId())
                .orElseThrow();
        membership.deactivate();
        operatorUserRepository.saveAndFlush(membership);

        mockMvc.perform(post("/api/v1/operator/{operatorId}/buses/{busId}/maintenance", operatorId, busId)
                        .with(bearer(admin.accessToken())))
                .andExpect(status().isNotFound());
    }

    @Test
    void createValidatesTypeLayoutAndRegistration() throws Exception {
        IssuedOperatorMember admin = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_ADMIN, List.of("OPERATOR_ADMIN"));
        IssuedOperatorMember other = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_ADMIN, List.of("OPERATOR_ADMIN"));
        UUID operatorId = admin.operator().getId();
        UUID busTypeId = createBusType("CRT_" + shortId(), "Create Type");
        UUID publishedLayoutId = createPublishedLayout(operatorId, "Pub Layout", 1);
        UUID draftLayoutId = createDraftLayout(operatorId, "Draft Layout", 1);
        UUID archivedLayoutId = createPublishedLayout(operatorId, "Arch Layout", 1);
        mockMvc.perform(post("/api/v1/admin/seat-layouts/{id}/deactivate", archivedLayoutId)
                        .with(bearer(platformAdminToken)))
                .andExpect(status().isOk());
        UUID foreignLayoutId = createPublishedLayout(other.operator().getId(), "Foreign", 1);
        UUID inactiveTypeId = createBusType("INACT_" + shortId(), "Inactive Type");
        mockMvc.perform(post("/api/v1/admin/bus-types/{id}/deactivate", inactiveTypeId)
                        .with(bearer(platformAdminToken)))
                .andExpect(status().isOk());

        String registration = "TS09CR" + shortId().substring(0, 4);
        mockMvc.perform(post("/api/v1/operator/{operatorId}/buses", operatorId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(busTypeId, publishedLayoutId, registration)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        mockMvc.perform(post("/api/v1/operator/{operatorId}/buses", operatorId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(busTypeId, publishedLayoutId, registration.toLowerCase())))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/v1/operator/{operatorId}/buses", operatorId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(UUID.randomUUID(), publishedLayoutId, "TS09MX" + shortId().substring(0, 4))))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/v1/operator/{operatorId}/buses", operatorId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(inactiveTypeId, publishedLayoutId, "TS09IY" + shortId().substring(0, 4))))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/v1/operator/{operatorId}/buses", operatorId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(busTypeId, foreignLayoutId, "TS09FX" + shortId().substring(0, 4))))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/v1/operator/{operatorId}/buses", operatorId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(busTypeId, draftLayoutId, "TS09DR" + shortId().substring(0, 4))))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/v1/operator/{operatorId}/buses", operatorId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(busTypeId, archivedLayoutId, "TS09AR" + shortId().substring(0, 4))))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/v1/operator/{operatorId}/buses", operatorId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"busTypeId":"%s","seatLayoutId":"%s","registrationNumber":"TS09OP%s","operatorId":"%s"}
                                """.formatted(busTypeId, publishedLayoutId, shortId().substring(0, 4), UUID.randomUUID())))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/operator/{operatorId}/buses", operatorId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"busTypeId":"%s","seatLayoutId":"%s","registrationNumber":"TS09UK%s","extra":true}
                                """.formatted(busTypeId, publishedLayoutId, shortId().substring(0, 4))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void concurrentCaseInsensitiveRegistrationCreatesExactlyOneRow() throws Exception {
        IssuedOperatorMember adminA = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_ADMIN, List.of("OPERATOR_ADMIN"));
        IssuedOperatorMember adminB = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_ADMIN, List.of("OPERATOR_ADMIN"));
        UUID typeA = createBusType("CIA_" + shortId(), "CI A");
        UUID typeB = createBusType("CIB_" + shortId(), "CI B");
        UUID layoutA = createPublishedLayout(adminA.operator().getId(), "CI A L", 1);
        UUID layoutB = createPublishedLayout(adminB.operator().getId(), "CI B L", 1);
        String upper = "TS09CI" + shortId().substring(0, 4);
        String lower = upper.toLowerCase();

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        AtomicInteger created = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        try {
            List<Future<?>> futures = new ArrayList<>();
            futures.add(executor.submit(() -> {
                ready.countDown();
                start.await();
                MvcResult result = mockMvc.perform(post("/api/v1/operator/{operatorId}/buses", adminA.operator().getId())
                                .with(bearer(adminA.accessToken()))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(createBody(typeA, layoutA, upper)))
                        .andReturn();
                int statusCode = result.getResponse().getStatus();
                if (statusCode == 201) {
                    created.incrementAndGet();
                } else if (statusCode == 409) {
                    conflicts.incrementAndGet();
                    assertThat(result.getResponse().getContentAsString())
                            .contains("Bus registration number already exists.");
                }
                return null;
            }));
            futures.add(executor.submit(() -> {
                ready.countDown();
                start.await();
                MvcResult result = mockMvc.perform(post("/api/v1/operator/{operatorId}/buses", adminB.operator().getId())
                                .with(bearer(adminB.accessToken()))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(createBody(typeB, layoutB, lower)))
                        .andReturn();
                int statusCode = result.getResponse().getStatus();
                if (statusCode == 201) {
                    created.incrementAndGet();
                } else if (statusCode == 409) {
                    conflicts.incrementAndGet();
                    assertThat(result.getResponse().getContentAsString())
                            .contains("Bus registration number already exists.");
                }
                return null;
            }));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(created.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(1);
        Integer rows = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM buses WHERE lower(registration_number) = lower(?)
                """, Integer.class, upper);
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void patchAndLifecycleRules() throws Exception {
        IssuedOperatorMember admin = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_ADMIN, List.of("OPERATOR_ADMIN"));
        UUID operatorId = admin.operator().getId();
        UUID busTypeId = createBusType("PAT_" + shortId(), "Patch Type");
        UUID otherTypeId = createBusType("PAT2_" + shortId(), "Patch Type 2");
        UUID layoutId = createPublishedLayout(operatorId, "Patch L1", 1);
        UUID otherLayoutId = createPublishedLayout(operatorId, "Patch L2", 1);
        UUID busId = createOperatorBus(admin, busTypeId, layoutId, "TS09PT" + shortId().substring(0, 4));

        mockMvc.perform(patch("/api/v1/operator/{operatorId}/buses/{busId}", operatorId, busId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Renamed Bus\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Renamed Bus"))
                .andExpect(jsonPath("$.busTypeId").value(busTypeId.toString()));

        mockMvc.perform(patch("/api/v1/operator/{operatorId}/buses/{busId}", operatorId, busId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"busTypeId\":\"%s\"}".formatted(otherTypeId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.busTypeId").value(otherTypeId.toString()));

        mockMvc.perform(patch("/api/v1/operator/{operatorId}/buses/{busId}", operatorId, busId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seatLayoutId\":\"%s\"}".formatted(otherLayoutId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seatLayoutId").value(otherLayoutId.toString()));

        mockMvc.perform(patch("/api/v1/operator/{operatorId}/buses/{busId}", operatorId, busId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(patch("/api/v1/operator/{operatorId}/buses/{busId}", operatorId, busId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"x\",\"registrationNumber\":\"HACKED\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(patch("/api/v1/operator/{operatorId}/buses/{busId}", operatorId, busId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"x\",\"operatorId\":\"%s\"}".formatted(UUID.randomUUID())))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/operator/{operatorId}/buses/{busId}/deactivate", operatorId, busId)
                        .with(bearer(admin.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INACTIVE"));
        mockMvc.perform(post("/api/v1/operator/{operatorId}/buses/{busId}/deactivate", operatorId, busId)
                        .with(bearer(admin.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INACTIVE"));

        mockMvc.perform(post("/api/v1/operator/{operatorId}/buses/{busId}/maintenance", operatorId, busId)
                        .with(bearer(admin.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("MAINTENANCE"));
        mockMvc.perform(post("/api/v1/operator/{operatorId}/buses/{busId}/maintenance", operatorId, busId)
                        .with(bearer(admin.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("MAINTENANCE"));

        mockMvc.perform(post("/api/v1/operator/{operatorId}/buses/{busId}/activate", operatorId, busId)
                        .with(bearer(admin.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        mockMvc.perform(post("/api/v1/operator/{operatorId}/buses/{busId}/activate", operatorId, busId)
                        .with(bearer(admin.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void seatLayoutChangeBlockedWhenAnyTripExistsAndLifecycleLeavesTrips() throws Exception {
        IssuedOperatorMember admin = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_ADMIN, List.of("OPERATOR_ADMIN"));
        UUID operatorId = admin.operator().getId();
        UUID busTypeId = createBusType("TRP_" + shortId(), "Trip Type");
        UUID layoutId = createPublishedLayout(operatorId, "Trip L1", 1);
        UUID otherLayoutId = createPublishedLayout(operatorId, "Trip L2", 1);
        UUID busId = createOperatorBus(admin, busTypeId, layoutId, "TS09TR" + shortId().substring(0, 4));
        UUID routeId = createActiveRoute(operatorId);

        UUID tripId = createAdminTrip(busId, routeId);
        assertThat(tripRepository.existsByBus_Id(busId)).isTrue();

        mockMvc.perform(patch("/api/v1/operator/{operatorId}/buses/{busId}", operatorId, busId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seatLayoutId\":\"%s\"}".formatted(otherLayoutId)))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/v1/operator/{operatorId}/buses/{busId}/deactivate", operatorId, busId)
                        .with(bearer(admin.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INACTIVE"));

        JsonNode trip = objectMapper.readTree(mockMvc.perform(get("/api/v1/admin/trips/{id}", tripId)
                        .with(bearer(platformAdminToken)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(trip.get("busId").asText()).isEqualTo(busId.toString());
        assertThat(trip.get("seatLayoutId").asText()).isEqualTo(layoutId.toString());
        assertThat(trip.get("status").asText()).isNotEqualTo("CANCELLED");

        mockMvc.perform(post("/api/v1/admin/trips")
                        .with(bearer(platformAdminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "busId":"%s",
                                  "routeId":"%s",
                                  "scheduledDepartureAt":"2027-06-01T10:00:00Z",
                                  "scheduledArrivalAt":"2027-06-01T18:00:00Z",
                                  "baseFare":500.00,
                                  "bookingOpensAt":"2027-05-01T10:00:00Z",
                                  "bookingClosesAt":"2027-06-01T09:00:00Z"
                                }
                                """.formatted(busId, routeId)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void concurrentDemotionInvalidatesStaleBusMutation() throws Exception {
        IssuedOperatorMember adminA = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_ADMIN, List.of("OPERATOR_ADMIN"));
        UUID operatorId = adminA.operator().getId();
        IssuedUser adminBUser = tokens.issueCustomer();
        tokens.attachMembership(adminA.operator(), adminBUser.user(), RoleCode.OPERATOR_ADMIN);
        IssuedUser adminBToken = tokens.issueToken(adminBUser.user(), List.of("CUSTOMER"));

        UUID busTypeId = createBusType("TOC_" + shortId(), "Toc Type");
        UUID layoutId = createPublishedLayout(operatorId, "Toc L", 1);
        UUID busId = createOperatorBus(adminA, busTypeId, layoutId, "TS09TO" + shortId().substring(0, 4));

        CountDownLatch authorized = new CountDownLatch(1);
        CountDownLatch demoted = new CountDownLatch(1);
        AtomicInteger barrierHits = new AtomicInteger();
        ReflectionTestUtils.setField(
                operatorBusAdminService,
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
                            patch("/api/v1/operator/{operatorId}/buses/{busId}", operatorId, busId)
                                    .with(bearer(adminA.accessToken()))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"displayName\":\"Should Fail\"}"))
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
            assertThat(busRepository.findByIdAndOperator_Id(busId, operatorId).orElseThrow().getDisplayName())
                    .isNotEqualTo("Should Fail");
        } finally {
            ReflectionTestUtils.setField(operatorBusAdminService, "afterAuthorizeBeforeLockForTests", null);
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentLayoutChangeAndTripCreatePreserveBusLayoutFk() throws Exception {
        IssuedOperatorMember admin = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_ADMIN, List.of("OPERATOR_ADMIN"));
        UUID operatorId = admin.operator().getId();
        UUID busTypeId = createBusType("LCK_" + shortId(), "Lock Type");
        UUID layoutId = createPublishedLayout(operatorId, "Lock L1", 1);
        UUID otherLayoutId = createPublishedLayout(operatorId, "Lock L2", 1);
        UUID busId = createOperatorBus(admin, busTypeId, layoutId, "TS09LK" + shortId().substring(0, 4));
        UUID routeId = createActiveRoute(operatorId);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        AtomicInteger layoutOk = new AtomicInteger();
        AtomicInteger layoutConflict = new AtomicInteger();
        AtomicInteger tripOk = new AtomicInteger();
        AtomicInteger tripFail = new AtomicInteger();
        try {
            List<Future<?>> futures = new ArrayList<>();
            futures.add(executor.submit(() -> {
                ready.countDown();
                start.await();
                int statusCode = mockMvc.perform(patch("/api/v1/operator/{operatorId}/buses/{busId}",
                                operatorId, busId)
                                .with(bearer(admin.accessToken()))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"seatLayoutId\":\"%s\"}".formatted(otherLayoutId)))
                        .andReturn()
                        .getResponse()
                        .getStatus();
                if (statusCode == 200) {
                    layoutOk.incrementAndGet();
                } else if (statusCode == 409) {
                    layoutConflict.incrementAndGet();
                }
                return null;
            }));
            futures.add(executor.submit(() -> {
                ready.countDown();
                start.await();
                int statusCode = mockMvc.perform(post("/api/v1/admin/trips")
                                .with(bearer(platformAdminToken))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "busId":"%s",
                                          "routeId":"%s",
                                          "scheduledDepartureAt":"2027-07-01T10:00:00Z",
                                          "scheduledArrivalAt":"2027-07-01T18:00:00Z",
                                          "baseFare":500.00,
                                          "bookingOpensAt":"2027-06-01T10:00:00Z",
                                          "bookingClosesAt":"2027-07-01T09:00:00Z"
                                        }
                                        """.formatted(busId, routeId)))
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
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> future : futures) {
                future.get(45, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(layoutOk.get() + layoutConflict.get()).isGreaterThanOrEqualTo(1);
        Integer mismatches = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM trips t
                JOIN buses b ON b.id = t.bus_id
                WHERE t.bus_id = ?
                  AND t.seat_layout_id <> b.seat_layout_id
                """, Integer.class, busId);
        assertThat(mismatches).isZero();
    }

    @Test
    void unrelatedIntegrityFailureIsNotReportedAsRegistrationConflict() throws Exception {
        IssuedOperatorMember admin = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_ADMIN, List.of("OPERATOR_ADMIN"));
        UUID operatorId = admin.operator().getId();
        UUID busTypeId = createBusType("FK_" + shortId(), "Fk Type");
        UUID layoutId = createPublishedLayout(operatorId, "Fk Layout", 1);
        String registration = "TS09FK" + shortId().substring(0, 4);

        ReflectionTestUtils.setField(
                operatorBusAdminService,
                "afterValidationBeforeSaveForTests",
                (Runnable) () -> {
                    jdbcTemplate.update("DELETE FROM seats WHERE seat_layout_id = ?", layoutId);
                    jdbcTemplate.update("DELETE FROM seat_layouts WHERE id = ?", layoutId);
                });
        try {
            MvcResult result = mockMvc.perform(post("/api/v1/operator/{operatorId}/buses", operatorId)
                            .with(bearer(admin.accessToken()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createBody(busTypeId, layoutId, registration)))
                    .andReturn();

            assertThat(result.getResponse().getStatus()).isEqualTo(409);
            assertThat(result.getResponse().getContentAsString())
                    .doesNotContain("Bus registration number already exists.");
            assertThat(result.getResponse().getContentAsString())
                    .contains("Request conflicts with the current state.");
            Integer rows = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM buses WHERE lower(registration_number) = lower(?)
                    """, Integer.class, registration);
            assertThat(rows).isZero();
        } finally {
            ReflectionTestUtils.setField(
                    operatorBusAdminService, "afterValidationBeforeSaveForTests", null);
        }
    }

    private UUID createOperatorBus(
            IssuedOperatorMember admin, UUID busTypeId, UUID layoutId, String registration) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/operator/{operatorId}/buses", admin.operator().getId())
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(busTypeId, layoutId, registration)))
                .andExpect(status().isCreated())
                .andReturn();
        return idOf(result);
    }

    private String createBody(UUID busTypeId, UUID layoutId, String registration) {
        return createBody(busTypeId, layoutId, registration, null);
    }

    private String createBody(UUID busTypeId, UUID layoutId, String registration, String displayName) {
        if (displayName == null) {
            return """
                    {"busTypeId":"%s","seatLayoutId":"%s","registrationNumber":"%s"}
                    """.formatted(busTypeId, layoutId, registration);
        }
        return """
                {"busTypeId":"%s","seatLayoutId":"%s","registrationNumber":"%s","displayName":"%s"}
                """.formatted(busTypeId, layoutId, registration, displayName);
    }

    private UUID createBusType(String code, String displayName) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/admin/bus-types")
                        .with(bearer(platformAdminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"%s","displayName":"%s"}
                                """.formatted(code, displayName)))
                .andExpect(status().isCreated())
                .andReturn();
        return idOf(result);
    }

    private UUID createDraftLayout(UUID operatorId, String name, int version) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/admin/seat-layouts")
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
        return idOf(result);
    }

    private UUID createPublishedLayout(UUID operatorId, String name, int version) throws Exception {
        UUID layoutId = createDraftLayout(operatorId, name, version);
        mockMvc.perform(post("/api/v1/admin/seat-layouts/{id}/activate", layoutId)
                        .with(bearer(platformAdminToken)))
                .andExpect(status().isOk());
        return layoutId;
    }

    private UUID createActiveRoute(UUID operatorId) throws Exception {
        UUID origin = createLocation("Telangana", "Hyd-" + shortId());
        UUID via = createLocation("Telangana", "Via-" + shortId());
        UUID mid = createLocation("Andhra Pradesh", "Mid-" + shortId());
        UUID destination = createLocation("Andhra Pradesh", "Vja-" + shortId());
        String code = "R" + shortId();
        MvcResult result = mockMvc.perform(post("/api/v1/admin/routes")
                        .with(bearer(platformAdminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "operatorId":"%s",
                                  "code":"%s",
                                  "name":"Hyderabad to Vijayawada %s",
                                  "sourceLocationId":"%s",
                                  "destinationLocationId":"%s",
                                  "stops":[
                                    {
                                      "locationId":"%s","sequenceNumber":1,"stopKind":"SOURCE","departureOffsetMinutes":0,"distanceKm":0,
                                      "points":[{"name":"Origin Boarding","pointType":"BOARDING"}]
                                    },
                                    {
                                      "locationId":"%s","sequenceNumber":2,"stopKind":"INTERMEDIATE","arrivalOffsetMinutes":90,"departureOffsetMinutes":100,"distanceKm":140.5,
                                      "points":[{"name":"Via Stand","pointType":"BOTH"}]
                                    },
                                    {
                                      "locationId":"%s","sequenceNumber":3,"stopKind":"INTERMEDIATE","arrivalOffsetMinutes":180,"departureOffsetMinutes":190,"distanceKm":260
                                    },
                                    {
                                      "locationId":"%s","sequenceNumber":4,"stopKind":"DESTINATION","arrivalOffsetMinutes":270,"distanceKm":340,
                                      "points":[{"name":"Dest Drop","pointType":"DROPPING"}]
                                    }
                                  ]
                                }
                                """.formatted(
                                operatorId, code, shortId(), origin, destination,
                                origin, via, mid, destination)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID routeId = idOf(result);
        mockMvc.perform(post("/api/v1/admin/routes/{id}/activate", routeId)
                        .with(bearer(platformAdminToken)))
                .andExpect(status().isOk());
        return routeId;
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

    private UUID createAdminTrip(UUID busId, UUID routeId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/admin/trips")
                        .with(bearer(platformAdminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
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
                                Instant.parse("2027-05-01T10:00:00Z"),
                                Instant.parse("2027-05-01T18:00:00Z"),
                                Instant.parse("2027-04-01T10:00:00Z"),
                                Instant.parse("2027-05-01T09:00:00Z"))))
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
