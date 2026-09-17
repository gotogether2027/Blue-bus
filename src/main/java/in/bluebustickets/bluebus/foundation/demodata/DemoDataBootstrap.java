package in.bluebustickets.bluebus.foundation.demodata;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Starts the local demo catalog only when explicitly enabled.
 * Does not run in the default (production-safe) configuration.
 */
@Component
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
@ConditionalOnProperty(prefix = "blue-bus.demo-data", name = "enabled", havingValue = "true")
public class DemoDataBootstrap implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(DemoDataBootstrap.class);

    private final DemoDataService demoDataService;
    private final DemoDataProperties properties;

    public DemoDataBootstrap(DemoDataService demoDataService, DemoDataProperties properties) {
        this.demoDataService = demoDataService;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        demoDataService.ensureDemoData();
        LOGGER.info(
                "BLUE BUS local demo data is ready. Search {} -> {} on {}. Demo customer email: {}",
                DemoDataCatalog.HYDERABAD_CITY,
                DemoDataCatalog.VIJAYAWADA_CITY,
                DemoDataCatalog.SERVICE_DATE,
                properties.requireCustomerEmail());
    }
}
