package in.bluebustickets.bluebus.identity.api;

import java.util.UUID;

import in.bluebustickets.bluebus.identity.domain.User;
import in.bluebustickets.bluebus.identity.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CustomerRegistrationApiPostgresIntegrationTest {

    private static final String PASSWORD = "StrongPassword123!";

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
    @Autowired private PasswordEncoder passwordEncoder;

    @Test
    void registersCustomerWithBcryptHashAndCustomerRole() throws Exception {
        String email = "rahul.register@example.test";

        MvcResult created = mockMvc.perform(post("/api/v1/auth/register")
                        .with(anonymous())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("Rahul", "Kumar", email, PASSWORD)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").isNotEmpty())
                .andExpect(jsonPath("$.firstName").value("Rahul"))
                .andExpect(jsonPath("$.lastName").value("Kumar"))
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.roles[0]").value("CUSTOMER"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andReturn();

        UUID userId = UUID.fromString(
                objectMapper.readTree(created.getResponse().getContentAsString()).get("userId").asText());
        User stored = userRepository.findById(userId).orElseThrow();
        assertThat(stored.getPasswordHash()).isNotEqualTo(PASSWORD);
        assertThat(stored.getPasswordHash()).startsWith("$2");
        assertThat(passwordEncoder.matches(PASSWORD, stored.getPasswordHash())).isTrue();
        assertThat(stored.getEmail()).isEqualTo(email);

        mockMvc.perform(post("/api/v1/auth/login")
                        .with(anonymous())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(email, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());
    }

    @Test
    void rejectsDuplicateEmailIncludingCaseAndIgnoresRoleFieldsInBody() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .with(anonymous())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("One", "User", "dup@example.test", PASSWORD)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/auth/register")
                        .with(anonymous())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("Two", "User", "dup@example.test", PASSWORD)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("An account with this email already exists."));

        mockMvc.perform(post("/api/v1/auth/register")
                        .with(anonymous())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("Two", "User", "DUP@example.test", PASSWORD)))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/v1/auth/register")
                        .with(anonymous())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("Two", "User", "  Dup@Example.Test  ", PASSWORD)))
                .andExpect(status().isConflict());

        // Extra privileged fields in JSON are ignored by the DTO; role remains CUSTOMER.
        MvcResult withExtra = mockMvc.perform(post("/api/v1/auth/register")
                        .with(anonymous())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "firstName":"Extra",
                                  "lastName":"Fields",
                                  "email":"extra-role@example.test",
                                  "password":"%s",
                                  "role":"ADMIN",
                                  "roles":["SUPER_ADMIN","OPERATOR_ADMIN"],
                                  "status":"SUSPENDED",
                                  "passwordHash":"not-a-hash"
                                }
                                """.formatted(PASSWORD)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.roles.length()").value(1))
                .andExpect(jsonPath("$.roles[0]").value("CUSTOMER"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn();

        assertThat(objectMapper.readTree(withExtra.getResponse().getContentAsString()).has("passwordHash"))
                .isFalse();
    }

    @Test
    void rejectsInvalidRegistrationInput() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .with(anonymous())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("Rahul", "Kumar", "not-an-email", PASSWORD)))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/auth/register")
                        .with(anonymous())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"lastName":"Kumar","email":"missing-first@example.test","password":"%s"}
                                """.formatted(PASSWORD)))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/auth/register")
                        .with(anonymous())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("Rahul", "Kumar", "weak@example.test", "short1")))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/auth/register")
                        .with(anonymous())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("Rahul", "Kumar", "letters@example.test", "PasswordOnly")))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/auth/register")
                        .with(anonymous())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("Rahul", "Kumar", "digits@example.test", "12345678")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void normalizesEmailWhitespaceAndCaseOnRegisterAndLogin() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .with(anonymous())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("Norm", "User", "  Norm.User@Example.TEST  ", PASSWORD)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("norm.user@example.test"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .with(anonymous())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("  NORM.USER@example.test  ", PASSWORD)))
                .andExpect(status().isOk());
    }

    @Test
    void authMeRequiresJwtAndReturnsAuthenticatedIdentity() throws Exception {
        String email = "me.user@example.test";
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("Me", "User", email, PASSWORD)))
                .andExpect(status().isCreated());

        String token = loginToken(email, PASSWORD);

        mockMvc.perform(get("/api/v1/auth/me").with(anonymous()))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());

        MvcResult me = mockMvc.perform(get("/api/v1/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .param("userId", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.firstName").value("Me"))
                .andExpect(jsonPath("$.roles[0]").value("CUSTOMER"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andReturn();

        JsonNode body = objectMapper.readTree(me.getResponse().getContentAsString());
        UUID meId = UUID.fromString(body.get("userId").asText());
        assertThat(userRepository.findById(meId).orElseThrow().getEmail()).isEqualTo(email);
    }

    @Test
    void publicAndAdminSecurityBoundariesRemain() throws Exception {
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

        mockMvc.perform(get("/api/v1/admin/locations").with(anonymous()))
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

    private static String registerBody(String firstName, String lastName, String email, String password) {
        return """
                {
                  "firstName":"%s",
                  "lastName":"%s",
                  "email":"%s",
                  "password":"%s"
                }
                """.formatted(firstName, lastName, email, password);
    }

    private static String loginBody(String email, String password) {
        return """
                {"email":"%s","password":"%s"}
                """.formatted(email, password);
    }
}
