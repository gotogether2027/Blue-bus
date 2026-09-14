package in.bluebustickets.bluebus.scheduling.domain;

/**
 * Occupancy state for a segment allocation on physical trip seat inventory.
 * Physical inventory itself remains AVAILABLE/BLOCKED only.
 */
public enum TripSeatAllocationState {
    HELD,
    BOOKED,
    EXPIRED,
    CANCELLED,
    RELEASED,
    BLOCKED
}
