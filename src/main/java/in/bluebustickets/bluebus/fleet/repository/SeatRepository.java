package in.bluebustickets.bluebus.fleet.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import in.bluebustickets.bluebus.fleet.domain.Seat;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SeatRepository extends JpaRepository<Seat, UUID> {

    List<Seat> findBySeatLayoutIdOrderByDeckNumberAscRowNumberAscColumnNumberAsc(UUID seatLayoutId);

    List<Seat> findBySeatLayoutIdInOrderByDeckNumberAscRowNumberAscColumnNumberAsc(Collection<UUID> seatLayoutIds);

    long countBySeatLayoutId(UUID seatLayoutId);
}
