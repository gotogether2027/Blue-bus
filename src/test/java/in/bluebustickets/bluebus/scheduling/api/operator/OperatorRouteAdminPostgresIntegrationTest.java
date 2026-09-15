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

import in.bluebustickets.bluebus.foundation.security.TestAccessTokenFactory;
import in.bluebustickets.bluebus.foundation.security.TestAccessTokenFactory.IssuedOperatorMember;
import in.bluebustickets.bluebus.foundation.security.TestAccessTokenFactory.IssuedUser;
import in.bluebustickets.bluebus.identity.domain.RoleCode;
import in.bluebustickets.bluebus.identity.domain.UserStatus;
import in.bluebustickets.bluebus.operator.repository.OperatorUserRepository;
import in.bluebustickets.bluebus.scheduling.application.OperatorRouteAdminService;
import in.bluebustickets.bluebus.scheduling.repository.RouteRepository;
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
class OperatorRouteAdminPostgresIntegrationTest {

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
    @Autowired private OperatorRouteAdminService operatorRouteAdminService;
    @Autowired private RouteRepository routeRepository;
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
        UUID source = createLocation("Telangana", "Hyd-" + shortId());
        UUID destination = createLocation("Andhra Pradesh", "Vja-" + shortId());
        String code = "STF-" + shortId();

        mockMvc.perform(post("/api/v1/operator/{operatorId}/routes", operatorId)
                        .with(bearer(staffToken.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRouteBody(code, source, destination)))
                .andExpect(status().isForbidden());

        MvcResult created = mockMvc.perform(post("/api/v1/operator/{operatorId}/routes", operatorId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRouteBody(code, source, destination, "Staff Visible Route")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.operatorId").value(operatorId.toString()))
                .andReturn();
        UUID routeId = idOf(created);

        mockMvc.perform(get("/api/v1/operator/{operatorId}/routes", operatorId)
                        .with(bearer(staffToken.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='%s')]".formatted(routeId)).exists());

        mockMvc.perform(patch("/api/v1/operator/{operatorId}/routes/{routeId}", operatorId, routeId)
                        .with(bearer(staffToken.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Nope\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/operator/{operatorId}/routes/{routeId}/deactivate", operatorId, routeId)
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
        UUID source = createLocation("Telangana", "AuthS-" + shortId());
        UUID destination = createLocation("Andhra Pradesh", "AuthD-" + shortId());
        UUID routeId = createOperatorRoute(admin, "AUTH-" + shortId(), source, destination);

        mockMvc.perform(get("/api/v1/operator/{operatorId}/routes", operatorId).with(anonymous()))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/operator/{operatorId}/routes", operatorId)
                        .with(bearer(inactive.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRouteBody("IN-" + shortId(), source, destination)))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/operator/{operatorId}/routes/{routeId}", other.operator().getId(), routeId)
                        .with(bearer(admin.accessToken())))
                .andExpect(status().isNotFound());

        UUID otherSource = createLocation("Karnataka", "OthS-" + shortId());
        UUID otherDest = createLocation("Tamil Nadu", "OthD-" + shortId());
        UUID otherRouteId = createOperatorRoute(other, "OTH-" + shortId(), otherSource, otherDest);

        mockMvc.perform(patch("/api/v1/operator/{operatorId}/routes/{routeId}",
                        admin.operator().getId(), otherRouteId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\"}"))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/v1/operator/{operatorId}/routes", operatorId)
                        .with(bearer(platformAdmin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRouteBody("PA-" + shortId(), source, destination)))
                .andExpect(status().isNotFound());

        var membership = operatorUserRepository
                .findByOperatorIdAndUserId(operatorId, admin.user().getId())
                .orElseThrow();
        membership.deactivate();
        operatorUserRepository.saveAndFlush(membership);

        mockMvc.perform(post("/api/v1/operator/{operatorId}/routes/{routeId}/deactivate", operatorId, routeId)
                        .with(bearer(admin.accessToken())))
                .andExpect(status().isNotFound());
    }

    @Test
    void createSupportsNestedStopsPointsZeroStopsAndValidation() throws Exception {
        IssuedOperatorMember admin = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_ADMIN, List.of("OPERATOR_ADMIN"));
        UUID operatorId = admin.operator().getId();
        UUID source = createLocation("Telangana", "CrtS-" + shortId());
        UUID via = createLocation("Telangana", "CrtV-" + shortId());
        UUID destination = createLocation("Andhra Pradesh", "CrtD-" + shortId());

        mockMvc.perform(post("/api/v1/operator/{operatorId}/routes", operatorId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRouteBody("ZERO-" + shortId(), source, destination)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.stops").isEmpty())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        String nestedCode = "NEST-" + shortId();
        MvcResult nested = mockMvc.perform(post("/api/v1/operator/{operatorId}/routes", operatorId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "code":"%s",
                                  "name":"Nested Route",
                                  "sourceLocationId":"%s",
                                  "destinationLocationId":"%s",
                                  "stops":[
                                    {
                                      "locationId":"%s","sequenceNumber":1,"stopKind":"SOURCE",
                                      "departureOffsetMinutes":0,"distanceKm":0,
                                      "points":[{"name":"Miyapur","pointType":"BOARDING","latitude":17.5,"longitude":78.3}]
                                    },
                                    {
                                      "locationId":"%s","sequenceNumber":2,"stopKind":"INTERMEDIATE",
                                      "arrivalOffsetMinutes":90,"departureOffsetMinutes":100,"distanceKm":140
                                    },
                                    {
                                      "locationId":"%s","sequenceNumber":3,"stopKind":"DESTINATION",
                                      "arrivalOffsetMinutes":270,"distanceKm":340,
                                      "points":[{"name":"Benz Circle","pointType":"DROPPING"}]
                                    }
                                  ]
                                }
                                """.formatted(nestedCode, source, destination, source, via, destination)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.stops.length()").value(3))
                .andExpect(jsonPath("$.stops[0].points[0].name").value("Miyapur"))
                .andReturn();
        UUID nestedRouteId = idOf(nested);

        mockMvc.perform(get("/api/v1/operator/{operatorId}/routes/{routeId}", operatorId, nestedRouteId)
                        .with(bearer(admin.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stops[2].stopKind").value("DESTINATION"));

        mockMvc.perform(post("/api/v1/operator/{operatorId}/routes", operatorId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRouteBody("SAME-" + shortId(), source, source)))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/operator/{operatorId}/routes", operatorId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "code":"DUPSEQ-%s",
                                  "name":"Dup Seq",
                                  "sourceLocationId":"%s",
                                  "destinationLocationId":"%s",
                                  "stops":[
                                    {"locationId":"%s","sequenceNumber":1,"stopKind":"SOURCE","departureOffsetMinutes":0,"distanceKm":0},
                                    {"locationId":"%s","sequenceNumber":1,"stopKind":"DESTINATION","arrivalOffsetMinutes":10,"distanceKm":10}
                                  ]
                                }
                                """.formatted(shortId(), source, destination, source, destination)))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/v1/operator/{operatorId}/routes", operatorId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "code":"UNK-%s",
                                  "name":"Unknown Field",
                                  "sourceLocationId":"%s",
                                  "destinationLocationId":"%s",
                                  "operatorId":"%s"
                                }
                                """.formatted(shortId(), source, destination, operatorId)))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/operator/{operatorId}/routes", operatorId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "code":"UNKSTOP-%s",
                                  "name":"Unknown Nested Stop Field",
                                  "sourceLocationId":"%s",
                                  "destinationLocationId":"%s",
                                  "stops":[
                                    {
                                      "locationId":"%s","sequenceNumber":1,"stopKind":"SOURCE",
                                      "departureOffsetMinutes":0,"distanceKm":0,
                                      "routeId":"%s"
                                    }
                                  ]
                                }
                                """.formatted(shortId(), source, destination, source, UUID.randomUUID())))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/operator/{operatorId}/routes", operatorId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "code":"UNKPT-%s",
                                  "name":"Unknown Nested Point Field",
                                  "sourceLocationId":"%s",
                                  "destinationLocationId":"%s",
                                  "stops":[
                                    {
                                      "locationId":"%s","sequenceNumber":1,"stopKind":"SOURCE",
                                      "departureOffsetMinutes":0,"distanceKm":0,
                                      "points":[{"name":"Miyapur","pointType":"BOARDING","extra":"nope"}]
                                    }
                                  ]
                                }
                                """.formatted(shortId(), source, destination, source)))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/operator/{operatorId}/routes", operatorId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRouteBody("MISSLOC-" + shortId(), UUID.randomUUID(), destination)))
                .andExpect(status().isNotFound());

        String rollbackCode = "RB-" + shortId();
        mockMvc.perform(post("/api/v1/operator/{operatorId}/routes", operatorId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "code":"%s",
                                  "name":"Rollback Nested",
                                  "sourceLocationId":"%s",
                                  "destinationLocationId":"%s",
                                  "stops":[
                                    {
                                      "locationId":"%s","sequenceNumber":1,"stopKind":"SOURCE",
                                      "departureOffsetMinutes":0,"distanceKm":0,
                                      "points":[
                                        {"name":"Dup","pointType":"BOARDING"},
                                        {"name":"Dup","pointType":"DROPPING"}
                                      ]
                                    }
                                  ]
                                }
                                """.formatted(rollbackCode, source, destination, source)))
                .andExpect(status().isConflict());
        Integer rolledBack = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM routes WHERE operator_id = ? AND code = ?
                """, Integer.class, operatorId, rollbackCode);
        assertThat(rolledBack).isZero();
    }

    @Test
    void routeCodeIsCaseInsensitivePerOperator() throws Exception {
        IssuedOperatorMember adminA = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_ADMIN, List.of("OPERATOR_ADMIN"));
        IssuedOperatorMember adminB = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_ADMIN, List.of("OPERATOR_ADMIN"));
        UUID sourceA = createLocation("Telangana", "CodeS-" + shortId());
        UUID destA = createLocation("Andhra Pradesh", "CodeD-" + shortId());
        UUID sourceB = createLocation("Karnataka", "CodeSB-" + shortId());
        UUID destB = createLocation("Tamil Nadu", "CodeDB-" + shortId());

        String sharedCode = "HYD-VJA-" + shortId();
        mockMvc.perform(post("/api/v1/operator/{operatorId}/routes", adminA.operator().getId())
                        .with(bearer(adminA.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRouteBody(sharedCode, sourceA, destA)))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/operator/{operatorId}/routes", adminB.operator().getId())
                        .with(bearer(adminB.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRouteBody(sharedCode, sourceB, destB)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/operator/{operatorId}/routes", adminA.operator().getId())
                        .with(bearer(adminA.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRouteBody(sharedCode.toLowerCase(), sourceA, destA)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Route code already exists for this operator."));

        assertThat(indexExists("ux_routes_operator_code_lower")).isTrue();
    }

    @Test
    void concurrentCaseInsensitiveDuplicateCreateAllowsExactlyOne() throws Exception {
        IssuedOperatorMember admin = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_ADMIN, List.of("OPERATOR_ADMIN"));
        UUID operatorId = admin.operator().getId();
        UUID source = createLocation("Telangana", "CiS-" + shortId());
        UUID destination = createLocation("Andhra Pradesh", "CiD-" + shortId());
        String codeUpper = "CI-" + shortId();
        String codeLower = codeUpper.toLowerCase();

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        AtomicInteger created = new AtomicInteger();
        AtomicInteger conflicted = new AtomicInteger();
        try {
            List<Future<?>> futures = new ArrayList<>();
            futures.add(executor.submit(() -> {
                ready.countDown();
                start.await();
                int statusCode = mockMvc.perform(post("/api/v1/operator/{operatorId}/routes", operatorId)
                                .with(bearer(admin.accessToken()))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(createRouteBody(codeUpper, source, destination)))
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
            futures.add(executor.submit(() -> {
                ready.countDown();
                start.await();
                int statusCode = mockMvc.perform(post("/api/v1/operator/{operatorId}/routes", operatorId)
                                .with(bearer(admin.accessToken()))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(createRouteBody(codeLower, source, destination)))
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
        Integer rows = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM routes
                WHERE operator_id = ? AND lower(code) = lower(?)
                """, Integer.class, operatorId, codeUpper);
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void unrelatedIntegrityFailureIsNotReportedAsRouteCodeConflict() throws Exception {
        IssuedOperatorMember admin = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_ADMIN, List.of("OPERATOR_ADMIN"));
        UUID operatorId = admin.operator().getId();
        UUID source = createLocation("Telangana", "FkS-" + shortId());
        UUID destination = createLocation("Andhra Pradesh", "FkD-" + shortId());
        String code = "FK-" + shortId();

        ReflectionTestUtils.setField(
                operatorRouteAdminService,
                "afterValidationBeforeSaveForTests",
                (Runnable) () -> jdbcTemplate.update("DELETE FROM locations WHERE id = ?", source));
        try {
            MvcResult result = mockMvc.perform(post("/api/v1/operator/{operatorId}/routes", operatorId)
                            .with(bearer(admin.accessToken()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createRouteBody(code, source, destination)))
                    .andReturn();

            assertThat(result.getResponse().getStatus()).isEqualTo(409);
            assertThat(result.getResponse().getContentAsString())
                    .doesNotContain("Route code already exists for this operator.");
            assertThat(result.getResponse().getContentAsString())
                    .contains("Request conflicts with the current state.");
            Integer rows = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM routes WHERE operator_id = ? AND code = ?
                    """, Integer.class, operatorId, code);
            assertThat(rows).isZero();
        } finally {
            ReflectionTestUtils.setField(
                    operatorRouteAdminService, "afterValidationBeforeSaveForTests", null);
        }
    }

    @Test
    void patchNameIsSafeWithTripsWhileEndpointsAreStructural() throws Exception {
        IssuedOperatorMember admin = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_ADMIN, List.of("OPERATOR_ADMIN"));
        UUID operatorId = admin.operator().getId();
        UUID source = createLocation("Telangana", "PatS-" + shortId());
        UUID via = createLocation("Telangana", "PatV-" + shortId());
        UUID destination = createLocation("Andhra Pradesh", "PatD-" + shortId());
        UUID altSource = createLocation("Telangana", "AltS-" + shortId());
        UUID routeId = createOperatorRouteWithStops(admin, "PAT-" + shortId(), source, via, destination);
        UUID busId = createAdminBus(operatorId);

        mockMvc.perform(patch("/api/v1/operator/{operatorId}/routes/{routeId}", operatorId, routeId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(patch("/api/v1/operator/{operatorId}/routes/{routeId}", operatorId, routeId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"NOPE\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(patch("/api/v1/operator/{operatorId}/routes/{routeId}", operatorId, routeId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceLocationId\":\"%s\"}".formatted(altSource)))
                .andExpect(status().isOk());

        createAdminTrip(busId, routeId);
        assertThat(tripRepository.existsByRoute_Id(routeId)).isTrue();

        mockMvc.perform(patch("/api/v1/operator/{operatorId}/routes/{routeId}", operatorId, routeId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Safe Rename\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Safe Rename"));

        mockMvc.perform(patch("/api/v1/operator/{operatorId}/routes/{routeId}", operatorId, routeId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceLocationId\":\"%s\"}".formatted(source)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value("Route source or destination cannot be changed while trips exist for this route."));
    }

    @Test
    void lifecycleActivateDeactivateIsIdempotentAndDoesNotAlterTrips() throws Exception {
        IssuedOperatorMember admin = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_ADMIN, List.of("OPERATOR_ADMIN"));
        UUID operatorId = admin.operator().getId();
        UUID source = createLocation("Telangana", "LifeS-" + shortId());
        UUID via = createLocation("Telangana", "LifeV-" + shortId());
        UUID destination = createLocation("Andhra Pradesh", "LifeD-" + shortId());
        UUID routeId = createOperatorRouteWithStops(admin, "LIFE-" + shortId(), source, via, destination);
        UUID busId = createAdminBus(operatorId);
        UUID tripId = createAdminTrip(busId, routeId);

        mockMvc.perform(post("/api/v1/operator/{operatorId}/routes/{routeId}/deactivate", operatorId, routeId)
                        .with(bearer(admin.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INACTIVE"));
        mockMvc.perform(post("/api/v1/operator/{operatorId}/routes/{routeId}/deactivate", operatorId, routeId)
                        .with(bearer(admin.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INACTIVE"));

        JsonNode trip = objectMapper.readTree(mockMvc.perform(get("/api/v1/admin/trips/{id}", tripId)
                        .with(bearer(platformAdminToken)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(trip.get("routeId").asText()).isEqualTo(routeId.toString());
        assertThat(trip.get("status").asText()).isNotEqualTo("CANCELLED");

        mockMvc.perform(post("/api/v1/admin/trips")
                        .with(bearer(platformAdminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tripBody(busId, routeId, "2027-08-01T10:00:00Z", "2027-08-01T18:00:00Z",
                                "2027-07-01T10:00:00Z", "2027-08-01T09:00:00Z")))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/operator/{operatorId}/routes/{routeId}/activate", operatorId, routeId)
                        .with(bearer(admin.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        mockMvc.perform(post("/api/v1/operator/{operatorId}/routes/{routeId}/activate", operatorId, routeId)
                        .with(bearer(admin.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void stopAndPointMutationsRespectTripGuardsAndIdor() throws Exception {
        IssuedOperatorMember admin = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_ADMIN, List.of("OPERATOR_ADMIN"));
        IssuedOperatorMember other = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_ADMIN, List.of("OPERATOR_ADMIN"));
        UUID operatorId = admin.operator().getId();
        UUID source = createLocation("Telangana", "SpS-" + shortId());
        UUID via = createLocation("Telangana", "SpV-" + shortId());
        UUID destination = createLocation("Andhra Pradesh", "SpD-" + shortId());
        UUID extra = createLocation("Telangana", "SpE-" + shortId());

        MvcResult created = mockMvc.perform(post("/api/v1/operator/{operatorId}/routes", operatorId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "code":"SP-%s",
                                  "name":"Stop Point Route",
                                  "sourceLocationId":"%s",
                                  "destinationLocationId":"%s",
                                  "stops":[
                                    {
                                      "locationId":"%s","sequenceNumber":1,"stopKind":"SOURCE",
                                      "departureOffsetMinutes":0,"distanceKm":0,
                                      "points":[{"name":"Origin","pointType":"BOARDING"}]
                                    },
                                    {
                                      "locationId":"%s","sequenceNumber":2,"stopKind":"DESTINATION",
                                      "arrivalOffsetMinutes":200,"distanceKm":300,
                                      "points":[{"name":"Dest","pointType":"DROPPING"}]
                                    }
                                  ]
                                }
                                """.formatted(shortId(), source, destination, source, destination)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode routeJson = objectMapper.readTree(created.getResponse().getContentAsString());
        UUID routeId = UUID.fromString(routeJson.get("id").asText());
        UUID sourceStopId = UUID.fromString(routeJson.get("stops").get(0).get("id").asText());
        UUID destStopId = UUID.fromString(routeJson.get("stops").get(1).get("id").asText());
        UUID pointId = UUID.fromString(routeJson.get("stops").get(0).get("points").get(0).get("id").asText());

        MvcResult midStop = mockMvc.perform(post("/api/v1/operator/{operatorId}/routes/{routeId}/stops",
                        operatorId, routeId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "locationId":"%s","sequenceNumber":3,"stopKind":"INTERMEDIATE",
                                  "arrivalOffsetMinutes":100,"departureOffsetMinutes":110,"distanceKm":150
                                }
                                """.formatted(via)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID midStopId = idOf(midStop);

        mockMvc.perform(post("/api/v1/operator/{operatorId}/routes/{routeId}/stops", operatorId, routeId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "locationId":"%s","sequenceNumber":3,"stopKind":"INTERMEDIATE",
                                  "arrivalOffsetMinutes":120,"departureOffsetMinutes":130,"distanceKm":160
                                }
                                """.formatted(extra)))
                .andExpect(status().isConflict());

        MvcResult addedPoint = mockMvc.perform(post(
                        "/api/v1/operator/{operatorId}/routes/{routeId}/stops/{stopId}/points",
                        operatorId, routeId, midStopId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Via Point\",\"pointType\":\"BOTH\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        UUID midPointId = idOf(addedPoint);

        UUID otherSource = createLocation("Karnataka", "IdorS-" + shortId());
        UUID otherDest = createLocation("Tamil Nadu", "IdorD-" + shortId());
        UUID otherRouteId = createOperatorRoute(other, "IDOR-" + shortId(), otherSource, otherDest);

        mockMvc.perform(patch("/api/v1/operator/{operatorId}/routes/{routeId}/stops/{stopId}",
                        other.operator().getId(), otherRouteId, sourceStopId)
                        .with(bearer(other.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "locationId":"%s","sequenceNumber":1,"stopKind":"SOURCE",
                                  "departureOffsetMinutes":0,"distanceKm":0
                                }
                                """.formatted(otherSource)))
                .andExpect(status().isNotFound());

        mockMvc.perform(patch(
                        "/api/v1/operator/{operatorId}/routes/{routeId}/stops/{stopId}/points/{pointId}",
                        other.operator().getId(), otherRouteId, sourceStopId, pointId)
                        .with(bearer(other.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Hijack\",\"pointType\":\"BOARDING\"}"))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/v1/operator/{operatorId}/routes/{routeId}/points/{pointId}/deactivate",
                        other.operator().getId(), otherRouteId, pointId)
                        .with(bearer(other.accessToken())))
                .andExpect(status().isNotFound());

        UUID busId = createAdminBus(operatorId);
        createAdminTrip(busId, routeId);

        mockMvc.perform(post("/api/v1/operator/{operatorId}/routes/{routeId}/stops", operatorId, routeId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "locationId":"%s","sequenceNumber":4,"stopKind":"INTERMEDIATE",
                                  "arrivalOffsetMinutes":150,"departureOffsetMinutes":160,"distanceKm":200
                                }
                                """.formatted(extra)))
                .andExpect(status().isConflict());

        mockMvc.perform(patch("/api/v1/operator/{operatorId}/routes/{routeId}/stops/{stopId}",
                        operatorId, routeId, midStopId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "locationId":"%s","sequenceNumber":3,"stopKind":"INTERMEDIATE",
                                  "arrivalOffsetMinutes":105,"departureOffsetMinutes":115,"distanceKm":155
                                }
                                """.formatted(via)))
                .andExpect(status().isConflict());

        mockMvc.perform(patch(
                        "/api/v1/operator/{operatorId}/routes/{routeId}/stops/{stopId}/points/{pointId}",
                        operatorId, routeId, midStopId, midPointId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Changed\",\"pointType\":\"BOTH\"}"))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/v1/operator/{operatorId}/routes/{routeId}/points/{pointId}/deactivate",
                        operatorId, routeId, midPointId)
                        .with(bearer(admin.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
        mockMvc.perform(post("/api/v1/operator/{operatorId}/routes/{routeId}/points/{pointId}/deactivate",
                        operatorId, routeId, midPointId)
                        .with(bearer(admin.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
        mockMvc.perform(post("/api/v1/operator/{operatorId}/routes/{routeId}/points/{pointId}/activate",
                        operatorId, routeId, midPointId)
                        .with(bearer(admin.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));

        Integer tripPointActiveCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM trip_points tp
                JOIN trip_stops ts ON ts.id = tp.trip_stop_id
                JOIN trips t ON t.id = ts.trip_id
                WHERE t.route_id = ? AND tp.name = 'Via Point' AND tp.active = false
                """, Integer.class, routeId);
        assertThat(tripPointActiveCount).isZero();
    }

    @Test
    void concurrentDemotionInvalidatesStaleRouteMutation() throws Exception {
        IssuedOperatorMember adminA = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_ADMIN, List.of("OPERATOR_ADMIN"));
        UUID operatorId = adminA.operator().getId();
        IssuedUser adminBUser = tokens.issueCustomer();
        tokens.attachMembership(adminA.operator(), adminBUser.user(), RoleCode.OPERATOR_ADMIN);
        IssuedUser adminBToken = tokens.issueToken(adminBUser.user(), List.of("CUSTOMER"));

        UUID source = createLocation("Telangana", "TocS-" + shortId());
        UUID destination = createLocation("Andhra Pradesh", "TocD-" + shortId());
        UUID routeId = createOperatorRoute(adminA, "TOC-" + shortId(), source, destination);

        CountDownLatch authorized = new CountDownLatch(1);
        CountDownLatch demoted = new CountDownLatch(1);
        AtomicInteger barrierHits = new AtomicInteger();
        ReflectionTestUtils.setField(
                operatorRouteAdminService,
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
                            patch("/api/v1/operator/{operatorId}/routes/{routeId}", operatorId, routeId)
                                    .with(bearer(adminA.accessToken()))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"name\":\"Should Fail\"}"))
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
            assertThat(routeRepository.findByIdAndOperator_Id(routeId, operatorId).orElseThrow().getName())
                    .isNotEqualTo("Should Fail");
        } finally {
            ReflectionTestUtils.setField(operatorRouteAdminService, "afterAuthorizeBeforeLockForTests", null);
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentPatchSerializesOnRouteLock() throws Exception {
        IssuedOperatorMember admin = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_ADMIN, List.of("OPERATOR_ADMIN"));
        UUID operatorId = admin.operator().getId();
        UUID source = createLocation("Telangana", "SerS-" + shortId());
        UUID destination = createLocation("Andhra Pradesh", "SerD-" + shortId());
        UUID routeId = createOperatorRoute(admin, "SER-" + shortId(), source, destination);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        AtomicInteger ok = new AtomicInteger();
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (String name : List.of("Name-A", "Name-B")) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    int statusCode = mockMvc.perform(patch(
                                    "/api/v1/operator/{operatorId}/routes/{routeId}", operatorId, routeId)
                                    .with(bearer(admin.accessToken()))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"name\":\"%s\"}".formatted(name)))
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
        String finalName = routeRepository.findByIdAndOperator_Id(routeId, operatorId).orElseThrow().getName();
        assertThat(finalName).isIn("Name-A", "Name-B");
    }

    @Test
    void structuralMutationVersusTripCreatePreservesConsistency() throws Exception {
        IssuedOperatorMember admin = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_ADMIN, List.of("OPERATOR_ADMIN"));
        UUID operatorId = admin.operator().getId();
        UUID source = createLocation("Telangana", "RaceS-" + shortId());
        UUID via = createLocation("Telangana", "RaceV-" + shortId());
        UUID destination = createLocation("Andhra Pradesh", "RaceD-" + shortId());
        UUID altSource = createLocation("Telangana", "RaceAlt-" + shortId());
        UUID routeId = createOperatorRouteWithStops(admin, "RACE-" + shortId(), source, via, destination);
        UUID busId = createAdminBus(operatorId);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        AtomicInteger patchOk = new AtomicInteger();
        AtomicInteger patchConflict = new AtomicInteger();
        AtomicInteger tripOk = new AtomicInteger();
        AtomicInteger tripFail = new AtomicInteger();
        try {
            List<Future<?>> futures = new ArrayList<>();
            futures.add(executor.submit(() -> {
                ready.countDown();
                start.await();
                int statusCode = mockMvc.perform(patch(
                                "/api/v1/operator/{operatorId}/routes/{routeId}", operatorId, routeId)
                                .with(bearer(admin.accessToken()))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"sourceLocationId\":\"%s\"}".formatted(altSource)))
                        .andReturn()
                        .getResponse()
                        .getStatus();
                if (statusCode == 200) {
                    patchOk.incrementAndGet();
                } else if (statusCode == 409) {
                    patchConflict.incrementAndGet();
                }
                return null;
            }));
            futures.add(executor.submit(() -> {
                ready.countDown();
                start.await();
                int statusCode = mockMvc.perform(post("/api/v1/admin/trips")
                                .with(bearer(platformAdminToken))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(tripBody(busId, routeId, "2027-09-01T10:00:00Z", "2027-09-01T18:00:00Z",
                                        "2027-08-01T10:00:00Z", "2027-09-01T09:00:00Z")))
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

        assertThat(patchOk.get() + patchConflict.get()).isEqualTo(1);
        assertThat(tripOk.get() + tripFail.get()).isEqualTo(1);
        // Trip-first => structural PATCH must 409. PATCH-first => trip may still create.
        if (tripOk.get() == 1 && patchOk.get() == 0) {
            assertThat(patchConflict.get()).isEqualTo(1);
        }
        Integer mismatches = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM trips t
                JOIN routes r ON r.id = t.route_id
                WHERE t.route_id = ?
                  AND t.operator_id <> r.operator_id
                """, Integer.class, routeId);
        assertThat(mismatches).isZero();
    }

    @Test
    void deactivateVersusTripCreatePreservesActiveRouteRule() throws Exception {
        IssuedOperatorMember admin = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_ADMIN, List.of("OPERATOR_ADMIN"));
        UUID operatorId = admin.operator().getId();
        UUID source = createLocation("Telangana", "DeactS-" + shortId());
        UUID via = createLocation("Telangana", "DeactV-" + shortId());
        UUID destination = createLocation("Andhra Pradesh", "DeactD-" + shortId());
        UUID routeId = createOperatorRouteWithStops(admin, "DEACT-" + shortId(), source, via, destination);
        UUID busId = createAdminBus(operatorId);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        AtomicInteger deactivateOk = new AtomicInteger();
        AtomicInteger tripOk = new AtomicInteger();
        AtomicInteger tripFail = new AtomicInteger();
        try {
            List<Future<?>> futures = new ArrayList<>();
            futures.add(executor.submit(() -> {
                ready.countDown();
                start.await();
                int statusCode = mockMvc.perform(post(
                                "/api/v1/operator/{operatorId}/routes/{routeId}/deactivate",
                                operatorId, routeId)
                                .with(bearer(admin.accessToken())))
                        .andReturn()
                        .getResponse()
                        .getStatus();
                if (statusCode == 200) {
                    deactivateOk.incrementAndGet();
                }
                return null;
            }));
            futures.add(executor.submit(() -> {
                ready.countDown();
                start.await();
                int statusCode = mockMvc.perform(post("/api/v1/admin/trips")
                                .with(bearer(platformAdminToken))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(tripBody(busId, routeId, "2027-10-01T10:00:00Z", "2027-10-01T18:00:00Z",
                                        "2027-09-01T10:00:00Z", "2027-10-01T09:00:00Z")))
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

        assertThat(deactivateOk.get()).isEqualTo(1);
        assertThat(tripOk.get() + tripFail.get()).isEqualTo(1);
        String status = routeRepository.findByIdAndOperator_Id(routeId, operatorId).orElseThrow().getStatus().name();
        assertThat(status).isEqualTo("INACTIVE");
        if (tripFail.get() == 1) {
            assertThat(tripRepository.existsByRoute_Id(routeId)).isFalse();
        } else {
            assertThat(tripRepository.existsByRoute_Id(routeId)).isTrue();
        }
    }

    private UUID createOperatorRoute(
            IssuedOperatorMember admin, String code, UUID source, UUID destination) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/operator/{operatorId}/routes", admin.operator().getId())
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRouteBody(code, source, destination)))
                .andExpect(status().isCreated())
                .andReturn();
        return idOf(result);
    }

    private UUID createOperatorRouteWithStops(
            IssuedOperatorMember admin,
            String code,
            UUID source,
            UUID via,
            UUID destination) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/operator/{operatorId}/routes", admin.operator().getId())
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "code":"%s",
                                  "name":"Route %s",
                                  "sourceLocationId":"%s",
                                  "destinationLocationId":"%s",
                                  "stops":[
                                    {
                                      "locationId":"%s","sequenceNumber":1,"stopKind":"SOURCE",
                                      "departureOffsetMinutes":0,"distanceKm":0,
                                      "points":[{"name":"Origin Boarding","pointType":"BOARDING"}]
                                    },
                                    {
                                      "locationId":"%s","sequenceNumber":2,"stopKind":"INTERMEDIATE",
                                      "arrivalOffsetMinutes":90,"departureOffsetMinutes":100,"distanceKm":140,
                                      "points":[{"name":"Via Stand","pointType":"BOTH"}]
                                    },
                                    {
                                      "locationId":"%s","sequenceNumber":3,"stopKind":"DESTINATION",
                                      "arrivalOffsetMinutes":270,"distanceKm":340,
                                      "points":[{"name":"Dest Drop","pointType":"DROPPING"}]
                                    }
                                  ]
                                }
                                """.formatted(code, shortId(), source, destination, source, via, destination)))
                .andExpect(status().isCreated())
                .andReturn();
        return idOf(result);
    }

    private String createRouteBody(String code, UUID source, UUID destination) {
        return createRouteBody(code, source, destination, "Route " + code);
    }

    private String createRouteBody(String code, UUID source, UUID destination, String name) {
        return """
                {
                  "code":"%s",
                  "name":"%s",
                  "sourceLocationId":"%s",
                  "destinationLocationId":"%s"
                }
                """.formatted(code, name, source, destination);
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

    private UUID createAdminBus(UUID operatorId) throws Exception {
        UUID busTypeId = createBusType("BT-" + shortId(), "Bus Type " + shortId());
        UUID layoutId = createPublishedLayout(operatorId, "L-" + shortId(), 1);
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

    private UUID createAdminTrip(UUID busId, UUID routeId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/admin/trips")
                        .with(bearer(platformAdminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tripBody(
                                busId,
                                routeId,
                                Instant.parse("2027-05-01T10:00:00Z").toString(),
                                Instant.parse("2027-05-01T18:00:00Z").toString(),
                                Instant.parse("2027-04-01T10:00:00Z").toString(),
                                Instant.parse("2027-05-01T09:00:00Z").toString())))
                .andExpect(status().isCreated())
                .andReturn();
        return idOf(result);
    }

    private String tripBody(
            UUID busId,
            UUID routeId,
            String departure,
            String arrival,
            String opens,
            String closes) {
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
                """.formatted(busId, routeId, departure, arrival, opens, closes);
    }

    private boolean indexExists(String indexName) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM pg_indexes
                WHERE schemaname = 'public' AND indexname = ?
                """, Integer.class, indexName);
        return count != null && count == 1;
    }

    private UUID idOf(MvcResult result) throws Exception {
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
