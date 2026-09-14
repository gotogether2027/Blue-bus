package in.bluebustickets.bluebus.scheduling.domain;

import java.time.Instant;
import java.util.UUID;

import in.bluebustickets.bluebus.foundation.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Temporary checkout hold for one origin→destination segment across one or more seats.
 * Occupancy is stored on {@link TripSeatAllocation}; this aggregate owns those HELD rows.
 */
@Entity
@Table(name = "seat_holds")
public class SeatHold extends AuditableEntity {

    @NotNull
    @Column(name = "trip_id", nullable = false, updatable = false)
    private UUID tripId;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trip_id", insertable = false, updatable = false, nullable = false)
    private Trip trip;

    @Column(name = "user_id", updatable = false)
    private UUID userId;

    @Min(1)
    @Column(name = "origin_sequence", nullable = false, updatable = false)
    private int originSequence;

    @Min(1)
    @Column(name = "destination_sequence", nullable = false, updatable = false)
    private int destinationSequence;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private SeatHoldStatus status = SeatHoldStatus.ACTIVE;

    @NotNull
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "idempotency_key", length = 100, updatable = false)
    private String idempotencyKey;

    @Column(name = "request_fingerprint", length = 128, updatable = false)
    private String requestFingerprint;

    @Min(1)
    @Column(nullable = false)
    private int version = 1;

    protected SeatHold() { }

    public SeatHold(
            Trip trip,
            int originSequence,
            int destinationSequence,
            Instant expiresAt,
            UUID userId,
            String idempotencyKey,
            String requestFingerprint) {
        if (trip == null || trip.getId() == null) {
            throw new IllegalArgumentException("Seat hold requires a persisted trip");
        }
        if (originSequence < 1 || destinationSequence <= originSequence) {
            throw new IllegalArgumentException("Seat hold destination must be greater than origin");
        }
        if (expiresAt == null) {
            throw new IllegalArgumentException("Seat hold requires expires_at");
        }
        this.trip = trip;
        this.tripId = trip.getId();
        this.originSequence = originSequence;
        this.destinationSequence = destinationSequence;
        this.expiresAt = expiresAt;
        this.userId = userId;
        this.idempotencyKey = blankToNull(idempotencyKey);
        this.requestFingerprint = blankToNull(requestFingerprint);
        this.status = SeatHoldStatus.ACTIVE;
    }

    public void consume() {
        requireActive("CONSUMED");
        this.status = SeatHoldStatus.CONSUMED;
        this.version++;
    }

    public void expire() {
        requireActive("EXPIRED");
        this.status = SeatHoldStatus.EXPIRED;
        this.version++;
    }

    public void cancel() {
        requireActive("CANCELLED");
        this.status = SeatHoldStatus.CANCELLED;
        this.version++;
    }

    private void requireActive(String target) {
        if (status != SeatHoldStatus.ACTIVE) {
            throw new IllegalArgumentException(
                    "Only ACTIVE seat holds can transition to " + target + "; current status is " + status);
        }
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    public UUID getTripId() { return tripId; }
    public Trip getTrip() { return trip; }
    public UUID getUserId() { return userId; }
    public int getOriginSequence() { return originSequence; }
    public int getDestinationSequence() { return destinationSequence; }
    public SeatHoldStatus getStatus() { return status; }
    public Instant getExpiresAt() { return expiresAt; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getRequestFingerprint() { return requestFingerprint; }
    public int getVersion() { return version; }
}
