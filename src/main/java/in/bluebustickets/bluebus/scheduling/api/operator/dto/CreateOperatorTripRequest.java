package in.bluebustickets.bluebus.scheduling.api.operator.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Create an operator-owned trip. Path {@code operatorId} is authoritative.
 * Seat layout and service date are derived; unknown fields are rejected.
 */
public class CreateOperatorTripRequest {

    @NotNull
    private UUID busId;

    @NotNull
    private UUID routeId;

    @NotNull
    private Instant scheduledDepartureAt;

    @NotNull
    private Instant scheduledArrivalAt;

    @NotNull
    @DecimalMin("0.0")
    private BigDecimal baseFare;

    @NotNull
    private Instant bookingOpensAt;

    @NotNull
    private Instant bookingClosesAt;

    @Size(max = 64)
    private String timeZone;

    public UUID getBusId() {
        return busId;
    }

    public void setBusId(UUID busId) {
        this.busId = busId;
    }

    public UUID getRouteId() {
        return routeId;
    }

    public void setRouteId(UUID routeId) {
        this.routeId = routeId;
    }

    public Instant getScheduledDepartureAt() {
        return scheduledDepartureAt;
    }

    public void setScheduledDepartureAt(Instant scheduledDepartureAt) {
        this.scheduledDepartureAt = scheduledDepartureAt;
    }

    public Instant getScheduledArrivalAt() {
        return scheduledArrivalAt;
    }

    public void setScheduledArrivalAt(Instant scheduledArrivalAt) {
        this.scheduledArrivalAt = scheduledArrivalAt;
    }

    public BigDecimal getBaseFare() {
        return baseFare;
    }

    public void setBaseFare(BigDecimal baseFare) {
        this.baseFare = baseFare;
    }

    public Instant getBookingOpensAt() {
        return bookingOpensAt;
    }

    public void setBookingOpensAt(Instant bookingOpensAt) {
        this.bookingOpensAt = bookingOpensAt;
    }

    public Instant getBookingClosesAt() {
        return bookingClosesAt;
    }

    public void setBookingClosesAt(Instant bookingClosesAt) {
        this.bookingClosesAt = bookingClosesAt;
    }

    public String getTimeZone() {
        return timeZone;
    }

    public void setTimeZone(String timeZone) {
        this.timeZone = timeZone;
    }

    @JsonAnySetter
    public void rejectUnknown(String name, Object value) {
        throw new IllegalArgumentException("Unknown field '" + name + "'.");
    }
}
