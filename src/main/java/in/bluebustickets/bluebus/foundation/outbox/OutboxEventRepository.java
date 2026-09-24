package in.bluebustickets.bluebus.foundation.outbox;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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

    @Query("""
            select e.id from OutboxEvent e
            where e.rabbitPublishedAt is null
              and e.eventType = :eventType
              and (e.rabbitNextRetryAt is null or e.rabbitNextRetryAt <= :now)
            order by e.occurredAt asc
            """)
    List<UUID> findUnpublishedRabbitIdsByEventType(
            @Param("eventType") String eventType,
            @Param("now") java.time.Instant now,
            Pageable pageable);

    @Query(value = """
            SELECT e.id
            FROM outbox_events e
            WHERE e.event_type IN (:eventTypes)
              AND NOT EXISTS (
                  SELECT 1
                  FROM processed_events p
                  WHERE p.event_id = e.id
                    AND p.consumer_name = :consumerName
              )
            ORDER BY e.occurred_at ASC
            """, nativeQuery = true)
    List<UUID> findUnprocessedNotificationIds(
            @Param("eventTypes") Collection<String> eventTypes,
            @Param("consumerName") String consumerName,
            Pageable pageable);

    @Query("""
            select e.id from OutboxEvent e
            where e.rabbitPublishedAt is null
              and e.eventType in :eventTypes
              and (e.rabbitNextRetryAt is null or e.rabbitNextRetryAt <= :now)
            order by e.occurredAt asc
            """)
    List<UUID> findUnpublishedRabbitIdsByEventTypes(
            @Param("eventTypes") Collection<String> eventTypes,
            @Param("now") java.time.Instant now,
            Pageable pageable);

    /**
     * One {@code REFUND_REQUESTED} row per refund aggregate. Conflicts are no-ops.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT INTO outbox_events (
                id, event_type, aggregate_type, aggregate_id, schema_version,
                correlation_id, causation_id, payload_json, occurred_at, published_at,
                attempt_count, created_at
            ) VALUES (
                :id, 'REFUND_REQUESTED', 'REFUND', :aggregateId, 1,
                :correlationId, :causationId, :payloadJson, :occurredAt, NULL,
                0, :createdAt
            )
            ON CONFLICT (aggregate_id) WHERE (event_type = 'REFUND_REQUESTED') DO NOTHING
            """, nativeQuery = true)
    int insertRefundRequestedIfAbsent(
            @Param("id") UUID id,
            @Param("aggregateId") UUID aggregateId,
            @Param("correlationId") String correlationId,
            @Param("causationId") String causationId,
            @Param("payloadJson") String payloadJson,
            @Param("occurredAt") java.time.Instant occurredAt,
            @Param("createdAt") java.time.Instant createdAt);

    /**
     * One {@code REFUND_FAILED} row per refund aggregate. Conflicts are no-ops.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT INTO outbox_events (
                id, event_type, aggregate_type, aggregate_id, schema_version,
                correlation_id, causation_id, payload_json, occurred_at, published_at,
                attempt_count, created_at
            ) VALUES (
                :id, 'REFUND_FAILED', 'REFUND', :aggregateId, 1,
                :correlationId, :causationId, :payloadJson, :occurredAt, NULL,
                0, :createdAt
            )
            ON CONFLICT (aggregate_id) WHERE (event_type = 'REFUND_FAILED') DO NOTHING
            """, nativeQuery = true)
    int insertRefundFailedIfAbsent(
            @Param("id") UUID id,
            @Param("aggregateId") UUID aggregateId,
            @Param("correlationId") String correlationId,
            @Param("causationId") String causationId,
            @Param("payloadJson") String payloadJson,
            @Param("occurredAt") java.time.Instant occurredAt,
            @Param("createdAt") java.time.Instant createdAt);

    boolean existsByEventTypeAndAggregateId(String eventType, UUID aggregateId);

    long countByEventTypeAndAggregateId(String eventType, UUID aggregateId);
}
