package in.bluebustickets.bluebus.scheduling.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import in.bluebustickets.bluebus.scheduling.domain.Route;
import in.bluebustickets.bluebus.scheduling.domain.RouteStatus;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RouteRepository extends JpaRepository<Route, UUID> {

    boolean existsByOperator_IdAndCodeIgnoreCase(UUID operatorId, String code);

    Optional<Route> findByOperator_IdAndCodeIgnoreCase(UUID operatorId, String code);

    List<Route> findAllByOrderByCodeAsc();

    List<Route> findByOperator_IdOrderByCodeAsc(UUID operatorId);

    List<Route> findByStatusOrderByCodeAsc(RouteStatus status);

    List<Route> findByOperator_IdAndStatusOrderByCodeAsc(UUID operatorId, RouteStatus status);

    Optional<Route> findByIdAndOperator_Id(UUID id, UUID operatorId);
}
