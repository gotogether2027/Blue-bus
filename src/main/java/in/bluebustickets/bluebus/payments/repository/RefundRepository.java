package in.bluebustickets.bluebus.payments.repository;

import java.util.UUID;

import in.bluebustickets.bluebus.payments.domain.Refund;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefundRepository extends JpaRepository<Refund, UUID> {
}
