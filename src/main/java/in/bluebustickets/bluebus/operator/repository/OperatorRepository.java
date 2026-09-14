package in.bluebustickets.bluebus.operator.repository;

import java.util.List;
import java.util.UUID;

import in.bluebustickets.bluebus.operator.domain.Operator;
import in.bluebustickets.bluebus.operator.domain.OperatorStatus;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OperatorRepository extends JpaRepository<Operator, UUID> {

    List<Operator> findAllByOrderByDisplayNameAsc();

    List<Operator> findByStatusOrderByDisplayNameAsc(OperatorStatus status);
}
