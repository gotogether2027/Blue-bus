package in.bluebustickets.bluebus.notification.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import in.bluebustickets.bluebus.notification.domain.Notification;
import in.bluebustickets.bluebus.notification.domain.NotificationChannel;
import in.bluebustickets.bluebus.notification.domain.NotificationEventType;
import in.bluebustickets.bluebus.notification.domain.NotificationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    Page<Notification> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    Optional<Notification> findByUserIdAndEventTypeAndChannelAndSourceEventId(
            UUID userId,
            NotificationEventType eventType,
            NotificationChannel channel,
            UUID sourceEventId);

    boolean existsByUserIdAndEventTypeAndChannelAndLogicalKey(
            UUID userId,
            NotificationEventType eventType,
            NotificationChannel channel,
            String logicalKey);

    long countByUserId(UUID userId);

    /**
     * Idempotent insert. Either unique index conflict is a duplicate, not a failure.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT INTO notifications (
                id, user_id, source_event_id, event_type, channel, template_code, logical_key,
                subject, body, payload_json, status, attempt_count, created_at
            ) VALUES (
                :id, :userId, :sourceEventId, :eventType, :channel, :templateCode, :logicalKey,
                :subject, :body, :payloadJson, 'PENDING', 0, :createdAt
            )
            ON CONFLICT DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(
            @Param("id") UUID id,
            @Param("userId") UUID userId,
            @Param("sourceEventId") UUID sourceEventId,
            @Param("eventType") String eventType,
            @Param("channel") String channel,
            @Param("templateCode") String templateCode,
            @Param("logicalKey") String logicalKey,
            @Param("subject") String subject,
            @Param("body") String body,
            @Param("payloadJson") String payloadJson,
            @Param("createdAt") Instant createdAt);

    @Query("""
            select n.id from Notification n
            where n.status = :status
              and (n.nextRetryAt is null or n.nextRetryAt <= :now)
            order by n.createdAt asc
            """)
    List<UUID> findDueDeliveryIds(
            @Param("status") NotificationStatus status,
            @Param("now") Instant now,
            Pageable pageable);
}
