package in.bluebustickets.bluebus.payments.application;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import in.bluebustickets.bluebus.foundation.api.error.ResourceNotFoundException;
import in.bluebustickets.bluebus.payments.domain.PaymentProviderEvent;
import in.bluebustickets.bluebus.payments.provider.PaymentProvider.VerifiedProviderEvent;
import in.bluebustickets.bluebus.payments.repository.PaymentProviderEventRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class ProviderEventIngressService {

    private final JdbcTemplate jdbcTemplate;
    private final PaymentProviderEventRepository eventRepository;

    public ProviderEventIngressService(
            JdbcTemplate jdbcTemplate,
            PaymentProviderEventRepository eventRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.eventRepository = eventRepository;
    }

    /**
     * Durable inbox insertion. ON CONFLICT avoids poisoning the transaction on duplicate delivery.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public IngressResult record(
            String provider,
            VerifiedProviderEvent event,
            Instant receivedAt,
            String payloadHash) {
        UUID id = UUID.randomUUID();
        int inserted = jdbcTemplate.update("""
                INSERT INTO payment_provider_events (
                    id, provider, provider_event_id, event_type, merchant_reference,
                    provider_order_id, provider_payment_id, amount, currency,
                    provider_status, failure_code, processing_status,
                    signature_verified, provider_occurred_at, received_at, payload_hash,
                    attempt_count, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'RECEIVED', TRUE, ?, ?, ?, 0, ?, ?)
                ON CONFLICT (provider, provider_event_id) DO NOTHING
                """,
                id,
                provider,
                event.providerEventId(),
                event.eventType().name(),
                event.merchantReference(),
                event.providerOrderId(),
                event.providerPaymentId(),
                event.amount(),
                event.currency(),
                event.providerStatus(),
                event.failureCode(),
                event.providerOccurredAt() == null ? null : Timestamp.from(event.providerOccurredAt()),
                Timestamp.from(receivedAt),
                payloadHash,
                Timestamp.from(receivedAt),
                Timestamp.from(receivedAt));

        PaymentProviderEvent stored = eventRepository
                .findByProviderAndProviderEventId(provider, event.providerEventId())
                .orElseThrow(() -> new ResourceNotFoundException("Provider event was not recorded."));
        return new IngressResult(stored.getId(), inserted == 0);
    }

    public record IngressResult(UUID eventId, boolean duplicate) {
    }
}
