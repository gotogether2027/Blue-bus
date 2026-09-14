package in.bluebustickets.bluebus.scheduling.domain;

/**
 * Physical status of a trip seat snapshot. Sale occupancy is not stored here.
 * Future segment allocations will derive HELD/BOOKED for a requested origin/destination range.
 */
public enum TripSeatInventoryStatus {
    AVAILABLE, BLOCKED
}
