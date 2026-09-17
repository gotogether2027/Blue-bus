package in.bluebustickets.bluebus.foundation.demodata;

import java.util.UUID;

import in.bluebustickets.bluebus.identity.domain.RoleCode;
import in.bluebustickets.bluebus.identity.domain.User;
import in.bluebustickets.bluebus.identity.repository.UserRepository;
import in.bluebustickets.bluebus.identity.repository.UserRoleRepository;
import in.bluebustickets.bluebus.payments.repository.PaymentAttemptRepository;
import in.bluebustickets.bluebus.scheduling.repository.LocationRepository;
import in.bluebustickets.bluebus.scheduling.repository.TripRepository;
import in.bluebustickets.bluebus.scheduling.repository.TripSeatInventoryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DemoDataBootstrapPostgresIntegrationTest {

    private static final String DEMO_PASSWORD = "DemoPassw0rd!";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("blue-bus.demo-data.enabled", () -> "true");
        registry.add("blue-bus.demo-data.customer-password", () -> DEMO_PASSWORD);
        registry.add("blue-bus.seat-holds.expiry.enabled", () -> "false");
        registry.add("blue-bus.bookings.expiry.enabled", () -> "false");
        registry.add("blue-bus.payments.default-provider", () -> "UNCONFIGURED");
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private DemoDataService demoDataService;
    @Autowired private LocationRepository locationRepository;
    @Autowired private TripRepository tripRepository;
    @Autowired private TripSeatInventoryRepository tripSeatInventoryRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private UserRoleRepository userRoleRepository;
    @Autowired private PaymentAttemptRepository paymentAttemptRepository;

    @Test
    void seedsSearchableSaleableCatalogAndAllowsHoldBookingWithoutPaymentSuccess() throws Exception {
        long locationCount = locationRepository.count();
        long tripCount = tripRepository.count();
        long userCount = userRepository.count();
        demoDataService.ensureDemoData();
        demoDataService.ensureDemoData();
        assertThat(locationRepository.count()).isEqualTo(locationCount);
        assertThat(tripRepository.count()).isEqualTo(tripCount);
        assertThat(userRepository.count()).isEqualTo(userCount);

        mockMvc.perform(get("/api/v1/locations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));
        UUID hyderabadId = searchableLocationId(DemoDataCatalog.HYDERABAD_CITY);
        searchableLocationId(DemoDataCatalog.SURYAPET_CITY);
        UUID vijayawadaId = searchableLocationId(DemoDataCatalog.VIJAYAWADA_CITY);

        MvcResult search = mockMvc.perform(get("/api/v1/search/trips")
                        .param("originLocationId", hyderabadId.toString())
                        .param("destinationLocationId", vijayawadaId.toString())
                        .param("serviceDate", DemoDataCatalog.SERVICE_DATE.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].tripId").exists())
                .andExpect(jsonPath("$[0].availableSeatCount").value(4))
                .andReturn();
        JsonNode trip = objectMapper.readTree(search.getResponse().getContentAsString()).get(0);
        UUID tripId = UUID.fromString(trip.get("tripId").asText());
        UUID originStopId = UUID.fromString(trip.get("origin").get("tripStopId").asText());
        UUID destinationStopId = UUID.fromString(trip.get("destination").get("tripStopId").asText());
        assertThat(tripSeatInventoryRepository.countByTrip_Id(tripId)).isEqualTo(4);

        MvcResult availability = mockMvc.perform(get("/api/v1/trips/{tripId}/seat-availability", tripId)
                        .param("originStopId", originStopId.toString())
                        .param("destinationStopId", destinationStopId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seats.length()").value(4))
                .andReturn();
        UUID seatId = availableSeatId(objectMapper.readTree(availability.getResponse().getContentAsString()));

        String accessToken = loginDemoCustomer();
        MvcResult holdCreated = mockMvc.perform(post("/api/v1/trips/{tripId}/holds", tripId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "originStopId":"%s",
                                  "destinationStopId":"%s",
                                  "seatInventoryIds":["%s"]
                                }
                                """.formatted(originStopId, destinationStopId, seatId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn();
        UUID holdId = UUID.fromString(
                objectMapper.readTree(holdCreated.getResponse().getContentAsString()).get("holdId").asText());

        mockMvc.perform(get("/api/v1/holds/{holdId}", holdId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.holdId").value(holdId.toString()))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        MvcResult booking = mockMvc.perform(post("/api/v1/bookings")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "holdId":"%s",
                                  "originStopId":"%s",
                                  "destinationStopId":"%s",
                                  "idempotencyKey":"demo-e2e-book-1",
                                  "passengers":[{"seatInventoryId":"%s","fullName":"Demo Rider","age":30}]
                                }
                                """.formatted(holdId, originStopId, destinationStopId, seatId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andReturn();
        UUID bookingId = UUID.fromString(
                objectMapper.readTree(booking.getResponse().getContentAsString()).get("bookingId").asText());

        mockMvc.perform(post("/api/v1/bookings/{bookingId}/payments", bookingId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .header("Idempotency-Key", "demo-e2e-pay-1"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("Payment provider is temporarily unavailable."));

        assertThat(paymentAttemptRepository.count()).isZero();
        User demoUser = userRepository.findByEmailIgnoreCase(DemoDataCatalog.DEFAULT_CUSTOMER_EMAIL).orElseThrow();
        assertThat(userRoleRepository.findByUserIdWithRole(demoUser.getId()))
                .extracting(role -> role.getRole().getCode())
                .containsExactly(RoleCode.CUSTOMER);
    }

    private String loginDemoCustomer() throws Exception {
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}
                                """.formatted(DemoDataCatalog.DEFAULT_CUSTOMER_EMAIL, DEMO_PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(login.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private UUID searchableLocationId(String city) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/locations").param("city", city))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].city").value(city))
                .andReturn();
        return UUID.fromString(
                objectMapper.readTree(result.getResponse().getContentAsString()).get(0).get("id").asText());
    }

    private static UUID availableSeatId(JsonNode availability) {
        for (JsonNode seat : availability.get("seats")) {
            if ("AVAILABLE".equals(seat.get("physicalStatus").asText())
                    && "AVAILABLE".equals(seat.get("availability").asText())) {
                return UUID.fromString(seat.get("inventoryId").asText());
            }
        }
        throw new AssertionError("No available demo seat");
    }
}
