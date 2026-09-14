package in.bluebustickets.bluebus.fleet.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import in.bluebustickets.bluebus.fleet.domain.BusType;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BusTypeRepository extends JpaRepository<BusType, UUID> {

    boolean existsByCodeIgnoreCase(String code);

    Optional<BusType> findByCodeIgnoreCase(String code);

    List<BusType> findByActiveOrderByCodeAsc(boolean active);

    List<BusType> findAllByOrderByCodeAsc();
}
