package in.bluebustickets.bluebus.payments.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Thin scheduler that delegates to {@link RefundRetryService}.
 * Disabled in tests via {@code blue-bus.payments.refund-retry.enabled=false}.
 */
@Component
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
@ConditionalOnProperty(prefix = "blue-bus.payments.refund-retry", name = "enabled", matchIfMissing = true)
public class RefundRetryScheduler {

    private static final Logger log = LoggerFactory.getLogger(RefundRetryScheduler.class);

    private final RefundRetryService refundRetryService;

    public RefundRetryScheduler(RefundRetryService refundRetryService) {
        this.refundRetryService = refundRetryService;
    }

    @Scheduled(fixedDelayString = "${blue-bus.payments.refund-retry.poll-interval-ms:5000}")
    public void processDueRefunds() {
        try {
            RefundRetryService.RefundRetryResult result = refundRetryService.processDueRefunds();
            if (result.claimed() > 0) {
                log.debug(
                        "Scheduled refund retry claimed={}, completed={}, failed={}",
                        result.claimed(),
                        result.completed(),
                        result.failed());
            }
        } catch (RuntimeException exception) {
            log.warn("Refund retry pass failed: {}", exception.getMessage());
        }
    }
}
