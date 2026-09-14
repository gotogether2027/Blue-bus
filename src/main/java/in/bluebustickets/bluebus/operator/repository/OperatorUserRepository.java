package in.bluebustickets.bluebus.operator.repository;
import in.bluebustickets.bluebus.operator.domain.OperatorUser;
import in.bluebustickets.bluebus.operator.domain.OperatorUserId;
import org.springframework.data.jpa.repository.JpaRepository;
public interface OperatorUserRepository extends JpaRepository<OperatorUser, OperatorUserId> { }
