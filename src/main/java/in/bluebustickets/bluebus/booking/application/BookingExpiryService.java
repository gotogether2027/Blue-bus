package in.bluebustickets.bluebus.booking.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import in.bluebustickets.bluebus.booking.domain.BookingStatus;
import in.bluebustickets.bluebus.booking.repository.BookingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Expires due {@code PENDING_PAYMENT} bookings and releases their BOOKED allocations.
 * Idempotent and restart-safe; PostgreSQL row locks remain authoritative.
 * <p>
 * Availability still treats {@code BOOKED} as blocking until this explicit
 * {@code BOOKED → RELEASED} transition commits.
 */
@Service
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class BookingExpiryService {

    private static final Logger log = LoggerFactory.getLogger(BookingExpiryService.class);

    private final BookingRepository bookingRepository;
    private final BookingExpiryProcessor bookingExpiryProcessor;
    private final BookingExpiryProperties properties;
    private final Clock clock;

    public BookingExpiryService(
            BookingRepository bookingRepository,
            BookingExpiryProcessor bookingExpiryProcessor,
            BookingExpiryProperties properties,
            Clock clock) {
        this.bookingRepository = bookingRepository;
        this.bookingExpiryProcessor = bookingExpiryProcessor;
        this.properties = properties;
        this.clock = clock;
    }

    /** Expire due unpaid bookings using the injected UTC clock. */
    public BookingExpiryResult expireDueBookings() {
        return expireDueBookings(clock.instant());
    }

    /**
     * Find PENDING_PAYMENT bookings with {@code payment_expires_at <= now} and expire them
     * in bounded batches. Each booking is processed in its own transaction
     * ({@code REQUIRES_NEW} + {@code FOR UPDATE SKIP LOCKED}).
     */
    public BookingExpiryResult expireDueBookings(Instant now) {
        if (now == null) {
            throw new IllegalArgumentException("Expiry now instant is required");
        }

        BookingExpiryResult total = BookingExpiryResult.empty();
        int batchSize = properties.getBatchSize();
        int failures = 0;

        while (true) {
            List<UUID> dueIds = bookingRepository.findDueUnpaidBookingIds(
                    BookingStatus.PENDING_PAYMENT,
                    now,
                    PageRequest.of(0, batchSize));
            if (dueIds.isEmpty()) {
                break;
            }

            BookingExpiryResult batchResult = BookingExpiryResult.empty();
            int processedInBatch = 0;
            for (UUID bookingId : dueIds) {
                try {
                    var expired = bookingExpiryProcessor.tryExpireDueBooking(bookingId, now);
                    if (expired.isPresent()) {
                        batchResult = batchResult.plus(expired.get());
                        processedInBatch++;
                    }
                } catch (RuntimeException exception) {
                    failures++;
                    log.warn("Failed to expire unpaid booking {}: {}", bookingId, exception.getMessage());
                }
            }

            total = total.plus(batchResult);
            if (dueIds.size() < batchSize || processedInBatch == 0) {
                break;
            }
        }

        if (total.bookingsExpired() > 0 || failures > 0) {
            log.info(
                    "Unpaid booking expiry pass complete: bookingsExpired={}, allocationsReleased={}, failures={}",
                    total.bookingsExpired(),
                    total.allocationsReleased(),
                    failures);
        }
        return total;
    }

    /**
     * Read-only helper for tests/ops: count currently due PENDING_PAYMENT bookings in one page.
     */
    @Transactional(readOnly = true)
    public long countDueUnpaidBookings(Instant now) {
        return bookingRepository.findDueUnpaidBookingIds(
                        BookingStatus.PENDING_PAYMENT,
                        now,
                        PageRequest.of(0, properties.getBatchSize()))
                .size();
    }
}
