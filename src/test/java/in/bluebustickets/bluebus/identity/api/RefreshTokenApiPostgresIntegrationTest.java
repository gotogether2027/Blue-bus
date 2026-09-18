package in.bluebustickets.bluebus.identity.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import in.bluebustickets.bluebus.identity.domain.Role;
import in.bluebustickets.bluebus.identity.domain.RoleCode;
import in.bluebustickets.bluebus.identity.domain.User;
import in.bluebustickets.bluebus.identity.domain.UserRole;
import in.bluebustickets.bluebus.identity.domain.UserStatus;
import in.bluebustickets.bluebus.identity.repository.RefreshTokenRepository;
import in.bluebustickets.bluebus.identity.repository.RoleRepository;
import in.bluebustickets.bluebus.identity.repository.UserRepository;
import in.bluebustickets.bluebus.identity.repository.UserRoleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RefreshTokenApiPostgresIntegrationTest {

    private static final String PASSWORD = "CorrectHorseBatteryStaple!";
    private static final String CUSTOMER_EMAIL = "refresh-customer@example.test";
    private static final String ADMIN_EMAIL = "refresh-admin@example.test";

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
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRoleRepository userRoleRepository;
    @Autowired private RefreshTokenRepository refreshTokenRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbcTemplate;

    private User customer;
    private User admin;

    @BeforeEach
    void seedUsers() {
        refreshTokenRepository.deleteAll();
        userRoleRepository.deleteAll();
        userRepository.deleteAll();

        Role customerRole = roleRepository.findByCode(RoleCode.CUSTOMER).orElseThrow();
        Role adminRole = roleRepository.findByCode(RoleCode.ADMIN).orElseThrow();

        customer = new User(CUSTOMER_EMAIL, "+919922200001", "Refresh", "Customer");
        customer.setPasswordHash(passwordEncoder.encode(PASSWORD));
        customer = userRepository.saveAndFlush(customer);
        userRoleRepository.saveAndFlush(new UserRole(customer, customerRole));

        admin = new User(ADMIN_EMAIL, "+919922200002", "Refresh", "Admin");
        admin.setPasswordHash(passwordEncoder.encode(PASSWORD));
        admin = userRepository.saveAndFlush(admin);
        userRoleRepository.saveAndFlush(new UserRole(admin, adminRole));
    }

    @Test
    void loginReturnsAccessAndRefreshWithHashOnlyStorage() throws Exception {
        JsonNode body = login(CUSTOMER_EMAIL);
        String refresh = body.get("refreshToken").asText();
        assertThat(body.get("accessToken").asText()).isNotBlank();
        assertThat(body.get("tokenType").asText()).isEqualTo("Bearer");
        assertThat(body.get("expiresIn").asInt()).isEqualTo(900);
        assertThat(body.has("tokenHash")).isFalse();
        assertThat(body.has("password")).isFalse();
        assertThat(body.has("passwordHash")).isFalse();
        assertThat(body.toString()).doesNotContain(bytesToHex(sha256(decodeRefresh(refresh))));

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT token_hash, octet_length(token_hash) AS hash_len FROM refresh_tokens");
        assertThat(rows).hasSize(1);
        byte[] stored = (byte[]) rows.get(0).get("token_hash");
        assertThat(stored).hasSize(32);
        assertThat(rows.get(0).get("hash_len")).isEqualTo(32);
        assertThat(stored).isEqualTo(sha256(decodeRefresh(refresh)));
        assertThat(new String(stored, StandardCharsets.UTF_8)).doesNotContain(refresh);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM refresh_tokens WHERE encode(token_hash, 'escape') LIKE ?",
                        Integer.class,
                        "%" + refresh + "%"))
                .isZero();
    }

    @Test
    void refreshRotatesTokenAndRejectsOldToken() throws Exception {
        JsonNode login = login(CUSTOMER_EMAIL);
        String oldRefresh = login.get("refreshToken").asText();

        MvcResult refreshResult = mockMvc.perform(post("/api/v1/auth/refresh")
                        .with(anonymous())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(oldRefresh)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andExpect(jsonPath("$.tokenHash").doesNotExist())
                .andReturn();

        JsonNode rotated = objectMapper.readTree(refreshResult.getResponse().getContentAsString());
        String newRefresh = rotated.get("refreshToken").asText();
        assertThat(newRefresh).isNotEqualTo(oldRefresh);

        // New token works before any reuse attempt.
        JsonNode again = objectMapper.readTree(mockMvc.perform(post("/api/v1/auth/refresh")
                        .with(anonymous())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(newRefresh)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andReturn()
                .getResponse()
                .getContentAsString());
        String newest = again.get("refreshToken").asText();

        // Presenting a superseded token is reuse → family revoke → generic 401.
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .with(anonymous())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(oldRefresh)))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .with(anonymous())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(newest)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void expiredUnknownAndRevokedRefreshTokensFail() throws Exception {
        JsonNode login = login(CUSTOMER_EMAIL);
        String refresh = login.get("refreshToken").asText();

        jdbcTemplate.update(
                """
                UPDATE refresh_tokens
                SET issued_at = ?, expires_at = ?
                WHERE token_hash = ?
                """,
                java.sql.Timestamp.from(Instant.now().minusSeconds(120)),
                java.sql.Timestamp.from(Instant.now().minusSeconds(60)),
                sha256(decodeRefresh(refresh)));

        expectGenericRefreshUnauthorized(refresh);

        String unknown = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        expectGenericRefreshUnauthorized(unknown);

        JsonNode again = login(CUSTOMER_EMAIL);
        String active = again.get("refreshToken").asText();
        jdbcTemplate.update(
                "UPDATE refresh_tokens SET revoked_at = ? WHERE token_hash = ?",
                java.sql.Timestamp.from(Instant.now()),
                sha256(decodeRefresh(active)));

        expectGenericRefreshUnauthorized(active);
    }

    @Test
    void malformedRefreshTokenMatchesUnknownUnauthorizedEnvelope() throws Exception {
        expectGenericRefreshUnauthorized("not-a-valid-refresh-token!!!");
        expectGenericRefreshUnauthorized("%%%");
        expectGenericRefreshUnauthorized(Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[8]));
        expectGenericRefreshUnauthorized("header.payload.signature");
    }

    @Test
    void reuseRevokesFamilyButNotOtherDeviceFamily() throws Exception {
        JsonNode familyA = login(CUSTOMER_EMAIL);
        JsonNode familyB = login(CUSTOMER_EMAIL);
        String a1 = familyA.get("refreshToken").asText();
        String b1 = familyB.get("refreshToken").asText();

        UUID familyAId = familyIdFor(a1);
        UUID familyBId = familyIdFor(b1);
        assertThat(familyAId).isNotEqualTo(familyBId);

        JsonNode rotatedA = objectMapper.readTree(mockMvc.perform(post("/api/v1/auth/refresh")
                        .with(anonymous())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(a1)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        String a2 = rotatedA.get("refreshToken").asText();

        // Outside the concurrent-collision grace window → genuine reuse.
        expireConcurrentGrace(a1);

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .with(anonymous())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(a1)))
                .andExpect(status().isUnauthorized());

        assertThat(refreshTokenRepository.countByFamilyIdAndRevokedAtIsNull(familyAId)).isZero();
        assertThat(refreshTokenRepository.countByFamilyIdAndRevokedAtIsNull(familyBId)).isEqualTo(1);

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .with(anonymous())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(b1)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .with(anonymous())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(a2)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void concurrentDoubleRefreshKeepsWinnerUsableAndLaterReplayRevokesFamily() throws Exception {
        JsonNode otherDevice = login(CUSTOMER_EMAIL);
        String otherRefresh = otherDevice.get("refreshToken").asText();
        UUID otherFamilyId = familyIdFor(otherRefresh);

        JsonNode login = login(CUSTOMER_EMAIL);
        String refresh = login.get("refreshToken").asText();
        UUID racedFamilyId = familyIdFor(refresh);

        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<String> winningRefresh =
                new java.util.concurrent.atomic.AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    MvcResult result = mockMvc.perform(post("/api/v1/auth/refresh")
                                    .with(anonymous())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(refreshBody(refresh)))
                            .andReturn();
                    if (result.getResponse().getStatus() == 200) {
                        successes.incrementAndGet();
                        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
                        winningRefresh.set(body.get("refreshToken").asText());
                    } else {
                        failures.incrementAndGet();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(successes.get()).isEqualTo(1);
        assertThat(failures.get()).isEqualTo(1);
        String winner = winningRefresh.get();
        assertThat(winner).isNotBlank();
        assertThat(winner).isNotEqualTo(refresh);
        assertThat(refreshTokenRepository.countByFamilyIdAndRevokedAtIsNull(racedFamilyId)).isEqualTo(1);

        // Still within grace: presenting RT-1 again is a collision, not theft.
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .with(anonymous())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(refresh)))
                .andExpect(status().isUnauthorized());
        assertThat(refreshTokenRepository.countByFamilyIdAndRevokedAtIsNull(racedFamilyId)).isEqualTo(1);

        // Winner's RT-2 remains usable.
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .with(anonymous())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(winner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty());

        // After grace, replay of RT-1 is genuine reuse → revoke raced family only.
        // (Successor of RT-1 may already be rotated; either way this is outside the collision window.)
        expireConcurrentGrace(refresh);
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .with(anonymous())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(refresh)))
                .andExpect(status().isUnauthorized());

        assertThat(refreshTokenRepository.countByFamilyIdAndRevokedAtIsNull(racedFamilyId)).isZero();
        assertThat(refreshTokenRepository.countByFamilyIdAndRevokedAtIsNull(otherFamilyId)).isEqualTo(1);

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .with(anonymous())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(otherRefresh)))
                .andExpect(status().isOk());
    }

    @Test
    void logoutIsIdempotentAndScopedToFamily() throws Exception {
        JsonNode familyA = login(CUSTOMER_EMAIL);
        JsonNode familyB = login(CUSTOMER_EMAIL);
        String a = familyA.get("refreshToken").asText();
        String b = familyB.get("refreshToken").asText();
        UUID familyAId = familyIdFor(a);
        UUID familyBId = familyIdFor(b);

        mockMvc.perform(post("/api/v1/auth/logout")
                        .with(anonymous())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody("not-a-valid-refresh-token!!!")))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/v1/auth/logout")
                        .with(anonymous())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]))))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/v1/auth/logout")
                        .with(anonymous())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(a)))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/auth/logout")
                        .with(anonymous())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(a)))
                .andExpect(status().isNoContent());

        assertThat(refreshTokenRepository.countByFamilyIdAndRevokedAtIsNull(familyAId)).isZero();
        assertThat(refreshTokenRepository.countByFamilyIdAndRevokedAtIsNull(familyBId)).isEqualTo(1);

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .with(anonymous())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(a)))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .with(anonymous())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(b)))
                .andExpect(status().isOk());
    }

    @Test
    void suspendedAndInactiveUsersCannotRefresh() throws Exception {
        JsonNode suspendedLogin = login(CUSTOMER_EMAIL);
        String suspendedRefresh = suspendedLogin.get("refreshToken").asText();
        customer.setStatus(UserStatus.SUSPENDED);
        userRepository.saveAndFlush(customer);

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .with(anonymous())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(suspendedRefresh)))
                .andExpect(status().isUnauthorized());

        customer.setStatus(UserStatus.ACTIVE);
        userRepository.saveAndFlush(customer);
        JsonNode inactiveLogin = login(CUSTOMER_EMAIL);
        String inactiveRefresh = inactiveLogin.get("refreshToken").asText();
        customer.setStatus(UserStatus.INACTIVE);
        userRepository.saveAndFlush(customer);

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .with(anonymous())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(inactiveRefresh)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refreshAndLogoutArePublicWhileMeAndAdminRemainProtected() throws Exception {
        JsonNode login = login(CUSTOMER_EMAIL);
        String refresh = login.get("refreshToken").asText();
        String access = login.get("accessToken").asText();

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .with(anonymous())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(refresh)))
                .andExpect(status().isOk());

        JsonNode again = login(CUSTOMER_EMAIL);
        mockMvc.perform(post("/api/v1/auth/logout")
                        .with(anonymous())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(again.get("refreshToken").asText())))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/auth/me").with(anonymous()))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(CUSTOMER_EMAIL));

        mockMvc.perform(get("/api/v1/admin/locations").with(anonymous()))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .with(anonymous())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    private JsonNode login(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .with(anonymous())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}
                                """.formatted(email, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private void expectGenericRefreshUnauthorized(String refreshToken) throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .with(anonymous())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(refreshToken)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("Invalid credentials."))
                .andExpect(jsonPath("$.path").value("/api/v1/auth/refresh"))
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andExpect(content().string(not(containsString("malformed"))))
                .andExpect(content().string(not(containsString("SHA-256"))))
                .andExpect(content().string(not(containsString("tokenHash"))))
                .andExpect(content().string(not(containsString(refreshToken))));
    }

    private static String refreshBody(String refreshToken) {
        return "{\"refreshToken\":%s}".formatted(quote(refreshToken));
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private UUID familyIdFor(String refreshToken) {
        return jdbcTemplate.queryForObject(
                "SELECT family_id FROM refresh_tokens WHERE token_hash = ?",
                UUID.class,
                sha256(decodeRefresh(refreshToken)));
    }

    private void expireConcurrentGrace(String refreshToken) {
        java.sql.Timestamp past = java.sql.Timestamp.from(Instant.now().minusSeconds(60));
        jdbcTemplate.update(
                """
                UPDATE refresh_tokens
                SET last_used_at = ?, revoked_at = COALESCE(revoked_at, ?)
                WHERE token_hash = ?
                """,
                past,
                past,
                sha256(decodeRefresh(refreshToken)));
    }

    private static byte[] decodeRefresh(String transport) {
        return Base64.getUrlDecoder().decode(transport);
    }

    private static byte[] sha256(byte[] raw) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(raw);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(String.format("%02x", value));
        }
        return builder.toString();
    }
}
