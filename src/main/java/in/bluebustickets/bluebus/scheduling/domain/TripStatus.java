package in.bluebustickets.bluebus.scheduling.domain;

/** Lifecycle states for a scheduled journey; completed and cancelled trips remain historical records. */
public enum TripStatus {
    DRAFT, SCHEDULED, ON_SALE, CLOSED, DEPARTED, COMPLETED, CANCELLED
}
