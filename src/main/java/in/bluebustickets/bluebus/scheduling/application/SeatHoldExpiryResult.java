package in.bluebustickets.bluebus.scheduling.application;

/**
 * Outcome of one reaper pass over due ACTIVE seat holds.
 */
public record SeatHoldExpiryResult(int holdsExpired, int allocationsExpired) {

    public static SeatHoldExpiryResult empty() {
        return new SeatHoldExpiryResult(0, 0);
    }

    public SeatHoldExpiryResult plus(SeatHoldExpiryResult other) {
        return new SeatHoldExpiryResult(
                holdsExpired + other.holdsExpired(),
                allocationsExpired + other.allocationsExpired());
    }
}
