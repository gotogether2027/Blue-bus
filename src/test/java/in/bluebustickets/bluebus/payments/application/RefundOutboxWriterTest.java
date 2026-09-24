package in.bluebustickets.bluebus.payments.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import in.bluebustickets.bluebus.payments.domain.Refund;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RefundOutboxWriterTest {

    @Test
    void failedWithoutProviderRefundIdStaysRetryable() {
        Refund refund = refund();
        refund.markFailed("failed", "PROVIDER_FAILED", Instant.parse("2026-09-24T00:00:00Z"));
        assertThat(RefundOutboxWriter.isTerminalFailure(refund)).isFalse();
    }

    @Test
    void failedAfterProviderRefundIdIsTerminal() {
        Refund refund = refund();
        refund.markProcessing("rfnd_terminal", "processing");
        refund.markFailed("failed", "PROVIDER_FAILED", Instant.parse("2026-09-24T00:00:00Z"));
        assertThat(RefundOutboxWriter.isTerminalFailure(refund)).isTrue();
    }

    private static Refund refund() {
        return new Refund(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "RAZORPAY",
                "booking-cancel-1",
                "fingerprint",
                new BigDecimal("10.00"),
                "INR",
                "BOOKING_CANCELLED",
                Instant.parse("2026-09-24T00:00:00Z"));
    }
}
