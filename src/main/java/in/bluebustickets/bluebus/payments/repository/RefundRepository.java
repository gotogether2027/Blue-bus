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

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Refund r where r.id = :id")
    Optional<Refund> findByIdForUpdate(@Param("id") UUID id);
}
