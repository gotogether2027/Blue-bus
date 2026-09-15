package in.bluebustickets.bluebus.payments.provider;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class PaymentProviderRegistry {

    private final Map<String, PaymentProvider> providers;

    public PaymentProviderRegistry(List<PaymentProvider> providers) {
        this.providers = providers.stream().collect(Collectors.toUnmodifiableMap(
                provider -> normalize(provider.providerCode()),
                Function.identity()));
    }

    public PaymentProvider require(String providerCode) {
        PaymentProvider provider = providers.get(normalize(providerCode));
        if (provider == null) {
            throw new PaymentProviderUnavailableException("Payment provider is not configured.");
        }
        return provider;
    }

    private static String normalize(String providerCode) {
        return providerCode == null ? "" : providerCode.trim().toUpperCase(Locale.ROOT);
    }
}
