package in.bluebustickets.bluebus.foundation.demodata;

import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import in.bluebustickets.bluebus.foundation.config.ProductionConfigurationGuard;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DemoDataBootstrapTest {

    @Test
    void refusesToSeedWhenProdProfileIsActive() {
        DemoDataService demoDataService = mock(DemoDataService.class);
        DemoDataProperties properties = mock(DemoDataProperties.class);
        Environment environment = mock(Environment.class);
        when(environment.acceptsProfiles(any(Profiles.class))).thenReturn(true);

        DemoDataBootstrap bootstrap = new DemoDataBootstrap(demoDataService, properties, environment);

        assertThatThrownBy(() -> bootstrap.run(mock(ApplicationArguments.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(ProductionConfigurationGuard.DEMO_DATA_IN_PROD);

        verify(demoDataService, never()).ensureDemoData();
    }

    @Test
    void refusesToSeedWhenEnvironmentMarkerIsProduction() {
        DemoDataService demoDataService = mock(DemoDataService.class);
        DemoDataProperties properties = mock(DemoDataProperties.class);
        Environment environment = mock(Environment.class);
        when(environment.acceptsProfiles(any(Profiles.class))).thenReturn(false);
        when(environment.getProperty(ProductionConfigurationGuard.ENVIRONMENT_PROPERTY)).thenReturn("production");

        DemoDataBootstrap bootstrap = new DemoDataBootstrap(demoDataService, properties, environment);

        assertThatThrownBy(() -> bootstrap.run(mock(ApplicationArguments.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(ProductionConfigurationGuard.DEMO_DATA_IN_PROD);

        verify(demoDataService, never()).ensureDemoData();
    }
}
