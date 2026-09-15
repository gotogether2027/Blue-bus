package in.bluebustickets.bluebus.payments.domain;

public enum ProviderEventProcessingStatus {
    RECEIVED,
    PROCESSING,
    PROCESSED,
    IGNORED,
    FAILED_RETRYABLE,
    REQUIRES_REVIEW
}
