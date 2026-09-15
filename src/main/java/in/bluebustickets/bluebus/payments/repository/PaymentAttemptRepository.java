package in.bluebustickets.bluebus.payments.repository;

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

    boolean existsByBookingIdAndStatusIn(UUID bookingId, List<PaymentStatus> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PaymentAttempt p where p.id = :id")
    Optional<PaymentAttempt> findByIdForUpdate(@Param("id") UUID id);
}
