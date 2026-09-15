package in.bluebustickets.bluebus.scheduling.application;

import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Thin Spring scheduler that delegates to {@link SeatHoldExpiryService}.
 * Disabled in tests via {@code blue-bus.seat-holds.expiry.enabled=false}.
 */
@Component
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
@ConditionalOnProperty(prefix = "blue-bus.seat-holds.expiry", name = "enabled", matchIfMissing = true)
public class SeatHoldExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(SeatHoldExpiryScheduler.class);

    private final SeatHoldExpiryService seatHoldExpiryService;
    private final Clock clock;

    public SeatHoldExpiryScheduler(SeatHoldExpiryService seatHoldExpiryService, Clock clock) {
        this.seatHoldExpiryService = seatHoldExpiryService;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${blue-bus.seat-holds.expiry.reaper-interval-ms:30000}")
    public void reapExpiredHolds() {
        SeatHoldExpiryResult result = seatHoldExpiryService.expireDueHolds(clock.instant());
        if (result.holdsExpired() > 0) {
            log.debug(
                    "Scheduled seat hold reaper expired {} hold(s), {} allocation(s)",
                    result.holdsExpired(),
                    result.allocationsExpired());
        }
    }
}
