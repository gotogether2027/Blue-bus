package in.bluebustickets.bluebus.scheduling.domain;

/** Operational status of a trip stop snapshot. Sequence identity does not change with this status. */
public enum TripStopStatus {
    ACTIVE, SKIPPED, CANCELLED
}
