package in.bluebustickets.bluebus.identity.api.dto;

public record LoginResponse(
        String accessToken,
        String tokenType,
        long expiresIn,
        String refreshToken) {

    public static LoginResponse bearer(String accessToken, long expiresIn, String refreshToken) {
        return new LoginResponse(accessToken, "Bearer", expiresIn, refreshToken);
    }
}
