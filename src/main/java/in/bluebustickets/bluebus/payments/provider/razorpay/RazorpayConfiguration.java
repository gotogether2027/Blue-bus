package in.bluebustickets.bluebus.payments.provider.razorpay;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;

import com.fasterxml.jackson.databind.ObjectMapper;

@Configuration
@EnableConfigurationProperties(RazorpayProperties.class)
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
@Conditional(RazorpayConfiguration.RazorpayEnabledCondition.class)
public class RazorpayConfiguration {

    static final class RazorpayEnabledCondition implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            String value = context.getEnvironment().getProperty("blue-bus.payments.default-provider", "");
            return "RAZORPAY".equalsIgnoreCase(value.trim());
        }
    }

    @Bean
    RazorpayApiClient razorpayApiClient(RazorpayProperties properties, ObjectMapper objectMapper) {
        properties.requireConfigured();
        return new RazorpayApiClient(properties, objectMapper);
    }

    @Bean
    RazorpayPaymentProvider razorpayPaymentProvider(
            RazorpayProperties properties,
            RazorpayApiClient razorpayApiClient,
            ObjectMapper objectMapper) {
        properties.requireConfigured();
        return new RazorpayPaymentProvider(properties, razorpayApiClient, objectMapper);
    }
}
