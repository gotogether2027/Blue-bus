package in.bluebustickets.bluebus.fleet.domain;

public enum SeatLayoutType {
    SEATER,
    SLEEPER,
    SEATER_SLEEPER,
    CUSTOM;

    public static SeatLayoutType parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Layout type is required");
        }
        try {
            return SeatLayoutType.valueOf(value.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid layout type.");
        }
    }
}
