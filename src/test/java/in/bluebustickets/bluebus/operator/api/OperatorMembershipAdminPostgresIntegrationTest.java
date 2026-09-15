package in.bluebustickets.bluebus.operator.api;

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
import in.bluebustickets.bluebus.operator.application.OperatorMembershipAdminService;
import in.bluebustickets.bluebus.operator.domain.OperatorUserStatus;
import in.bluebustickets.bluebus.operator.repository.OperatorUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

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
class OperatorMembershipAdminPostgresIntegrationTest {

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
    @Autowired private TestAccessTokenFactory tokens;
    @Autowired private OperatorUserRepository operatorUserRepository;
    @Autowired private OperatorMembershipAdminService membershipAdminService;

    @Test
    void adminAndStaffCanListMembersAndStaffCannotMutate() throws Exception {
        IssuedOperatorMember admin = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_ADMIN, List.of("OPERATOR_ADMIN"));
        IssuedUser staffUser = tokens.issueCustomer();
        tokens.attachMembership(admin.operator(), staffUser.user(), RoleCode.OPERATOR_STAFF);
        IssuedUser staffToken = tokens.issueToken(staffUser.user(), List.of("CUSTOMER"));

        UUID operatorId = admin.operator().getId();
        mockMvc.perform(get("/api/v1/operator/{operatorId}/members", operatorId)
                        .with(bearer(admin.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].passwordHash").doesNotExist());

        mockMvc.perform(get("/api/v1/operator/{operatorId}/members", operatorId)
                        .with(bearer(staffToken.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        IssuedUser target = tokens.issueCustomer();
        mockMvc.perform(post("/api/v1/operator/{operatorId}/members", operatorId)
                        .with(bearer(staffToken.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":"%s","role":"OPERATOR_STAFF"}
                                """.formatted(target.user().getId())))
                .andExpect(status().isForbidden());

        mockMvc.perform(patch("/api/v1/operator/{operatorId}/members/{userId}",
                        operatorId, staffUser.user().getId())
                        .with(bearer(staffToken.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"OPERATOR_ADMIN\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/operator/{operatorId}/members/{userId}/deactivate",
                        operatorId, staffUser.user().getId())
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

        mockMvc.perform(get("/api/v1/operator/{operatorId}/members", other.operator().getId())
                        .with(bearer(admin.accessToken())))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/operator/{operatorId}/members", admin.operator().getId())
                        .with(anonymous()))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/operator/{operatorId}/members", admin.operator().getId())
                        .with(bearer(inactive.accessToken())))
                .andExpect(status().isUnauthorized());

        var membership = operatorUserRepository
                .findByOperatorIdAndUserId(admin.operator().getId(), admin.user().getId())
                .orElseThrow();
        membership.deactivate();
        operatorUserRepository.saveAndFlush(membership);

        mockMvc.perform(get("/api/v1/operator/{operatorId}/members", admin.operator().getId())
                        .with(bearer(admin.accessToken())))
                .andExpect(status().isNotFound());
    }

    @Test
    void createUpdateAndReactivateMemberships() throws Exception {
        IssuedOperatorMember admin = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_ADMIN, List.of("OPERATOR_ADMIN"));
        UUID operatorId = admin.operator().getId();
        IssuedUser staffCandidate = tokens.issueCustomer();
        IssuedUser adminCandidate = tokens.issueCustomer();

        mockMvc.perform(post("/api/v1/operator/{operatorId}/members", operatorId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":"%s","role":"OPERATOR_STAFF"}
                                """.formatted(staffCandidate.user().getId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value(staffCandidate.user().getId().toString()))
                .andExpect(jsonPath("$.role").value("OPERATOR_STAFF"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.email").value(staffCandidate.user().getEmail()));

        mockMvc.perform(post("/api/v1/operator/{operatorId}/members", operatorId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":"%s","role":"OPERATOR_ADMIN"}
                                """.formatted(adminCandidate.user().getId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("OPERATOR_ADMIN"));

        mockMvc.perform(post("/api/v1/operator/{operatorId}/members", operatorId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":"%s","role":"OPERATOR_STAFF"}
                                """.formatted(staffCandidate.user().getId())))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/v1/operator/{operatorId}/members", operatorId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":"%s","role":"OPERATOR_STAFF"}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/v1/operator/{operatorId}/members", operatorId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":"%s","role":"ADMIN"}
                                """.formatted(staffCandidate.user().getId())))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/operator/{operatorId}/members", operatorId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":"%s","role":"OPERATOR_STAFF","operatorId":"%s"}
                                """.formatted(UUID.randomUUID(), UUID.randomUUID())))
                .andExpect(status().isBadRequest());

        mockMvc.perform(patch("/api/v1/operator/{operatorId}/members/{userId}",
                        operatorId, staffCandidate.user().getId())
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"OPERATOR_ADMIN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("OPERATOR_ADMIN"));

        mockMvc.perform(patch("/api/v1/operator/{operatorId}/members/{userId}",
                        operatorId, staffCandidate.user().getId())
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"OPERATOR_STAFF\",\"status\":\"INACTIVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("OPERATOR_STAFF"))
                .andExpect(jsonPath("$.status").value("INACTIVE"));

        mockMvc.perform(patch("/api/v1/operator/{operatorId}/members/{userId}",
                        operatorId, staffCandidate.user().getId())
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        mockMvc.perform(patch("/api/v1/operator/{operatorId}/members/{userId}",
                        operatorId, staffCandidate.user().getId())
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(patch("/api/v1/operator/{operatorId}/members/{userId}",
                        operatorId, staffCandidate.user().getId())
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"OPERATOR_STAFF\",\"userId\":\"%s\"}"
                                .formatted(UUID.randomUUID())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void lastActiveAdminInvariantAndSelfDemotion() throws Exception {
        IssuedOperatorMember adminA = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_ADMIN, List.of("OPERATOR_ADMIN"));
        UUID operatorId = adminA.operator().getId();

        mockMvc.perform(post("/api/v1/operator/{operatorId}/members/{userId}/deactivate",
                        operatorId, adminA.user().getId())
                        .with(bearer(adminA.accessToken())))
                .andExpect(status().isConflict());

        mockMvc.perform(patch("/api/v1/operator/{operatorId}/members/{userId}",
                        operatorId, adminA.user().getId())
                        .with(bearer(adminA.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"OPERATOR_STAFF\"}"))
                .andExpect(status().isConflict());

        IssuedUser adminBUser = tokens.issueCustomer();
        mockMvc.perform(post("/api/v1/operator/{operatorId}/members", operatorId)
                        .with(bearer(adminA.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":"%s","role":"OPERATOR_ADMIN"}
                                """.formatted(adminBUser.user().getId())))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/operator/{operatorId}/members/{userId}/deactivate",
                        operatorId, adminA.user().getId())
                        .with(bearer(adminA.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INACTIVE"));

        mockMvc.perform(patch("/api/v1/operator/{operatorId}/members/{userId}",
                        operatorId, adminA.user().getId())
                        .with(bearer(adminA.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\",\"role\":\"OPERATOR_ADMIN\"}"))
                .andExpect(status().isNotFound());

        // Reactivate A using B's token (A membership inactive → A cannot authorize).
        IssuedUser adminBToken = tokens.issueToken(adminBUser.user(), List.of("CUSTOMER"));
        mockMvc.perform(patch("/api/v1/operator/{operatorId}/members/{userId}",
                        operatorId, adminA.user().getId())
                        .with(bearer(adminBToken.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\",\"role\":\"OPERATOR_ADMIN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        mockMvc.perform(patch("/api/v1/operator/{operatorId}/members/{userId}",
                        operatorId, adminA.user().getId())
                        .with(bearer(adminA.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"OPERATOR_STAFF\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("OPERATOR_STAFF"));
    }

    @Test
    void crossOperatorMemberAccessIsHidden() throws Exception {
        IssuedOperatorMember adminA = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_ADMIN, List.of("OPERATOR_ADMIN"));
        IssuedOperatorMember adminB = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_ADMIN, List.of("OPERATOR_ADMIN"));

        mockMvc.perform(get("/api/v1/operator/{operatorId}/members", adminB.operator().getId())
                        .with(bearer(adminA.accessToken())))
                .andExpect(status().isNotFound());

        mockMvc.perform(patch("/api/v1/operator/{operatorId}/members/{userId}",
                        adminA.operator().getId(), adminB.user().getId())
                        .with(bearer(adminA.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"OPERATOR_STAFF\"}"))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/v1/operator/{operatorId}/members/{userId}/deactivate",
                        adminA.operator().getId(), adminB.user().getId())
                        .with(bearer(adminA.accessToken())))
                .andExpect(status().isNotFound());
    }

    @Test
    void concurrentDemotionInvalidatesStaleAdminAuthorizationBeforeMutation() throws Exception {
        IssuedOperatorMember adminA = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_ADMIN, List.of("OPERATOR_ADMIN"));
        UUID operatorId = adminA.operator().getId();
        IssuedUser adminBUser = tokens.issueCustomer();
        tokens.attachMembership(adminA.operator(), adminBUser.user(), RoleCode.OPERATOR_ADMIN);
        IssuedUser adminBToken = tokens.issueToken(adminBUser.user(), List.of("CUSTOMER"));
        IssuedUser target = tokens.issueCustomer();

        CountDownLatch authorized = new CountDownLatch(1);
        CountDownLatch demoted = new CountDownLatch(1);
        AtomicInteger barrierHits = new AtomicInteger();
        ReflectionTestUtils.setField(
                membershipAdminService,
                "afterAuthorizeBeforeLockForTests",
                (Runnable) () -> {
                    // Only the first mutation (stale admin add) pauses; the demoting admin must proceed.
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
            Future<MvcResult> addFuture = executor.submit(() -> mockMvc.perform(
                            post("/api/v1/operator/{operatorId}/members", operatorId)
                                    .with(bearer(adminA.accessToken()))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("""
                                            {"userId":"%s","role":"OPERATOR_STAFF"}
                                            """.formatted(target.user().getId())))
                    .andReturn());

            assertThat(authorized.await(20, TimeUnit.SECONDS)).isTrue();

            mockMvc.perform(patch("/api/v1/operator/{operatorId}/members/{userId}",
                            operatorId, adminA.user().getId())
                            .with(bearer(adminBToken.accessToken()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"role\":\"OPERATOR_STAFF\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.role").value("OPERATOR_STAFF"));

            demoted.countDown();

            MvcResult addResult = addFuture.get(30, TimeUnit.SECONDS);
            assertThat(addResult.getResponse().getStatus()).isEqualTo(403);
            assertThat(operatorUserRepository.findByOperatorIdAndUserId(operatorId, target.user().getId()))
                    .isEmpty();
            assertThat(operatorUserRepository.countActiveOperatorAdmins(
                    operatorId, OperatorUserStatus.ACTIVE, RoleCode.OPERATOR_ADMIN)).isEqualTo(1);
        } finally {
            ReflectionTestUtils.setField(
                    membershipAdminService, "afterAuthorizeBeforeLockForTests", null);
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentLastAdminMutationsPreserveAtLeastOneAdmin() throws Exception {
        IssuedOperatorMember adminA = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_ADMIN, List.of("OPERATOR_ADMIN"));
        UUID operatorId = adminA.operator().getId();
        IssuedUser adminBUser = tokens.issueCustomer();
        tokens.attachMembership(adminA.operator(), adminBUser.user(), RoleCode.OPERATOR_ADMIN);
        IssuedUser adminBToken = tokens.issueToken(adminBUser.user(), List.of("CUSTOMER"));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        AtomicInteger conflicts = new AtomicInteger();
        AtomicInteger successes = new AtomicInteger();
        try {
            List<Future<?>> futures = new ArrayList<>();
            futures.add(executor.submit(() -> {
                ready.countDown();
                start.await();
                MvcResult result = mockMvc.perform(post(
                                "/api/v1/operator/{operatorId}/members/{userId}/deactivate",
                                operatorId, adminA.user().getId())
                                .with(bearer(adminA.accessToken())))
                        .andReturn();
                if (result.getResponse().getStatus() == 409) {
                    conflicts.incrementAndGet();
                } else if (result.getResponse().getStatus() == 200) {
                    successes.incrementAndGet();
                }
                return null;
            }));
            futures.add(executor.submit(() -> {
                ready.countDown();
                start.await();
                MvcResult result = mockMvc.perform(patch(
                                "/api/v1/operator/{operatorId}/members/{userId}",
                                operatorId, adminBUser.user().getId())
                                .with(bearer(adminBToken.accessToken()))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"role\":\"OPERATOR_STAFF\"}"))
                        .andReturn();
                if (result.getResponse().getStatus() == 409) {
                    conflicts.incrementAndGet();
                } else if (result.getResponse().getStatus() == 200) {
                    successes.incrementAndGet();
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

        assertThat(successes.get() + conflicts.get()).isEqualTo(2);
        assertThat(operatorUserRepository.countActiveOperatorAdmins(
                operatorId, OperatorUserStatus.ACTIVE, RoleCode.OPERATOR_ADMIN)).isGreaterThanOrEqualTo(1);
    }

    @Test
    void concurrentDuplicateMembershipCreationYieldsOneRow() throws Exception {
        IssuedOperatorMember admin = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_ADMIN, List.of("OPERATOR_ADMIN"));
        UUID operatorId = admin.operator().getId();
        IssuedUser target = tokens.issueCustomer();

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        AtomicInteger created = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    MvcResult result = mockMvc.perform(post("/api/v1/operator/{operatorId}/members", operatorId)
                                    .with(bearer(admin.accessToken()))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("""
                                            {"userId":"%s","role":"OPERATOR_STAFF"}
                                            """.formatted(target.user().getId())))
                            .andReturn();
                    if (result.getResponse().getStatus() == 201) {
                        created.incrementAndGet();
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

        assertThat(created.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(1);
        assertThat(operatorUserRepository.findByOperatorIdAndUserId(operatorId, target.user().getId()))
                .isPresent();
    }

    @Test
    void singleAdminConcurrentMutationsCannotRemoveLastAdmin() throws Exception {
        IssuedOperatorMember admin = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_ADMIN, List.of("OPERATOR_ADMIN"));
        UUID operatorId = admin.operator().getId();

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        AtomicInteger conflicts = new AtomicInteger();
        try {
            List<Future<?>> futures = new ArrayList<>();
            futures.add(executor.submit(() -> {
                ready.countDown();
                start.await();
                int statusCode = mockMvc.perform(post(
                                "/api/v1/operator/{operatorId}/members/{userId}/deactivate",
                                operatorId, admin.user().getId())
                                .with(bearer(admin.accessToken())))
                        .andReturn()
                        .getResponse()
                        .getStatus();
                if (statusCode == 409) {
                    conflicts.incrementAndGet();
                }
                return null;
            }));
            futures.add(executor.submit(() -> {
                ready.countDown();
                start.await();
                int statusCode = mockMvc.perform(patch(
                                "/api/v1/operator/{operatorId}/members/{userId}",
                                operatorId, admin.user().getId())
                                .with(bearer(admin.accessToken()))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"role\":\"OPERATOR_STAFF\"}"))
                        .andReturn()
                        .getResponse()
                        .getStatus();
                if (statusCode == 409) {
                    conflicts.incrementAndGet();
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

        assertThat(conflicts.get()).isEqualTo(2);
        assertThat(operatorUserRepository.countActiveOperatorAdmins(
                operatorId, OperatorUserStatus.ACTIVE, RoleCode.OPERATOR_ADMIN)).isEqualTo(1);
    }

    @Test
    void concurrentUpdatesOnSameMembershipDoNotDuplicateOrBreakInvariant() throws Exception {
        IssuedOperatorMember admin = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_ADMIN, List.of("OPERATOR_ADMIN"));
        UUID operatorId = admin.operator().getId();
        IssuedUser staff = tokens.issueCustomer();
        tokens.attachMembership(admin.operator(), staff.user(), RoleCode.OPERATOR_STAFF);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        AtomicInteger successes = new AtomicInteger();
        try {
            List<Future<?>> futures = new ArrayList<>();
            futures.add(executor.submit(() -> {
                ready.countDown();
                start.await();
                int statusCode = mockMvc.perform(patch(
                                "/api/v1/operator/{operatorId}/members/{userId}",
                                operatorId, staff.user().getId())
                                .with(bearer(admin.accessToken()))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"status\":\"INACTIVE\"}"))
                        .andReturn()
                        .getResponse()
                        .getStatus();
                if (statusCode == 200) {
                    successes.incrementAndGet();
                }
                return null;
            }));
            futures.add(executor.submit(() -> {
                ready.countDown();
                start.await();
                int statusCode = mockMvc.perform(patch(
                                "/api/v1/operator/{operatorId}/members/{userId}",
                                operatorId, staff.user().getId())
                                .with(bearer(admin.accessToken()))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"role\":\"OPERATOR_ADMIN\"}"))
                        .andReturn()
                        .getResponse()
                        .getStatus();
                if (statusCode == 200) {
                    successes.incrementAndGet();
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

        assertThat(successes.get()).isEqualTo(2);
        assertThat(operatorUserRepository.findByOperatorIdAndUserId(operatorId, staff.user().getId()))
                .isPresent();
        assertThat(operatorUserRepository.countActiveOperatorAdmins(
                operatorId, OperatorUserStatus.ACTIVE, RoleCode.OPERATOR_ADMIN)).isGreaterThanOrEqualTo(1);
    }
}
