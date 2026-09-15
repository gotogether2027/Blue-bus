package in.bluebustickets.bluebus.scheduling.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import in.bluebustickets.bluebus.scheduling.domain.SeatHold;
import in.bluebustickets.bluebus.scheduling.domain.SeatHoldStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SeatHoldRepository extends JpaRepository<SeatHold, UUID> {

    List<SeatHold> findByTripIdOrderByCreatedAtDesc(UUID tripId);

    List<SeatHold> findByTripIdAndStatusOrderByCreatedAtDesc(UUID tripId, SeatHoldStatus status);

    Optional<SeatHold> findByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select h from SeatHold h where h.id = :id")
    Optional<SeatHold> findByIdForUpdate(@Param("id") UUID id);

    @Query("""
            select h.id from SeatHold h
            where h.status = :status and h.expiresAt <= :now
            order by h.expiresAt asc
            """)
    List<UUID> findDueHoldIds(
            @Param("status") SeatHoldStatus status,
            @Param("now") Instant now,
            Pageable pageable);
}
