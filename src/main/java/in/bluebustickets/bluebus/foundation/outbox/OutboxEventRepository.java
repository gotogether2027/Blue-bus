package in.bluebustickets.bluebus.foundation.outbox;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    @Query("""
            select e.id from OutboxEvent e
            where e.publishedAt is null and e.eventType = :eventType
            order by e.occurredAt asc
            """)
    List<UUID> findUnpublishedIdsByEventType(
            @Param("eventType") String eventType,
            Pageable pageable);

    boolean existsByEventTypeAndAggregateId(String eventType, UUID aggregateId);

    long countByEventTypeAndAggregateId(String eventType, UUID aggregateId);
}
