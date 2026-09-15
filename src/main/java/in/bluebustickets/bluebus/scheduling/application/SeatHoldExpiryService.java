package in.bluebustickets.bluebus.scheduling.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import in.bluebustickets.bluebus.scheduling.domain.SeatHoldStatus;
import in.bluebustickets.bluebus.scheduling.repository.SeatHoldRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service that expires due ACTIVE seat holds and their HELD allocations.
 * Idempotent and restart-safe; PostgreSQL row locks remain authoritative.
 * <p>
 * SeatAvailabilityService still treats HELD as blocking until this explicit transition
 * commits {@code HELD → EXPIRED}.
 */
@Service
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class SeatHoldExpiryService {

    private static final Logger log = LoggerFactory.getLogger(SeatHoldExpiryService.class);

    private final SeatHoldRepository seatHoldRepository;
    private final SeatHoldExpiryProcessor seatHoldExpiryProcessor;
    private final SeatHoldExpiryProperties properties;
    private final Clock clock;

    public SeatHoldExpiryService(
            SeatHoldRepository seatHoldRepository,
            SeatHoldExpiryProcessor seatHoldExpiryProcessor,
            SeatHoldExpiryProperties properties,
            Clock clock) {
        this.seatHoldRepository = seatHoldRepository;
        this.seatHoldExpiryProcessor = seatHoldExpiryProcessor;
        this.properties = properties;
        this.clock = clock;
    }

    /** Expire due holds using the injected UTC clock. */
    public SeatHoldExpiryResult expireDueHolds() {
        return expireDueHolds(clock.instant());
    }

    /**
     * Find ACTIVE holds with {@code expires_at <= now} and expire them in bounded batches.
     * Each hold is processed in its own transaction ({@code REQUIRES_NEW} + SKIP LOCKED).
     */
    public SeatHoldExpiryResult expireDueHolds(Instant now) {
        if (now == null) {
            throw new IllegalArgumentException("Expiry now instant is required");
        }

        SeatHoldExpiryResult total = SeatHoldExpiryResult.empty();
        int batchSize = properties.getBatchSize();
        int failures = 0;

        while (true) {
            List<UUID> dueIds = seatHoldRepository.findDueHoldIds(
                    SeatHoldStatus.ACTIVE,
                    now,
                    PageRequest.of(0, batchSize));
            if (dueIds.isEmpty()) {
                break;
            }

            SeatHoldExpiryResult batchResult = SeatHoldExpiryResult.empty();
            int processedInBatch = 0;
            for (UUID holdId : dueIds) {
                try {
                    var expired = seatHoldExpiryProcessor.tryExpireDueHold(holdId, now);
                    if (expired.isPresent()) {
                        batchResult = batchResult.plus(expired.get());
                        processedInBatch++;
                    }
                } catch (RuntimeException exception) {
                    failures++;
                    log.warn("Failed to expire seat hold {}: {}", holdId, exception.getMessage());
                }
            }

            total = total.plus(batchResult);
            if (dueIds.size() < batchSize || processedInBatch == 0) {
                break;
            }
        }

        if (total.holdsExpired() > 0 || failures > 0) {
            log.info(
                    "Seat hold expiry pass complete: holdsExpired={}, allocationsExpired={}, failures={}",
                    total.holdsExpired(),
                    total.allocationsExpired(),
                    failures);
        }
        return total;
    }

    /**
     * Read-only helper for tests/ops: count currently due ACTIVE holds.
     */
    @Transactional(readOnly = true)
    public long countDueActiveHolds(Instant now) {
        return seatHoldRepository.findDueHoldIds(
                        SeatHoldStatus.ACTIVE,
                        now,
                        PageRequest.of(0, properties.getBatchSize()))
                .size();
    }
}
