package in.bluebustickets.bluebus.ticket.repository;

import java.util.Optional;
import java.util.UUID;

import in.bluebustickets.bluebus.ticket.domain.Ticket;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TicketRepository extends JpaRepository<Ticket, UUID> {

    Optional<Ticket> findByBookingId(UUID bookingId);

    boolean existsByBookingId(UUID bookingId);

    @Query("""
            select distinct t from Ticket t
            left join fetch t.passengers
            where t.id = :id and t.userId = :userId
            """)
    Optional<Ticket> findDetailedByIdAndUserId(
            @Param("id") UUID id,
            @Param("userId") UUID userId);

    @Query("""
            select distinct t from Ticket t
            left join fetch t.passengers
            where t.bookingId = :bookingId
            """)
    Optional<Ticket> findDetailedByBookingId(@Param("bookingId") UUID bookingId);

    @Query("""
            select distinct t from Ticket t
            left join fetch t.passengers
            where t.id = :id
            """)
    Optional<Ticket> findDetailedById(@Param("id") UUID id);
}
