package in.bluebustickets.bluebus.booking.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import in.bluebustickets.bluebus.booking.domain.Booking;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BookingRepository extends JpaRepository<Booking, UUID> {

    Optional<Booking> findByHoldId(UUID holdId);

    Optional<Booking> findByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey);

    @Query("""
            select distinct b from Booking b
            left join fetch b.passengers
            left join fetch b.items i
            left join fetch i.passenger
            where b.id = :id
            """)
    Optional<Booking> findDetailedById(@Param("id") UUID id);

    @Query("""
            select distinct b from Booking b
            left join fetch b.passengers
            left join fetch b.items i
            left join fetch i.passenger
            where b.userId = :userId
            order by b.createdAt desc
            """)
    List<Booking> findDetailedByUserIdOrderByCreatedAtDesc(@Param("userId") UUID userId);
}
