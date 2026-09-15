package in.bluebustickets.bluebus.payments.api.dto;

import java.math.BigDecimal;

/**
 * Optional client payload. Amount is ignored; refunds always use the captured payment amount.
 */
public record CreateRefundRequest(BigDecimal amount, String reason) {
}
