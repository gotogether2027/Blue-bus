package in.bluebustickets.bluebus.booking.application;

import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Thin Spring scheduler that delegates to {@link BookingExpiryService}.
 * Disabled in tests via {@code blue-bus.bookings.expiry.enabled=false}.
 */
@Component
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
@ConditionalOnProperty(prefix = "blue-bus.bookings.expiry", name = "enabled", matchIfMissing = true)
public class BookingExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(BookingExpiryScheduler.class);

    private final BookingExpiryService bookingExpiryService;
    private final Clock clock;

    public BookingExpiryScheduler(BookingExpiryService bookingExpiryService, Clock clock) {
        this.bookingExpiryService = bookingExpiryService;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${blue-bus.bookings.expiry.reaper-interval-ms:30000}")
    public void reapExpiredUnpaidBookings() {
        BookingExpiryResult result = bookingExpiryService.expireDueBookings(clock.instant());
        if (result.bookingsExpired() > 0) {
            log.debug(
                    "Scheduled unpaid booking reaper expired {} booking(s), {} allocation(s)",
                    result.bookingsExpired(),
                    result.allocationsReleased());
        }
    }
}
