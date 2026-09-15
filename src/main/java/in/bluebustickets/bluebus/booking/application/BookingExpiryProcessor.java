package in.bluebustickets.bluebus.booking.application;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import in.bluebustickets.bluebus.booking.domain.Booking;
import in.bluebustickets.bluebus.booking.domain.BookingItem;
import in.bluebustickets.bluebus.booking.domain.BookingItemStatus;
import in.bluebustickets.bluebus.booking.domain.BookingStatus;
import in.bluebustickets.bluebus.booking.repository.BookingRepository;
import in.bluebustickets.bluebus.scheduling.domain.TripSeatAllocation;
import in.bluebustickets.bluebus.scheduling.domain.TripSeatAllocationState;
import in.bluebustickets.bluebus.scheduling.repository.TripSeatAllocationRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-booking unpaid expiry unit of work. Uses PostgreSQL {@code FOR UPDATE SKIP LOCKED}
 * so concurrent reapers and confirm/cancel races stay database-authoritative.
 */
@Service
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class BookingExpiryProcessor {

    private static final Logger log = LoggerFactory.getLogger(BookingExpiryProcessor.class);

    @PersistenceContext
    private EntityManager entityManager;

    private final BookingRepository bookingRepository;
    private final TripSeatAllocationRepository tripSeatAllocationRepository;

    public BookingExpiryProcessor(
            BookingRepository bookingRepository,
            TripSeatAllocationRepository tripSeatAllocationRepository) {
        this.bookingRepository = bookingRepository;
        this.tripSeatAllocationRepository = tripSeatAllocationRepository;
    }

    /**
     * Attempt to expire one due PENDING_PAYMENT booking in a new transaction.
     * Returns empty when the booking is not due, not pending, or locked by another worker.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<BookingExpiryResult> tryExpireDueBooking(UUID bookingId, Instant now) {
        Optional<Booking> locked = lockDueUnpaidBooking(bookingId, now);
        if (locked.isEmpty()) {
            return Optional.empty();
        }

        Booking booking = bookingRepository.findDetailedById(locked.get().getId())
                .orElseThrow(() -> new IllegalStateException("Locked booking disappeared: " + bookingId));

        if (booking.getStatus() != BookingStatus.PENDING_PAYMENT
                || booking.getPaymentExpiresAt() == null
                || booking.getPaymentExpiresAt().isAfter(now)) {
            return Optional.empty();
        }

        List<UUID> itemIds = booking.getItems().stream().map(BookingItem::getId).toList();
        List<TripSeatAllocation> allocations = itemIds.isEmpty()
                ? List.of()
                : tripSeatAllocationRepository.findByBookingItemIdInForUpdate(itemIds);

        if (allocations.size() != itemIds.size()) {
            throw new IllegalStateException(
                    "Refusing to expire booking " + bookingId
                            + " because allocation count " + allocations.size()
                            + " does not match item count " + itemIds.size());
        }
        for (TripSeatAllocation allocation : allocations) {
            if (allocation.getState() != TripSeatAllocationState.BOOKED) {
                throw new IllegalStateException(
                        "Refusing to expire booking " + bookingId
                                + " because allocation " + allocation.getId()
                                + " has unexpected state " + allocation.getState());
            }
        }

        booking.markExpired();
        for (BookingItem item : booking.getItems()) {
            if (item.getStatus() == BookingItemStatus.ACTIVE) {
                item.markExpired();
            }
        }

        int allocationsReleased = 0;
        for (TripSeatAllocation allocation : allocations) {
            allocation.release();
            allocationsReleased++;
        }

        entityManager.flush();
        tripSeatAllocationRepository.saveAllAndFlush(allocations);

        log.debug(
                "Expired unpaid booking {} and released {} allocation(s)",
                bookingId,
                allocationsReleased);
        return Optional.of(new BookingExpiryResult(1, allocationsReleased));
    }

    @SuppressWarnings("unchecked")
    private Optional<Booking> lockDueUnpaidBooking(UUID bookingId, Instant now) {
        List<Booking> rows = entityManager.createNativeQuery("""
                SELECT *
                FROM bookings
                WHERE id = :id
                  AND status = 'PENDING_PAYMENT'
                  AND payment_expires_at <= :now
                FOR UPDATE SKIP LOCKED
                """, Booking.class)
                .setParameter("id", bookingId)
                .setParameter("now", Timestamp.from(now))
                .getResultList();
        return rows.stream().findFirst();
    }
}
