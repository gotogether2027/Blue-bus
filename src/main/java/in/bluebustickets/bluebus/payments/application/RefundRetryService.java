package in.bluebustickets.bluebus.payments.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import in.bluebustickets.bluebus.payments.domain.RefundStatus;
import in.bluebustickets.bluebus.payments.repository.RefundRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * Discovers due {@code refunds} rows and completes provider refunds outside booking locks.
 * Restart-safe; PostgreSQL {@code SKIP LOCKED} and Razorpay refund-id idempotency are authoritative.
 */
@Service
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class RefundRetryService {

    private static final Logger log = LoggerFactory.getLogger(RefundRetryService.class);
    private static final List<RefundStatus> DUE_STATUSES = List.of(
            RefundStatus.REQUESTED,
            RefundStatus.PROCESSING,
            RefundStatus.FAILED);

    private final RefundRepository refundRepository;
    private final RefundRetryProcessor processor;
    private final RefundApplicationService refundApplicationService;
    private final RefundRetryProperties properties;
    private final Clock clock;

    public RefundRetryService(
            RefundRepository refundRepository,
            RefundRetryProcessor processor,
            RefundApplicationService refundApplicationService,
            RefundRetryProperties properties,
            Clock clock) {
        this.refundRepository = refundRepository;
        this.processor = processor;
        this.refundApplicationService = refundApplicationService;
        this.properties = properties;
        this.clock = clock;
    }

    public RefundRetryResult processDueRefunds() {
        return processDueRefunds(clock.instant());
    }

    public RefundRetryResult processDueRefunds(Instant now) {
        if (now == null) {
            throw new IllegalArgumentException("now instant is required");
        }

        int claimed = 0;
        int completed = 0;
        int failed = 0;
        int batchSize = properties.getBatchSize();

        while (true) {
            List<UUID> dueIds = refundRepository.findDueRetryIds(
                    DUE_STATUSES, now, PageRequest.of(0, batchSize));
            if (dueIds.isEmpty()) {
                break;
            }

            int claimedInBatch = 0;
            for (UUID refundId : dueIds) {
                try {
                    if (processor.tryClaimDueRefund(refundId, now).isEmpty()) {
                        continue;
                    }
                    claimed++;
                    claimedInBatch++;
                    refundApplicationService.executeProviderRefund(refundId);
                    var refund = refundRepository.findById(refundId).orElse(null);
                    if (refund != null && RefundRetryProcessor.isRetryable(refund)) {
                        processor.scheduleBackoff(refundId, clock.instant());
                        failed++;
                    } else {
                        completed++;
                    }
                } catch (RuntimeException exception) {
                    failed++;
                    log.warn("Provider refund retry failed for {}: {}", refundId, exception.getMessage());
                    try {
                        processor.scheduleBackoff(refundId, clock.instant());
                    } catch (RuntimeException backoffFailure) {
                        log.warn(
                                "Failed to schedule refund backoff for {}: {}",
                                refundId,
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
                    "Refund retry pass complete: claimed={}, completed={}, failed={}",
                    claimed,
                    completed,
                    failed);
        }
        return new RefundRetryResult(claimed, completed, failed);
    }

    public record RefundRetryResult(int claimed, int completed, int failed) {
    }
}
