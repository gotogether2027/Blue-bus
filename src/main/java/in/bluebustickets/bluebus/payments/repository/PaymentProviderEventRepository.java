package in.bluebustickets.bluebus.payments.repository;

import java.util.Optional;
import java.util.UUID;

import in.bluebustickets.bluebus.payments.domain.PaymentProviderEvent;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentProviderEventRepository extends JpaRepository<PaymentProviderEvent, UUID> {

    Optional<PaymentProviderEvent> findByProviderAndProviderEventId(String provider, String providerEventId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from PaymentProviderEvent e where e.id = :id")
    Optional<PaymentProviderEvent> findByIdForUpdate(@Param("id") UUID id);
}
