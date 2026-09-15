package in.bluebustickets.bluebus.payments.api;

import in.bluebustickets.bluebus.payments.api.dto.WebhookReceiptResponse;
import in.bluebustickets.bluebus.payments.application.PaymentWebhookService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/payments/webhooks")
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class PaymentWebhookController {

    private final PaymentWebhookService paymentWebhookService;

    public PaymentWebhookController(PaymentWebhookService paymentWebhookService) {
        this.paymentWebhookService = paymentWebhookService;
    }

    @PostMapping("/{provider}")
    public WebhookReceiptResponse receive(
            @PathVariable String provider,
            @RequestBody byte[] rawBody,
            @RequestHeader HttpHeaders headers) {
        return paymentWebhookService.receive(provider, rawBody, headers);
    }
}
