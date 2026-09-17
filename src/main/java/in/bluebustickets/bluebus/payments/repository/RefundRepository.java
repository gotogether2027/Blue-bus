package in.bluebustickets.bluebus.payments.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import in.bluebustickets.bluebus.payments.domain.Refund;
import in.bluebustickets.bluebus.payments.domain.RefundStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefundRepository extends JpaRepository<Refund, UUID> {

    Optional<Refund> findByPaymentAttemptIdAndIdempotencyKey(UUID paymentAttemptId, String idempotencyKey);

    Optional<Refund> findByProviderAndProviderRefundId(String provider, String providerRefundId);

    List<Refund> findByPaymentAttemptIdAndStatusIn(UUID paymentAttemptId, List<RefundStatus> statuses);

    List<Refund> findByPaymentAttemptIdOrderByCreatedAtDesc(UUID paymentAttemptId);

    List<Refund> findByBookingIdOrderByCreatedAtDesc(UUID bookingId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Refund r where r.id = :id")
    Optional<Refund> findByIdForUpdate(@Param("id") UUID id);

    @Query("""
            select r.id from Refund r
            where r.providerRefundId is null
              and r.status in :statuses
              and (r.nextRetryAt is null or r.nextRetryAt <= :now)
            order by r.createdAt asc
            """)
    List<UUID> findDueRetryIds(
            @Param("statuses") List<RefundStatus> statuses,
            @Param("now") java.time.Instant now,
            org.springframework.data.domain.Pageable pageable);
}
