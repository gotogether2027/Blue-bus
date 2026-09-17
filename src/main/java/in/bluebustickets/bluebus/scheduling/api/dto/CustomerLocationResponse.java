package in.bluebustickets.bluebus.scheduling.api.dto;

import java.util.UUID;

import in.bluebustickets.bluebus.scheduling.domain.Location;

/**
 * Public customer discovery fields only. Admin lat/long, district, timezone, and active flag are omitted.
 */
public record CustomerLocationResponse(
        UUID id,
        String city,
        String state,
        String countryCode,
        String locality) {

    public static CustomerLocationResponse from(Location location) {
        return new CustomerLocationResponse(
                location.getId(),
                location.getCity(),
                location.getState(),
                location.getCountryCode() == null ? null : location.getCountryCode().trim(),
                location.getLocality());
    }
}
