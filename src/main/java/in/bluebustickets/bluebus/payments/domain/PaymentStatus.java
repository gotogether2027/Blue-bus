package in.bluebustickets.bluebus.payments.domain;

public enum PaymentStatus {
    INITIATING,
    PENDING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    EXPIRED
}
