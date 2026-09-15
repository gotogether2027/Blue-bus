package in.bluebustickets.bluebus.booking.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import in.bluebustickets.bluebus.booking.domain.Booking;
import in.bluebustickets.bluebus.booking.domain.BookingStatus;
import in.bluebustickets.bluebus.booking.repository.BookingRepository;
import in.bluebustickets.bluebus.foundation.api.error.ApplicationConflictException;
import in.bluebustickets.bluebus.foundation.api.error.ResourceNotFoundException;
import in.bluebustickets.bluebus.foundation.outbox.OutboxEvent;
import in.bluebustickets.bluebus.foundation.outbox.OutboxEventRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lock-safe unpaid booking confirm/cancel hooks for payment races and concurrency tests.
 * Always locks the booking row {@code FOR UPDATE} (wait) before inspecting status, so a concurrent
 * expiry worker either wins completely or skips; never a partial release.
 * <p>
 * Customer HTTP cancellation uses {@link BookingCancellationService}. This cancel hook delegates
 * to that same unpaid-cancellation transaction.
 */
@Service
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class BookingLifecycleService {

    @PersistenceContext
    private EntityManager entityManager;

    private final BookingRepository bookingRepository;
    private final BookingCancellationService bookingCancellationService;
    private final OutboxEventRepository outboxEventRepository;

    public BookingLifecycleService(
            BookingRepository bookingRepository,
            BookingCancellationService bookingCancellationService,
            OutboxEventRepository outboxEventRepository) {
        this.bookingRepository = bookingRepository;
        this.bookingCancellationService = bookingCancellationService;
        this.outboxEventRepository = outboxEventRepository;
    }

    /**
     * Future payment-success hook: {@code PENDING_PAYMENT → CONFIRMED}. Allocations stay {@code BOOKED}.
     * Idempotent if already confirmed. Conflicts if the booking expired or cancelled first.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BookingStatus confirmPendingPayment(UUID bookingId) {
        Booking booking = lockBookingForUpdate(bookingId);
        if (booking.getStatus() == BookingStatus.CONFIRMED) {
            return BookingStatus.CONFIRMED;
        }
        if (booking.getStatus() != BookingStatus.PENDING_PAYMENT) {
            throw new ApplicationConflictException(
                    "Booking cannot be confirmed from status " + booking.getStatus() + ".");
        }
        booking.markConfirmed();
        Instant confirmedAt = Instant.now();
        outboxEventRepository.save(new OutboxEvent(
                "BOOKING_CONFIRMED",
                "BOOKING",
                bookingId,
                "{\"bookingId\":\"" + bookingId + "\"}",
                confirmedAt,
                null,
                null));
        entityManager.flush();
        return booking.getStatus();
    }

    /**
     * Unpaid cancel hook: {@code PENDING_PAYMENT → CANCELLED}, items {@code CANCELLED},
     * allocations {@code BOOKED → CANCELLED}. Idempotent if already cancelled.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BookingStatus cancelUnpaidBooking(UUID bookingId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking was not found."));
        return bookingCancellationService.cancelOwned(booking.getUserId(), bookingId, null)
                .booking()
                .status();
    }

    @SuppressWarnings("unchecked")
    private Booking lockBookingForUpdate(UUID bookingId) {
        List<Booking> rows = entityManager.createNativeQuery("""
                SELECT *
                FROM bookings
                WHERE id = :id
                FOR UPDATE
                """, Booking.class)
                .setParameter("id", bookingId)
                .getResultList();
        if (rows.isEmpty()) {
            throw new ResourceNotFoundException("Booking was not found.");
        }
        return rows.get(0);
    }
}
