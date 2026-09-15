package in.bluebustickets.bluebus.identity.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import in.bluebustickets.bluebus.foundation.security.JwtConfiguration;
import in.bluebustickets.bluebus.identity.domain.Role;
import in.bluebustickets.bluebus.identity.domain.RoleCode;
import in.bluebustickets.bluebus.identity.domain.User;
import in.bluebustickets.bluebus.identity.domain.UserRole;
import in.bluebustickets.bluebus.identity.domain.UserStatus;
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
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
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
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthApiPostgresIntegrationTest {

    private static final String PASSWORD = "CorrectHorseBatteryStaple!";
    private static final String ADMIN_EMAIL = "admin-auth@example.test";
    private static final String CUSTOMER_EMAIL = "customer-auth@example.test";

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
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtDecoder jwtDecoder;
    @Autowired private JwtAuthenticationConverter jwtAuthenticationConverter;
    @Autowired private org.springframework.core.env.Environment environment;

    @BeforeEach
    void seedUsers() {
        userRoleRepository.deleteAll();
        userRepository.deleteAll();

        Role adminRole = roleRepository.findByCode(RoleCode.ADMIN).orElseThrow();
        Role customerRole = roleRepository.findByCode(RoleCode.CUSTOMER).orElseThrow();

        User admin = new User(ADMIN_EMAIL, "+919911100001", "Auth", "Admin");
        admin.setPasswordHash(passwordEncoder.encode(PASSWORD));
        admin = userRepository.saveAndFlush(admin);
        userRoleRepository.saveAndFlush(new UserRole(admin, adminRole));

        User customer = new User(CUSTOMER_EMAIL, "+919911100002", "Auth", "Customer");
        customer.setPasswordHash(passwordEncoder.encode(PASSWORD));
        customer = userRepository.saveAndFlush(customer);
        userRoleRepository.saveAndFlush(new UserRole(customer, customerRole));
    }

    @Test
    void loginSucceedsWithValidCredentials() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .with(anonymous())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(ADMIN_EMAIL, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(3600))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        Jwt jwt = jwtDecoder.decode(body.get("accessToken").asText());
        assertThat(jwt.getSubject()).isNotBlank();
        assertThat(jwt.getClaimAsString(JwtConfiguration.EMAIL_CLAIM)).isEqualToIgnoringCase(ADMIN_EMAIL);
        assertThat(jwt.getClaimAsStringList(JwtConfiguration.ROLES_CLAIM)).contains("ADMIN");
    }

    @Test
    void loginFailsWithIncorrectPasswordUnknownUserAndDisabledUser() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .with(anonymous())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(ADMIN_EMAIL, "wrong-password")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("Invalid credentials."));

        mockMvc.perform(post("/api/v1/auth/login")
                        .with(anonymous())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("missing@example.test", PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid credentials."));

        User suspended = userRepository.findByEmailIgnoreCase(CUSTOMER_EMAIL).orElseThrow();
        suspended.setStatus(UserStatus.SUSPENDED);
        userRepository.saveAndFlush(suspended);

        mockMvc.perform(post("/api/v1/auth/login")
                        .with(anonymous())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(CUSTOMER_EMAIL, PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid credentials."));
    }

    @Test
    void jwtAuthenticatesProtectedEndpointAndRejectsMissingMalformedExpired() throws Exception {
        String token = loginToken(ADMIN_EMAIL, PASSWORD);

        mockMvc.perform(get("/api/v1/admin/locations").with(anonymous()))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/admin/locations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/admin/locations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());

        String expired = expiredToken(UUID.fromString(
                jwtDecoder.decode(token).getSubject()), ADMIN_EMAIL, List.of("ADMIN"));
        mockMvc.perform(get("/api/v1/admin/locations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + expired))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void jwtAuthoritiesAreMappedFromRolesClaim() throws Exception {
        String token = loginToken(ADMIN_EMAIL, PASSWORD);
        Jwt jwt = jwtDecoder.decode(token);
        var authentication = jwtAuthenticationConverter.convert(jwt);
        assertThat(authentication).isInstanceOf(JwtAuthenticationToken.class);
        assertThat(authentication.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .contains("ROLE_ADMIN");
    }

    @Test
    void publicSeatAvailabilityAndHoldPathsRemainAccessible() throws Exception {
        // Anonymous access must not be 401 — domain 404 proves the security filter permitted the request.
        mockMvc.perform(get("/api/v1/trips/{tripId}/seat-availability", UUID.randomUUID())
                        .with(anonymous())
                        .param("originStopId", UUID.randomUUID().toString())
                        .param("destinationStopId", UUID.randomUUID().toString()))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/v1/trips/{tripId}/holds", UUID.randomUUID())
                        .with(anonymous())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "originStopId":"%s",
                                  "destinationStopId":"%s",
                                  "seatInventoryIds":["%s"]
                                }
                                """.formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/holds/{holdId}", UUID.randomUUID()).with(anonymous()))
                .andExpect(status().isNotFound());
    }

    @Test
    void adminApisRemainProtectedWithoutToken() throws Exception {
        mockMvc.perform(post("/api/v1/admin/operators")
                        .with(anonymous())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"legalName":"Auth Op","displayName":"Auth Op"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    private String loginToken(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(email, password)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private String expiredToken(UUID userId, String email, List<String> roles) throws Exception {
        String secret = environment.getProperty("blue-bus.security.jwt.secret");
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(environment.getProperty("blue-bus.security.jwt.issuer"))
                .subject(userId.toString())
                .issueTime(java.util.Date.from(now.minusSeconds(7200)))
                .expirationTime(java.util.Date.from(now.minusSeconds(3600)))
                .claim(JwtConfiguration.EMAIL_CLAIM, email)
                .claim(JwtConfiguration.ROLES_CLAIM, roles)
                .build();
        SignedJWT signed = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        signed.sign(new MACSigner(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        return signed.serialize();
    }

    private static String loginBody(String email, String password) {
        return """
                {"email":"%s","password":"%s"}
                """.formatted(email, password);
    }
}
