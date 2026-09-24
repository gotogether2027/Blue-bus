package in.bluebustickets.bluebus.notification.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "notifications")
public class Notification {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "source_event_id", nullable = false, updatable = false)
    private UUID sourceEventId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 100, updatable = false)
    private NotificationEventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private NotificationChannel channel;

    @Column(name = "template_code", nullable = false, length = 100, updatable = false)
    private String templateCode;

    @Column(name = "logical_key", nullable = false, length = 150, updatable = false)
    private String logicalKey;

    @Column(length = 255)
    private String subject;

    @Column(nullable = false, columnDefinition = "text")
    private String body;

    @Column(name = "payload_json", nullable = false, columnDefinition = "text", updatable = false)
    private String payloadJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private NotificationStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_retry_at")
    private Instant nextRetryAt;

    @Column(name = "provider_message_id", length = 150)
    private String providerMessageId;

    @Column(name = "failure_code", length = 100)
    private String failureCode;

    @Column(name = "failure_message", length = 500)
    private String failureMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    protected Notification() {
    }

    public Notification(
            UUID userId,
            UUID sourceEventId,
            NotificationEventType eventType,
            NotificationChannel channel,
            String templateCode,
            String logicalKey,
            String subject,
            String body,
            String payloadJson,
            Instant createdAt) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.sourceEventId = sourceEventId;
        this.eventType = eventType;
        this.channel = channel;
        this.templateCode = templateCode;
        this.logicalKey = logicalKey;
        this.subject = subject;
        this.body = body;
        this.payloadJson = payloadJson;
        this.status = NotificationStatus.PENDING;
        this.attemptCount = 0;
        this.createdAt = createdAt;
    }

    public void claimForDelivery(Instant leaseUntil) {
        if (status != NotificationStatus.PENDING) {
            return;
        }
        this.attemptCount++;
        this.nextRetryAt = leaseUntil;
    }

    public void markSent(String providerMessageId, Instant sentAt) {
        this.status = NotificationStatus.SENT;
        this.sentAt = sentAt;
        this.nextRetryAt = null;
        this.providerMessageId = providerMessageId;
        this.failureCode = null;
        this.failureMessage = null;
    }

    public void scheduleRetry(Instant nextRetryAt, String failureCode, String failureMessage) {
        this.status = NotificationStatus.PENDING;
        this.nextRetryAt = nextRetryAt;
        this.failureCode = truncate(failureCode, 100);
        this.failureMessage = truncate(failureMessage, 500);
    }

    public void markFailed(String failureCode, String failureMessage) {
        this.status = NotificationStatus.FAILED;
        this.nextRetryAt = null;
        this.failureCode = truncate(failureCode, 100);
        this.failureMessage = truncate(failureMessage, 500);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getSourceEventId() {
        return sourceEventId;
    }

    public NotificationEventType getEventType() {
        return eventType;
    }

    public NotificationChannel getChannel() {
        return channel;
    }

    public String getTemplateCode() {
        return templateCode;
    }

    public String getLogicalKey() {
        return logicalKey;
    }

    public String getSubject() {
        return subject;
    }

    public String getBody() {
        return body;
    }

    public String getPayloadJson() {
        return payloadJson;
    }

    public NotificationStatus getStatus() {
        return status;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public Instant getNextRetryAt() {
        return nextRetryAt;
    }

    public String getProviderMessageId() {
        return providerMessageId;
    }

    public String getFailureCode() {
        return failureCode;
    }

    public String getFailureMessage() {
        return failureMessage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getSentAt() {
        return sentAt;
    }
}
