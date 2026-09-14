package in.bluebustickets.bluebus.scheduling.repository;

import java.util.List;
import java.util.UUID;

import in.bluebustickets.bluebus.scheduling.domain.Location;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LocationRepository extends JpaRepository<Location, UUID> {

    List<Location> findByActiveOrderByStateAscCityAsc(boolean active);

    List<Location> findAllByOrderByStateAscCityAsc();

    @Query("""
            SELECT l FROM Location l
            WHERE (:active IS NULL OR l.active = :active)
              AND (:state IS NULL OR LOWER(l.state) LIKE LOWER(CONCAT('%', CAST(:state AS string), '%')))
              AND (:city IS NULL OR LOWER(l.city) LIKE LOWER(CONCAT('%', CAST(:city AS string), '%')))
            ORDER BY l.state ASC, l.city ASC
            """)
    List<Location> search(
            @Param("active") Boolean active,
            @Param("state") String state,
            @Param("city") String city);
}
