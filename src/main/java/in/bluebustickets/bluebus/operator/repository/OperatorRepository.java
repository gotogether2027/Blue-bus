package in.bluebustickets.bluebus.operator.repository;
import java.util.UUID;
import in.bluebustickets.bluebus.operator.domain.Operator;
import org.springframework.data.jpa.repository.JpaRepository;
public interface OperatorRepository extends JpaRepository<Operator, UUID> { }
