package in.bluebustickets.bluebus.scheduling.application;

import java.time.Instant;

import in.bluebustickets.bluebus.scheduling.domain.TripStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TripSaleabilityTest {

    private static final Instant NOW = Instant.parse("2026-09-17T12:00:00Z");
    private static final Instant OPENS = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant CLOSES = Instant.parse("2026-12-01T09:00:00Z");
    private static final Instant DEPARTURE = Instant.parse("2026-12-01T10:00:00Z");

    @Test
    void scheduledInsideWindowBeforeDepartureIsSaleable() {
        assertThat(TripSaleability.isSaleableNow(
                TripStatus.SCHEDULED, OPENS, CLOSES, DEPARTURE, NOW)).isTrue();
        assertThat(TripSaleability.isSaleableNow(
                TripStatus.ON_SALE, OPENS, CLOSES, DEPARTURE, NOW)).isTrue();
    }

    @Test
    void draftCancelledAndClosedStatusesAreNotSaleable() {
        assertThat(TripSaleability.isSaleableNow(
                TripStatus.DRAFT, OPENS, CLOSES, DEPARTURE, NOW)).isFalse();
        assertThat(TripSaleability.isSaleableNow(
                TripStatus.CANCELLED, OPENS, CLOSES, DEPARTURE, NOW)).isFalse();
        assertThat(TripSaleability.isSaleableNow(
                TripStatus.CLOSED, OPENS, CLOSES, DEPARTURE, NOW)).isFalse();
        assertThat(TripSaleability.isSaleableNow(
                TripStatus.DEPARTED, OPENS, CLOSES, DEPARTURE, NOW)).isFalse();
        assertThat(TripSaleability.isSaleableNow(
                TripStatus.COMPLETED, OPENS, CLOSES, DEPARTURE, NOW)).isFalse();
    }

    @Test
    void windowAndDepartureBoundsUseASingleNow() {
        assertThat(TripSaleability.isSaleableNow(
                TripStatus.SCHEDULED, NOW, CLOSES, DEPARTURE, NOW)).isTrue();
        assertThat(TripSaleability.isSaleableNow(
                TripStatus.SCHEDULED, NOW.plusSeconds(1), CLOSES, DEPARTURE, NOW)).isFalse();
        assertThat(TripSaleability.isSaleableNow(
                TripStatus.SCHEDULED, OPENS, NOW, DEPARTURE, NOW)).isFalse();
        assertThat(TripSaleability.isSaleableNow(
                TripStatus.SCHEDULED, OPENS, NOW.plusSeconds(1), DEPARTURE, NOW)).isTrue();
        assertThat(TripSaleability.isSaleableNow(
                TripStatus.SCHEDULED, OPENS, CLOSES, NOW, NOW)).isFalse();
        assertThat(TripSaleability.isSaleableNow(
                TripStatus.SCHEDULED, OPENS, CLOSES, NOW.plusSeconds(1), NOW)).isTrue();
    }
}
