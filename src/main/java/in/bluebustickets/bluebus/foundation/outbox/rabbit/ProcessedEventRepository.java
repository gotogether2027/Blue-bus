package in.bluebustickets.bluebus.foundation.outbox.rabbit;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, ProcessedEvent.Id> {

    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT INTO processed_events (event_id, consumer_name, processed_at)
            VALUES (:eventId, :consumerName, :processedAt)
            ON CONFLICT (event_id, consumer_name) DO NOTHING
            """, nativeQuery = true)
    int tryInsert(
            @Param("eventId") UUID eventId,
            @Param("consumerName") String consumerName,
            @Param("processedAt") Instant processedAt);

    boolean existsByEventIdAndConsumerName(UUID eventId, String consumerName);
}
