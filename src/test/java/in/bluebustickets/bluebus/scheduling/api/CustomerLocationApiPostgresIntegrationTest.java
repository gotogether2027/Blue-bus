package in.bluebustickets.bluebus.scheduling.api;

import java.util.UUID;

import in.bluebustickets.bluebus.foundation.security.TestAccessTokenFactory;
import in.bluebustickets.bluebus.scheduling.application.LocationCustomerService;
import in.bluebustickets.bluebus.scheduling.domain.Location;
import in.bluebustickets.bluebus.scheduling.repository.LocationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CustomerLocationApiPostgresIntegrationTest {

    private static final String ALLOWED_ORIGIN = "http://localhost:4200";
    private static final String REJECTED_ORIGIN = "http://evil.example";

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
    @Autowired private LocationRepository locationRepository;
    @Autowired private TestAccessTokenFactory testAccessTokenFactory;

    private String adminToken;

    @BeforeEach
    void seedPlatformAdmin() {
        locationRepository.deleteAll();
        adminToken = testAccessTokenFactory.issuePlatformAdmin().accessToken();
    }

    @Test
    void publicActiveListHidesInactiveFiltersAndOrdersDeterministically() throws Exception {
        UUID amaravati = createAdminLocation("Andhra Pradesh", "Amaravati", "Undavalli");
        UUID vijayawada = createAdminLocation("Andhra Pradesh", "Vijayawada", "Benz Circle");
        UUID hyderabad = createAdminLocation("Telangana", "Hyderabad", "Miyapur");
        UUID inactive = createAdminLocation("Karnataka", "Bengaluru", "Majestic");

        mockMvc.perform(post("/api/v1/admin/locations/{id}/deactivate", inactive)
                        .with(TestAccessTokenFactory.bearer(adminToken)))
                .andExpect(status().isOk());

        MvcResult list = mockMvc.perform(get("/api/v1/locations").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].id").value(amaravati.toString()))
                .andExpect(jsonPath("$[0].city").value("Amaravati"))
                .andExpect(jsonPath("$[0].state").value("Andhra Pradesh"))
                .andExpect(jsonPath("$[0].countryCode").value("IN"))
                .andExpect(jsonPath("$[0].locality").value("Undavalli"))
                .andExpect(jsonPath("$[1].id").value(vijayawada.toString()))
                .andExpect(jsonPath("$[2].id").value(hyderabad.toString()))
                .andExpect(jsonPath("$[0].latitude").doesNotExist())
                .andExpect(jsonPath("$[0].longitude").doesNotExist())
                .andExpect(jsonPath("$[0].district").doesNotExist())
                .andExpect(jsonPath("$[0].timeZone").doesNotExist())
                .andExpect(jsonPath("$[0].active").doesNotExist())
                .andReturn();
        JsonNode body = objectMapper.readTree(list.getResponse().getContentAsString());
        assertThat(body.get(0).fieldNames()).toIterable().containsExactly(
                "id", "city", "state", "countryCode", "locality");

        mockMvc.perform(get("/api/v1/locations").param("state", "Andhra"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].city").value("Amaravati"))
                .andExpect(jsonPath("$[1].city").value("Vijayawada"));

        mockMvc.perform(get("/api/v1/locations").param("city", "Hyderabad"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(hyderabad.toString()));

        mockMvc.perform(get("/api/v1/locations").param("city", "NoSuchCity"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void publicListIsBoundedToOneHundredActiveRows() throws Exception {
        for (int i = 0; i < 101; i++) {
            locationRepository.save(new Location("BoundState", "City-%03d".formatted(i)));
        }
        locationRepository.flush();

        mockMvc.perform(get("/api/v1/locations").param("state", "BoundState"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(LocationCustomerService.MAX_RESULTS))
                .andExpect(jsonPath("$[0].city").value("City-000"))
                .andExpect(jsonPath("$[99].city").value("City-099"));
    }

    @Test
    void corsAllowsConfiguredOriginAndRejectsOthersIncludingPreflight() throws Exception {
        mockMvc.perform(get("/api/v1/locations")
                        .header("Origin", ALLOWED_ORIGIN)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", ALLOWED_ORIGIN));

        mockMvc.perform(get("/api/v1/locations")
                        .header("Origin", REJECTED_ORIGIN)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));

        mockMvc.perform(options("/api/v1/locations")
                        .header("Origin", ALLOWED_ORIGIN)
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "Authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", ALLOWED_ORIGIN))
                .andExpect(header().string("Access-Control-Allow-Headers", org.hamcrest.Matchers.containsString("Authorization")));

        mockMvc.perform(options("/api/v1/locations")
                        .header("Origin", REJECTED_ORIGIN)
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "Authorization"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    private UUID createAdminLocation(String state, String city, String locality) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/admin/locations")
                        .with(TestAccessTokenFactory.bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "countryCode":"IN",
                                  "state":"%s",
                                  "district":"District",
                                  "city":"%s",
                                  "locality":"%s",
                                  "latitude":17.385000,
                                  "longitude":78.486700,
                                  "timeZone":"Asia/Kolkata"
                                }
                                """.formatted(state, city, locality)))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText());
    }
}
