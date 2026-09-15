package in.bluebustickets.bluebus.payments.api.dto;

public record WebhookReceiptResponse(
        boolean accepted,
        boolean duplicate,
        String processingStatus) {
}
