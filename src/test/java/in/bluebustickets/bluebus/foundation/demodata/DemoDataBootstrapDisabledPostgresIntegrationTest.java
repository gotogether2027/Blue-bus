package in.bluebustickets.bluebus.foundation.demodata;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DemoDataBootstrapDisabledPostgresIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("blue-bus.demo-data.enabled", () -> "false");
    }

    @Autowired private ApplicationContext applicationContext;
    @Autowired private MockMvc mockMvc;

    @Test
    void demoBootstrapIsDisabledByDefaultAndCreatesNoCatalog() throws Exception {
        assertThat(applicationContext.getBeanNamesForType(DemoDataBootstrap.class)).isEmpty();
        assertThat(applicationContext.getBeanNamesForType(DemoDataService.class)).isEmpty();
        mockMvc.perform(get("/api/v1/locations").param("city", DemoDataCatalog.HYDERABAD_CITY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(get("/api/v1/search/trips")
                        .param("originLocationId", "00000000-0000-0000-0000-000000000001")
                        .param("destinationLocationId", "00000000-0000-0000-0000-000000000002")
                        .param("serviceDate", DemoDataCatalog.SERVICE_DATE.toString()))
                .andExpect(status().isNotFound());
    }
}
