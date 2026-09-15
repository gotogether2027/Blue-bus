package in.bluebustickets.bluebus.identity.domain;

import java.time.Instant;
import java.util.UUID;

import in.bluebustickets.bluebus.foundation.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;

@Entity
@Table(name = "refresh_tokens")
public class RefreshToken extends AuditableEntity {

    @NotNull
    @Column(name = "family_id", nullable = false, updatable = false)
    private UUID familyId;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @NotNull
    @Column(name = "token_hash", nullable = false, updatable = false, columnDefinition = "bytea")
    private byte[] tokenHash;

    @NotNull
    @Column(name = "issued_at", nullable = false, updatable = false)
    private Instant issuedAt;

    @NotNull
    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "replaced_by_id")
    private UUID replacedById;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    protected RefreshToken() {
    }

    public RefreshToken(UUID familyId, User user, byte[] tokenHash, Instant issuedAt, Instant expiresAt) {
        if (familyId == null) {
            throw new IllegalArgumentException("familyId is required");
        }
        if (user == null || user.getId() == null) {
            throw new IllegalArgumentException("Persisted user is required");
        }
        if (tokenHash == null || tokenHash.length != 32) {
            throw new IllegalArgumentException("tokenHash must be exactly 32 bytes");
        }
        if (issuedAt == null || expiresAt == null || !expiresAt.isAfter(issuedAt)) {
            throw new IllegalArgumentException("expiresAt must be after issuedAt");
        }
        this.familyId = familyId;
        this.user = user;
        this.tokenHash = tokenHash.clone();
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
    }

    public void revoke(Instant at) {
        if (this.revokedAt == null) {
            this.revokedAt = at == null ? Instant.now() : at;
        }
    }

    /**
     * Records rotation metadata. Caller must revoke the row (and flush) before inserting the
     * replacement so the partial unique active-family index stays satisfied.
     */
    public void markReplacedBy(UUID replacementId, Instant usedAt) {
        if (replacementId == null) {
            throw new IllegalArgumentException("replacementId is required");
        }
        this.replacedById = replacementId;
        this.lastUsedAt = usedAt;
        revoke(usedAt);
    }

    public boolean isActive(Instant now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }

    public UUID getFamilyId() {
        return familyId;
    }

    public User getUser() {
        return user;
    }

    public byte[] getTokenHash() {
        return tokenHash == null ? null : tokenHash.clone();
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public UUID getReplacedById() {
        return replacedById;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }
}
