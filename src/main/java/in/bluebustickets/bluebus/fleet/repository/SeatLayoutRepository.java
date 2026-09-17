package in.bluebustickets.bluebus.fleet.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import in.bluebustickets.bluebus.fleet.domain.SeatLayout;
import in.bluebustickets.bluebus.fleet.domain.SeatLayoutStatus;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SeatLayoutRepository extends JpaRepository<SeatLayout, UUID> {

    boolean existsByOperator_IdAndNameIgnoreCaseAndVersion(UUID operatorId, String name, int version);

    Optional<SeatLayout> findByOperator_IdAndNameIgnoreCaseAndVersion(UUID operatorId, String name, int version);

    Optional<SeatLayout> findByIdAndOperator_Id(UUID id, UUID operatorId);

    List<SeatLayout> findAllByOrderByNameAscVersionAsc();

    List<SeatLayout> findByOperator_IdOrderByNameAscVersionAsc(UUID operatorId);

    List<SeatLayout> findByStatusOrderByNameAscVersionAsc(SeatLayoutStatus status);

    List<SeatLayout> findByOperator_IdAndStatusOrderByNameAscVersionAsc(UUID operatorId, SeatLayoutStatus status);
}
