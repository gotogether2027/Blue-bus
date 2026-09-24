package in.bluebustickets.bluebus.notification.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "notification_preferences")
public class NotificationPreference {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "email_enabled", nullable = false)
    private boolean emailEnabled = true;

    @Column(name = "sms_enabled", nullable = false)
    private boolean smsEnabled = false;

    @Column(name = "whatsapp_enabled", nullable = false)
    private boolean whatsappEnabled = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected NotificationPreference() {
    }

    public NotificationPreference(UUID userId, Instant now) {
        this.userId = userId;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public boolean isChannelEnabled(NotificationChannel channel) {
        return switch (channel) {
            case EMAIL -> emailEnabled;
            case SMS -> smsEnabled;
            case WHATSAPP -> whatsappEnabled;
        };
    }

    public void update(boolean emailEnabled, boolean smsEnabled, boolean whatsappEnabled, Instant now) {
        this.emailEnabled = emailEnabled;
        this.smsEnabled = smsEnabled;
        this.whatsappEnabled = whatsappEnabled;
        this.updatedAt = now;
    }

    public UUID getUserId() {
        return userId;
    }

    public boolean isEmailEnabled() {
        return emailEnabled;
    }

    public boolean isSmsEnabled() {
        return smsEnabled;
    }

    public boolean isWhatsappEnabled() {
        return whatsappEnabled;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public static NotificationPreference defaults(UUID userId) {
        return new NotificationPreference(userId, Instant.EPOCH);
    }
}
