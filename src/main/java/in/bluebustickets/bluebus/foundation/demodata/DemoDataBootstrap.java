package in.bluebustickets.bluebus.foundation.demodata;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import in.bluebustickets.bluebus.foundation.config.ProductionConfigurationGuard;

/**
 * Starts the local demo catalog only when explicitly enabled.
 * The {@code prod} profile never loads this bean. ProductionConfigurationGuard also refuses
 * demo data when {@code BLUE_BUS_ENVIRONMENT=production} even without that profile.
 */
@Component
@Profile("!prod")
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
@ConditionalOnProperty(prefix = "blue-bus.demo-data", name = "enabled", havingValue = "true")
public class DemoDataBootstrap implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(DemoDataBootstrap.class);

    private final DemoDataService demoDataService;
    private final DemoDataProperties properties;
    private final Environment environment;

    public DemoDataBootstrap(
            DemoDataService demoDataService, DemoDataProperties properties, Environment environment) {
        this.demoDataService = demoDataService;
        this.properties = properties;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (ProductionConfigurationGuard.isProduction(environment)) {
            throw new IllegalStateException(ProductionConfigurationGuard.DEMO_DATA_IN_PROD);
        }
        demoDataService.ensureDemoData();
        LOGGER.info(
                "BLUE BUS local demo data is ready. Search {} -> {} on {}. Demo customer email: {}. Demo operator email: {}",
                DemoDataCatalog.HYDERABAD_CITY,
                DemoDataCatalog.VIJAYAWADA_CITY,
                DemoDataCatalog.SERVICE_DATE,
                properties.requireCustomerEmail(),
                DemoDataCatalog.DEFAULT_OPERATOR_EMAIL);
    }
}
