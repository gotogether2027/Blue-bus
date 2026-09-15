package in.bluebustickets.bluebus.fleet.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import in.bluebustickets.bluebus.fleet.domain.Bus;
import in.bluebustickets.bluebus.fleet.domain.BusStatus;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BusRepository extends JpaRepository<Bus, UUID> {

    boolean existsByRegistrationNumberIgnoreCase(String registrationNumber);

    List<Bus> findAllByOrderByRegistrationNumberAsc();

    List<Bus> findByOperator_IdOrderByRegistrationNumberAsc(UUID operatorId);

    List<Bus> findByStatusOrderByRegistrationNumberAsc(BusStatus status);

    List<Bus> findByOperator_IdAndStatusOrderByRegistrationNumberAsc(UUID operatorId, BusStatus status);

    Optional<Bus> findByIdAndOperator_Id(UUID id, UUID operatorId);
}
