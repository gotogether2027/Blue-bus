package in.bluebustickets.bluebus.booking.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import in.bluebustickets.bluebus.booking.domain.Booking;
import in.bluebustickets.bluebus.booking.domain.BookingStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BookingRepository extends JpaRepository<Booking, UUID> {

    Optional<Booking> findByHoldId(UUID holdId);

    Optional<Booking> findByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey);

    boolean existsByTripIdAndStatusIn(UUID tripId, Collection<BookingStatus> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Booking b where b.id = :id")
    Optional<Booking> findByIdForUpdate(@Param("id") UUID id);

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
            where b.id = :id and b.userId = :userId
            """)
    Optional<Booking> findDetailedByIdAndUserId(
            @Param("id") UUID id,
            @Param("userId") UUID userId);

    @Query("""
            select distinct b from Booking b
            left join fetch b.passengers
            left join fetch b.items i
            left join fetch i.passenger
            where b.userId = :userId
            order by b.createdAt desc
            """)
    List<Booking> findDetailedByUserIdOrderByCreatedAtDesc(@Param("userId") UUID userId);

    @Query("""
            select distinct b from Booking b
            left join fetch b.passengers
            left join fetch b.items i
            left join fetch i.passenger
            where b.tripId = :tripId and b.operatorId = :operatorId
            order by b.createdAt desc
            """)
    List<Booking> findDetailedByTripIdAndOperatorId(
            @Param("tripId") UUID tripId,
            @Param("operatorId") UUID operatorId);

    @Query("""
            select distinct b from Booking b
            left join fetch b.passengers
            left join fetch b.items i
            left join fetch i.passenger
            where b.id = :id and b.tripId = :tripId and b.operatorId = :operatorId
            """)
    Optional<Booking> findDetailedByIdAndTripIdAndOperatorId(
            @Param("id") UUID id,
            @Param("tripId") UUID tripId,
            @Param("operatorId") UUID operatorId);

    @Query("""
            select b.id from Booking b
            where b.status = :status and b.paymentExpiresAt <= :now
            order by b.paymentExpiresAt asc
            """)
    List<UUID> findDueUnpaidBookingIds(
            @Param("status") BookingStatus status,
            @Param("now") Instant now,
            Pageable pageable);
}
