package in.bluebustickets.bluebus.payments.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import in.bluebustickets.bluebus.payments.domain.PaymentAttempt;
import in.bluebustickets.bluebus.payments.domain.PaymentStatus;
import in.bluebustickets.bluebus.payments.repository.PaymentAttemptRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * Discovers stale INITIATING payment attempts and completes Razorpay order creation
 * outside booking locks. Never confirms a booking. Restart-safe: PostgreSQL
 * {@code SKIP LOCKED} plus Razorpay {@code payment_attempt_id} idempotency.
 */
@Service
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class InitiatingPaymentRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(InitiatingPaymentRecoveryService.class);

    private final PaymentAttemptRepository paymentAttemptRepository;
    private final InitiatingPaymentRecoveryProcessor processor;
    private final PaymentInitiationService paymentInitiationService;
    private final InitiatingPaymentRecoveryProperties properties;
    private final Clock clock;

    public InitiatingPaymentRecoveryService(
            PaymentAttemptRepository paymentAttemptRepository,
            InitiatingPaymentRecoveryProcessor processor,
            PaymentInitiationService paymentInitiationService,
            InitiatingPaymentRecoveryProperties properties,
            Clock clock) {
        this.paymentAttemptRepository = paymentAttemptRepository;
        this.processor = processor;
        this.paymentInitiationService = paymentInitiationService;
        this.properties = properties;
        this.clock = clock;
    }

    public InitiatingPaymentRecoveryResult processDueRecoveries() {
        return processDueRecoveries(clock.instant());
    }

    public InitiatingPaymentRecoveryResult processDueRecoveries(Instant now) {
        if (now == null) {
            throw new IllegalArgumentException("now instant is required");
        }

        int claimed = 0;
        int completed = 0;
        int failed = 0;
        int batchSize = properties.getBatchSize();

        while (true) {
            Instant staleBefore = processor.staleBefore(now);
            List<UUID> dueIds = paymentAttemptRepository.findDueInitiatingRecoveryIds(
                    PaymentStatus.INITIATING, now, staleBefore, PageRequest.of(0, batchSize));
            if (dueIds.isEmpty()) {
                break;
            }

            int claimedInBatch = 0;
            for (UUID attemptId : dueIds) {
                try {
                    if (processor.tryClaimDueAttempt(attemptId, now).isEmpty()) {
                        continue;
                    }
                    claimed++;
                    claimedInBatch++;
                    paymentInitiationService.recoverProviderOrder(attemptId);
                    PaymentAttempt attempt = paymentAttemptRepository.findById(attemptId).orElse(null);
                    if (attempt != null && InitiatingPaymentRecoveryProcessor.isRecoverable(attempt)) {
                        processor.scheduleBackoff(attemptId, clock.instant());
                        failed++;
                        log.warn(
                                "Initiating recovery left attempt recoverable: paymentAttemptId={} bookingId={} provider={} attemptCount={}",
                                attempt.getId(),
                                attempt.getBookingId(),
                                attempt.getProvider(),
                                attempt.getAttemptCount());
                    } else {
                        completed++;
                        if (attempt != null) {
                            log.info(
                                    "Initiating recovery result=applied paymentAttemptId={} bookingId={} provider={} providerOrderId={} attemptCount={} status={}",
                                    attempt.getId(),
                                    attempt.getBookingId(),
                                    attempt.getProvider(),
                                    attempt.getProviderOrderId(),
                                    attempt.getAttemptCount(),
                                    attempt.getStatus());
                        }
                    }
                } catch (RuntimeException exception) {
                    failed++;
                    log.warn(
                            "Initiating recovery provider call failed for paymentAttemptId={}: {}",
                            attemptId,
                            exception.getMessage());
                    try {
                        processor.scheduleBackoff(attemptId, clock.instant());
                    } catch (RuntimeException backoffFailure) {
                        log.warn(
                                "Failed to schedule initiating recovery backoff for {}: {}",
                                attemptId,
                                backoffFailure.getMessage());
                    }
                }
            }

            if (dueIds.size() < batchSize || claimedInBatch == 0) {
                break;
            }
            now = clock.instant();
        }

        if (claimed > 0 || failed > 0) {
            log.info(
                    "Initiating recovery pass complete: claimed={}, completed={}, failed={}",
                    claimed,
                    completed,
                    failed);
        }
        return new InitiatingPaymentRecoveryResult(claimed, completed, failed);
    }

    public record InitiatingPaymentRecoveryResult(int claimed, int completed, int failed) {
    }
}
