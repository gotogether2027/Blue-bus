package in.bluebustickets.bluebus.booking.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import in.bluebustickets.bluebus.booking.domain.Booking;
import in.bluebustickets.bluebus.booking.domain.BookingItem;
import in.bluebustickets.bluebus.booking.domain.BookingStatus;
import in.bluebustickets.bluebus.booking.repository.BookingRepository;
import in.bluebustickets.bluebus.foundation.api.error.ApplicationConflictException;
import in.bluebustickets.bluebus.foundation.api.error.ResourceNotFoundException;
import in.bluebustickets.bluebus.foundation.outbox.OutboxEvent;
import in.bluebustickets.bluebus.foundation.outbox.OutboxEventRepository;
import in.bluebustickets.bluebus.scheduling.domain.TripSeatAllocationState;
import in.bluebustickets.bluebus.scheduling.repository.TripSeatAllocationRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class BookingPaymentService implements BookingPaymentPort {

    private final BookingRepository bookingRepository;
    private final TripSeatAllocationRepository allocationRepository;
    private final OutboxEventRepository outboxEventRepository;

    public BookingPaymentService(
            BookingRepository bookingRepository,
            TripSeatAllocationRepository allocationRepository,
            OutboxEventRepository outboxEventRepository) {
        this.bookingRepository = bookingRepository;
        this.allocationRepository = allocationRepository;
        this.outboxEventRepository = outboxEventRepository;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public PaymentBookingSnapshot lockOwnedForPaymentInitiation(UUID bookingId, UUID userId, Instant now) {
        Booking booking = lock(bookingId);
        if (!booking.getUserId().equals(userId)) {
            throw new ResourceNotFoundException("Booking was not found.");
        }
        if (booking.getStatus() != BookingStatus.PENDING_PAYMENT) {
            throw new ApplicationConflictException("Booking is not eligible for payment.");
        }
        if (!booking.getPaymentExpiresAt().isAfter(now)) {
            throw new ApplicationConflictException("Booking payment deadline has passed.");
        }
        return snapshot(booking);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public PaymentBookingSnapshot lockForPaymentOutcome(UUID bookingId) {
        return snapshot(lock(bookingId));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public BookingStatus confirmLockedPendingPayment(UUID bookingId) {
        Booking booking = lock(bookingId);
        if (booking.getStatus() == BookingStatus.CONFIRMED) {
            return BookingStatus.CONFIRMED;
        }
        if (booking.getStatus() != BookingStatus.PENDING_PAYMENT) {
            throw new ApplicationConflictException(
                    "Booking cannot be confirmed from status " + booking.getStatus() + ".");
        }

        Booking detailed = bookingRepository.findDetailedById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking was not found."));
        List<UUID> itemIds = detailed.getItems().stream().map(BookingItem::getId).toList();
        if (itemIds.isEmpty()) {
            throw new IllegalStateException("Booking has no items.");
        }
        var allocations = allocationRepository.findByBookingItemIdInForUpdate(itemIds);
        if (allocations.size() != itemIds.size()
                || allocations.stream().anyMatch(a -> a.getState() != TripSeatAllocationState.BOOKED)) {
            throw new IllegalStateException("Booking allocations are not fully BOOKED.");
        }

        detailed.markConfirmed();
        Instant confirmedAt = Instant.now();
        outboxEventRepository.save(new OutboxEvent(
                "BOOKING_CONFIRMED",
                "BOOKING",
                bookingId,
                "{\"bookingId\":\"" + bookingId + "\"}",
                confirmedAt,
                null,
                null));
        return detailed.getStatus();
    }

    private Booking lock(UUID bookingId) {
        return bookingRepository.findByIdForUpdate(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking was not found."));
    }

    private static PaymentBookingSnapshot snapshot(Booking booking) {
        return new PaymentBookingSnapshot(
                booking.getId(),
                booking.getUserId(),
                booking.getStatus(),
                booking.getTotalAmount(),
                booking.getCurrency(),
                booking.getPaymentExpiresAt());
    }
}
