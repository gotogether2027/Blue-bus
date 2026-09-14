package in.bluebustickets.bluebus.scheduling.domain;

import java.math.BigDecimal;

import in.bluebustickets.bluebus.foundation.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "locations")
public class Location extends AuditableEntity {

    @Pattern(regexp = "^[A-Z]{2}$")
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "country_code", nullable = false, length = 2)
    private String countryCode = "IN";

    @NotBlank
    @Column(nullable = false, length = 120)
    private String state;

    @Column(length = 120)
    private String district;

    @NotBlank
    @Column(nullable = false, length = 120)
    private String city;

    @Column(length = 160)
    private String locality;

    @DecimalMin("-90.0")
    @DecimalMax("90.0")
    @Column(precision = 9, scale = 6)
    private BigDecimal latitude;

    @DecimalMin("-180.0")
    @DecimalMax("180.0")
    @Column(precision = 9, scale = 6)
    private BigDecimal longitude;

    @NotBlank
    @Column(name = "time_zone", nullable = false, length = 64)
    private String timeZone = "Asia/Kolkata";

    @Column(nullable = false)
    private boolean active = true;

    protected Location() { }

    public Location(String state, String city) {
        this.state = state;
        this.city = city;
    }

    public void updateDetails(
            String countryCode,
            String state,
            String district,
            String city,
            String locality,
            BigDecimal latitude,
            BigDecimal longitude,
            String timeZone) {
        if (countryCode != null && !countryCode.isBlank()) {
            this.countryCode = countryCode;
        }
        if (state == null || state.isBlank()) {
            throw new IllegalArgumentException("Location state is required");
        }
        if (city == null || city.isBlank()) {
            throw new IllegalArgumentException("Location city is required");
        }
        this.state = state;
        this.district = district;
        this.city = city;
        this.locality = locality;
        this.latitude = latitude;
        this.longitude = longitude;
        if (timeZone != null && !timeZone.isBlank()) {
            this.timeZone = timeZone;
        }
    }

    public void activate() {
        this.active = true;
    }

    public void deactivate() {
        this.active = false;
    }

    public String getCountryCode() { return countryCode; }
    public String getState() { return state; }
    public String getDistrict() { return district; }
    public String getCity() { return city; }
    public String getLocality() { return locality; }
    public BigDecimal getLatitude() { return latitude; }
    public BigDecimal getLongitude() { return longitude; }
    public String getTimeZone() { return timeZone; }
    public boolean isActive() { return active; }
}
