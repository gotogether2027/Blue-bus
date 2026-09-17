package in.bluebustickets.bluebus.payments.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Thin scheduler that delegates to {@link InitiatingPaymentRecoveryService}.
 * Disabled in tests via {@code blue-bus.payments.initiating-recovery.enabled=false}.
 */
@Component
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
@ConditionalOnProperty(prefix = "blue-bus.payments.initiating-recovery", name = "enabled", matchIfMissing = true)
public class InitiatingPaymentRecoveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(InitiatingPaymentRecoveryScheduler.class);

    private final InitiatingPaymentRecoveryService recoveryService;

    public InitiatingPaymentRecoveryScheduler(InitiatingPaymentRecoveryService recoveryService) {
        this.recoveryService = recoveryService;
    }

    @Scheduled(fixedDelayString = "${blue-bus.payments.initiating-recovery.poll-interval-ms:5000}")
    public void processDueRecoveries() {
        try {
            InitiatingPaymentRecoveryService.InitiatingPaymentRecoveryResult result =
                    recoveryService.processDueRecoveries();
            if (result.claimed() > 0) {
                log.debug(
                        "Scheduled initiating recovery claimed={}, completed={}, failed={}",
                        result.claimed(),
                        result.completed(),
                        result.failed());
            }
        } catch (RuntimeException exception) {
            log.warn("Initiating recovery pass failed: {}", exception.getMessage());
        }
    }
}
