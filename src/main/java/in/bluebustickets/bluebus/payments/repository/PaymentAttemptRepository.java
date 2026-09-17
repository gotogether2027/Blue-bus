package in.bluebustickets.bluebus.payments.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import in.bluebustickets.bluebus.payments.domain.PaymentAttempt;
import in.bluebustickets.bluebus.payments.domain.PaymentStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentAttemptRepository extends JpaRepository<PaymentAttempt, UUID> {

    Optional<PaymentAttempt> findByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey);

    Optional<PaymentAttempt> findByProviderAndMerchantReference(String provider, String merchantReference);

    Optional<PaymentAttempt> findByProviderAndProviderOrderId(String provider, String providerOrderId);

    Optional<PaymentAttempt> findByProviderAndProviderPaymentId(String provider, String providerPaymentId);

    List<PaymentAttempt> findByBookingIdOrderByCreatedAtDesc(UUID bookingId);

    List<PaymentAttempt> findByBookingIdInOrderByCreatedAtDescIdDesc(Collection<UUID> bookingIds);

    boolean existsByBookingIdAndStatusIn(UUID bookingId, List<PaymentStatus> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PaymentAttempt p where p.id = :id")
    Optional<PaymentAttempt> findByIdForUpdate(@Param("id") UUID id);

    @Query("""
            select p.id from PaymentAttempt p
            where p.status = :status
              and p.providerOrderId is null
              and (p.nextRetryAt is null or p.nextRetryAt <= :now)
              and p.createdAt <= :staleBefore
            order by p.createdAt asc
            """)
    List<UUID> findDueInitiatingRecoveryIds(
            @Param("status") PaymentStatus status,
            @Param("now") java.time.Instant now,
            @Param("staleBefore") java.time.Instant staleBefore,
            org.springframework.data.domain.Pageable pageable);
}
