package in.bluebustickets.bluebus.scheduling.api.admin.dto;

import java.math.BigDecimal;
import java.util.UUID;

import in.bluebustickets.bluebus.scheduling.domain.Location;

public record LocationResponse(
        UUID id,
        String countryCode,
        String state,
        String district,
        String city,
        String locality,
        BigDecimal latitude,
        BigDecimal longitude,
        String timeZone,
        boolean active) {

    public static LocationResponse from(Location location) {
        return new LocationResponse(
                location.getId(),
                location.getCountryCode() == null ? null : location.getCountryCode().trim(),
                location.getState(),
                location.getDistrict(),
                location.getCity(),
                location.getLocality(),
                location.getLatitude(),
                location.getLongitude(),
                location.getTimeZone(),
                location.isActive());
    }
}
