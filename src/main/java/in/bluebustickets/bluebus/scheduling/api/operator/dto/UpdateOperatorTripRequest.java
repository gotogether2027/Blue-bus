package in.bluebustickets.bluebus.scheduling.api.operator.dto;

import java.math.BigDecimal;
import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.DecimalMin;

/**
 * Allow-list PATCH for operator trip commercial terms.
 * Unknown and immutable fields (bus/route/schedule/status/layout) are rejected.
 */
public class UpdateOperatorTripRequest {

    @DecimalMin("0.0")
    private BigDecimal baseFare;
    private Instant bookingOpensAt;
    private Instant bookingClosesAt;

    private boolean baseFarePresent;
    private boolean bookingOpensAtPresent;
    private boolean bookingClosesAtPresent;

    public BigDecimal getBaseFare() {
        return baseFare;
    }

    public void setBaseFare(BigDecimal baseFare) {
        this.baseFare = baseFare;
        this.baseFarePresent = true;
    }

    public Instant getBookingOpensAt() {
        return bookingOpensAt;
    }

    public void setBookingOpensAt(Instant bookingOpensAt) {
        this.bookingOpensAt = bookingOpensAt;
        this.bookingOpensAtPresent = true;
    }

    public Instant getBookingClosesAt() {
        return bookingClosesAt;
    }

    public void setBookingClosesAt(Instant bookingClosesAt) {
        this.bookingClosesAt = bookingClosesAt;
        this.bookingClosesAtPresent = true;
    }

    public boolean hasBaseFare() {
        return baseFarePresent;
    }

    public boolean hasBookingOpensAt() {
        return bookingOpensAtPresent;
    }

    public boolean hasBookingClosesAt() {
        return bookingClosesAtPresent;
    }

    public boolean hasSupportedField() {
        return baseFarePresent || bookingOpensAtPresent || bookingClosesAtPresent;
    }

    @JsonAnySetter
    public void rejectUnknown(String name, Object value) {
        throw new IllegalArgumentException("Unknown field '" + name + "'.");
    }
}
