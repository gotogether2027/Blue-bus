package in.bluebustickets.bluebus.ticket.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import in.bluebustickets.bluebus.foundation.persistence.AuditableEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

/**
 * Immutable customer-facing travel document issued from a confirmed booking.
 * Snapshot fields must not silently follow later booking/trip/fleet edits.
 */
@Entity
@Table(name = "tickets")
public class Ticket extends AuditableEntity {

    @Column(name = "booking_id", nullable = false, updatable = false)
    private UUID bookingId;

    @Column(name = "ticket_number", nullable = false, length = 16, updatable = false)
    private String ticketNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private TicketStatus status;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private Instant issuedAt;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "booking_reference", nullable = false, length = 32, updatable = false)
    private String bookingReference;

    @Column(name = "trip_id", nullable = false, updatable = false)
    private UUID tripId;

    @Column(name = "operator_id", nullable = false, updatable = false)
    private UUID operatorId;

    @Column(name = "operator_name", nullable = false, length = 255, updatable = false)
    private String operatorName;

    @Column(name = "origin_stop_id", nullable = false, updatable = false)
    private UUID originStopId;

    @Column(name = "destination_stop_id", nullable = false, updatable = false)
    private UUID destinationStopId;

    @Column(name = "origin_stop_name", nullable = false, length = 200, updatable = false)
    private String originStopName;

    @Column(name = "destination_stop_name", nullable = false, length = 200, updatable = false)
    private String destinationStopName;

    @Column(name = "scheduled_departure_at", nullable = false, updatable = false)
    private Instant scheduledDepartureAt;

    @Column(name = "scheduled_arrival_at", nullable = false, updatable = false)
    private Instant scheduledArrivalAt;

    @Column(nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal totalAmount;

    @OneToMany(mappedBy = "ticket", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("createdAt ASC")
    private Set<TicketPassenger> passengers = new LinkedHashSet<>();

    protected Ticket() {
    }

    public Ticket(
            UUID bookingId,
            String ticketNumber,
            Instant issuedAt,
            UUID userId,
            String bookingReference,
            UUID tripId,
            UUID operatorId,
            String operatorName,
            UUID originStopId,
            UUID destinationStopId,
            String originStopName,
            String destinationStopName,
            Instant scheduledDepartureAt,
            Instant scheduledArrivalAt,
            String currency,
            BigDecimal totalAmount) {
        if (bookingId == null || issuedAt == null || userId == null || tripId == null || operatorId == null) {
            throw new IllegalArgumentException("booking, issuedAt, user, trip, and operator are required");
        }
        if (originStopId == null || destinationStopId == null) {
            throw new IllegalArgumentException("origin and destination stops are required");
        }
        if (scheduledDepartureAt == null || scheduledArrivalAt == null) {
            throw new IllegalArgumentException("scheduled departure and arrival are required");
        }
        if (totalAmount == null) {
            throw new IllegalArgumentException("totalAmount is required");
        }
        this.bookingId = bookingId;
        this.ticketNumber = requireText(ticketNumber, "ticketNumber").toUpperCase();
        this.issuedAt = issuedAt;
        this.userId = userId;
        this.bookingReference = requireText(bookingReference, "bookingReference");
        this.tripId = tripId;
        this.operatorId = operatorId;
        this.operatorName = requireText(operatorName, "operatorName");
        this.originStopId = originStopId;
        this.destinationStopId = destinationStopId;
        this.originStopName = requireText(originStopName, "originStopName");
        this.destinationStopName = requireText(destinationStopName, "destinationStopName");
        this.scheduledDepartureAt = scheduledDepartureAt;
        this.scheduledArrivalAt = scheduledArrivalAt;
        this.currency = requireText(currency, "currency").toUpperCase();
        this.totalAmount = totalAmount;
        this.status = TicketStatus.ACTIVE;
    }

    public void addPassenger(TicketPassenger passenger) {
        passengers.add(passenger);
    }

    /**
     * Idempotent ACTIVE → CANCELLED. Snapshot fields are never rewritten.
     */
    public void cancel() {
        if (status == TicketStatus.CANCELLED) {
            return;
        }
        if (status != TicketStatus.ACTIVE) {
            throw new IllegalArgumentException("Ticket cannot be cancelled from status " + status);
        }
        this.status = TicketStatus.CANCELLED;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    public UUID getBookingId() {
        return bookingId;
    }

    public String getTicketNumber() {
        return ticketNumber;
    }

    public TicketStatus getStatus() {
        return status;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getBookingReference() {
        return bookingReference;
    }

    public UUID getTripId() {
        return tripId;
    }

    public UUID getOperatorId() {
        return operatorId;
    }

    public String getOperatorName() {
        return operatorName;
    }

    public UUID getOriginStopId() {
        return originStopId;
    }

    public UUID getDestinationStopId() {
        return destinationStopId;
    }

    public String getOriginStopName() {
        return originStopName;
    }

    public String getDestinationStopName() {
        return destinationStopName;
    }

    public Instant getScheduledDepartureAt() {
        return scheduledDepartureAt;
    }

    public Instant getScheduledArrivalAt() {
        return scheduledArrivalAt;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public List<TicketPassenger> getPassengers() {
        return List.copyOf(passengers);
    }
}
