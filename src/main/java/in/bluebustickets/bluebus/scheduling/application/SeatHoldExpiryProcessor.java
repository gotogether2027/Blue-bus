package in.bluebustickets.bluebus.scheduling.application;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import in.bluebustickets.bluebus.scheduling.domain.SeatHold;
import in.bluebustickets.bluebus.scheduling.domain.TripSeatAllocation;
import in.bluebustickets.bluebus.scheduling.domain.TripSeatAllocationState;
import in.bluebustickets.bluebus.scheduling.repository.TripSeatAllocationRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-hold expiry unit of work. Uses PostgreSQL {@code FOR UPDATE SKIP LOCKED}
 * so concurrent reapers and consume/cancel races stay database-authoritative.
 */
@Service
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class SeatHoldExpiryProcessor {

    private static final Logger log = LoggerFactory.getLogger(SeatHoldExpiryProcessor.class);

    @PersistenceContext
    private EntityManager entityManager;

    private final TripSeatAllocationRepository tripSeatAllocationRepository;

    public SeatHoldExpiryProcessor(TripSeatAllocationRepository tripSeatAllocationRepository) {
        this.tripSeatAllocationRepository = tripSeatAllocationRepository;
    }

    /**
     * Attempt to expire one due ACTIVE hold in a new transaction.
     * Returns empty when the hold is not due, not ACTIVE, or locked by another worker.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<SeatHoldExpiryResult> tryExpireDueHold(UUID holdId, Instant now) {
        Optional<SeatHold> locked = lockActiveDueHold(holdId, now);
        if (locked.isEmpty()) {
            return Optional.empty();
        }

        SeatHold hold = locked.get();
        List<TripSeatAllocation> allocations =
                tripSeatAllocationRepository.findByHoldIdOrderByCreatedAtAsc(holdId);

        for (TripSeatAllocation allocation : allocations) {
            TripSeatAllocationState state = allocation.getState();
            if (state == TripSeatAllocationState.BOOKED || state == TripSeatAllocationState.BLOCKED) {
                throw new IllegalStateException(
                        "Refusing to expire hold " + holdId
                                + " because allocation " + allocation.getId()
                                + " has unexpected state " + state);
            }
        }

        hold.expire();
        int allocationsExpired = 0;
        for (TripSeatAllocation allocation : allocations) {
            if (allocation.getState() == TripSeatAllocationState.HELD) {
                allocation.expire();
                allocationsExpired++;
            }
        }

        entityManager.flush();
        tripSeatAllocationRepository.saveAllAndFlush(allocations);

        log.debug(
                "Expired seat hold {} with {} allocation(s)",
                holdId,
                allocationsExpired);
        return Optional.of(new SeatHoldExpiryResult(1, allocationsExpired));
    }

    @SuppressWarnings("unchecked")
    private Optional<SeatHold> lockActiveDueHold(UUID holdId, Instant now) {
        List<SeatHold> rows = entityManager.createNativeQuery("""
                SELECT *
                FROM seat_holds
                WHERE id = :id
                  AND status = 'ACTIVE'
                  AND expires_at <= :now
                FOR UPDATE SKIP LOCKED
                """, SeatHold.class)
                .setParameter("id", holdId)
                .setParameter("now", Timestamp.from(now))
                .getResultList();
        return rows.stream().findFirst();
    }
}
