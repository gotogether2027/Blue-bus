package in.bluebustickets.bluebus.identity.api.dto;

import java.util.List;
import java.util.UUID;

import in.bluebustickets.bluebus.identity.domain.UserStatus;

public record CustomerIdentityResponse(
        UUID userId,
        String firstName,
        String lastName,
        String email,
        List<String> roles,
        UserStatus status) {
}
