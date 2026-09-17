package in.bluebustickets.bluebus.payments.domain;

public enum PaymentDisposition {
    UNAPPLIED,
    APPLIED_TO_BOOKING,
    /**
     * Captured successfully but not applied to a travel-valid booking.
     * Compensation or reconciliation may follow; a later successful refund
     * does not change this disposition.
     */
    REQUIRES_RESOLUTION
}
