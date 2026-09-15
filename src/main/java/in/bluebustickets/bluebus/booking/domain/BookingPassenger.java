package in.bluebustickets.bluebus.booking.domain;

import in.bluebustickets.bluebus.foundation.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Entity
@Table(name = "booking_passengers")
public class BookingPassenger extends AuditableEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "booking_id", nullable = false, updatable = false)
    private Booking booking;

    @NotBlank
    @Column(name = "full_name", nullable = false, length = 120, updatable = false)
    private String fullName;

    @Min(0)
    @Max(120)
    @Column(updatable = false)
    private Integer age;

    @Column(length = 30, updatable = false)
    private String gender;

    protected BookingPassenger() {
    }

    public BookingPassenger(Booking booking, String fullName, Integer age, String gender) {
        if (booking == null) {
            throw new IllegalArgumentException("booking is required");
        }
        if (fullName == null || fullName.isBlank()) {
            throw new IllegalArgumentException("fullName is required");
        }
        this.booking = booking;
        this.fullName = fullName.trim();
        this.age = age;
        this.gender = gender == null || gender.isBlank() ? null : gender.trim();
    }

    public Booking getBooking() {
        return booking;
    }

    public String getFullName() {
        return fullName;
    }

    public Integer getAge() {
        return age;
    }

    public String getGender() {
        return gender;
    }
}
