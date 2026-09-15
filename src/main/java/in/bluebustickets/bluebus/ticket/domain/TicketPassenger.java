package in.bluebustickets.bluebus.ticket.domain;

import java.math.BigDecimal;
import java.util.UUID;

import in.bluebustickets.bluebus.foundation.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Snapshot of one booked passenger/seat at ticket issuance time.
 */
@Entity
@Table(name = "ticket_passengers")
public class TicketPassenger extends AuditableEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ticket_id", nullable = false, updatable = false)
    private Ticket ticket;

    @Column(name = "booking_passenger_id", updatable = false)
    private UUID bookingPassengerId;

    @Column(name = "passenger_name", nullable = false, length = 120, updatable = false)
    private String passengerName;

    @Column(updatable = false)
    private Integer age;

    @Column(length = 30, updatable = false)
    private String gender;

    @Column(name = "seat_label", nullable = false, length = 20, updatable = false)
    private String seatLabel;

    @Column(name = "origin_stop_name", nullable = false, length = 200, updatable = false)
    private String originStopName;

    @Column(name = "destination_stop_name", nullable = false, length = 200, updatable = false)
    private String destinationStopName;

    @Column(name = "fare_amount", nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal fareAmount;

    @Column(nullable = false, length = 3, updatable = false)
    private String currency;

    protected TicketPassenger() {
    }

    public TicketPassenger(
            Ticket ticket,
            UUID bookingPassengerId,
            String passengerName,
            Integer age,
            String gender,
            String seatLabel,
            String originStopName,
            String destinationStopName,
            BigDecimal fareAmount,
            String currency) {
        if (ticket == null) {
            throw new IllegalArgumentException("ticket is required");
        }
        if (fareAmount == null) {
            throw new IllegalArgumentException("fareAmount is required");
        }
        this.ticket = ticket;
        this.bookingPassengerId = bookingPassengerId;
        this.passengerName = requireText(passengerName, "passengerName");
        this.age = age;
        this.gender = gender == null || gender.isBlank() ? null : gender.trim();
        this.seatLabel = requireText(seatLabel, "seatLabel");
        this.originStopName = requireText(originStopName, "originStopName");
        this.destinationStopName = requireText(destinationStopName, "destinationStopName");
        this.fareAmount = fareAmount;
        this.currency = requireText(currency, "currency").toUpperCase();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    public Ticket getTicket() {
        return ticket;
    }

    public UUID getBookingPassengerId() {
        return bookingPassengerId;
    }

    public String getPassengerName() {
        return passengerName;
    }

    public Integer getAge() {
        return age;
    }

    public String getGender() {
        return gender;
    }

    public String getSeatLabel() {
        return seatLabel;
    }

    public String getOriginStopName() {
        return originStopName;
    }

    public String getDestinationStopName() {
        return destinationStopName;
    }

    public BigDecimal getFareAmount() {
        return fareAmount;
    }

    public String getCurrency() {
        return currency;
    }
}
