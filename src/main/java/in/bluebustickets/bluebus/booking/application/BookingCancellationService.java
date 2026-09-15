package in.bluebustickets.bluebus.booking.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import in.bluebustickets.bluebus.booking.api.dto.BookingCancellationResponse;
import in.bluebustickets.bluebus.booking.api.dto.CancelBookingRequest;
import in.bluebustickets.bluebus.booking.domain.Booking;
import in.bluebustickets.bluebus.booking.domain.BookingCancellation;
import in.bluebustickets.bluebus.booking.domain.BookingItem;
import in.bluebustickets.bluebus.booking.domain.BookingItemStatus;
import in.bluebustickets.bluebus.booking.domain.BookingStatus;
import in.bluebustickets.bluebus.booking.repository.BookingCancellationRepository;
import in.bluebustickets.bluebus.booking.repository.BookingRepository;
import in.bluebustickets.bluebus.foundation.api.error.ApplicationConflictException;
import in.bluebustickets.bluebus.foundation.api.error.ResourceNotFoundException;
import in.bluebustickets.bluebus.foundation.outbox.OutboxEvent;
import in.bluebustickets.bluebus.foundation.outbox.OutboxEventRepository;
import in.bluebustickets.bluebus.scheduling.domain.TripSeatAllocation;
import in.bluebustickets.bluebus.scheduling.domain.TripSeatAllocationState;
import in.bluebustickets.bluebus.scheduling.repository.TripSeatAllocationRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class BookingCancellationService {

    private final BookingRepository bookingRepository;
    private final BookingCancellationRepository cancellationRepository;
    private final TripSeatAllocationRepository allocationRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final BookingViewMapper bookingViewMapper;
    private final Clock clock;

    public BookingCancellationService(
            BookingRepository bookingRepository,
            BookingCancellationRepository cancellationRepository,
            TripSeatAllocationRepository allocationRepository,
            OutboxEventRepository outboxEventRepository,
            BookingViewMapper bookingViewMapper,
            Clock clock) {
        this.bookingRepository = bookingRepository;
        this.cancellationRepository = cancellationRepository;
        this.allocationRepository = allocationRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.bookingViewMapper = bookingViewMapper;
        this.clock = clock;
    }

    /**
     * Cancels only unpaid PENDING_PAYMENT bookings. Confirmed cancellation/refund policy is not
     * defined, so confirmed and all other terminal states are explicit conflicts.
     */
    @Transactional
    public BookingCancellationResponse cancelOwned(
            UUID userId,
            UUID bookingId,
            CancelBookingRequest request) {
        Instant now = clock.instant();
        Booking locked = bookingRepository.findByIdForUpdate(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking was not found."));
        if (!locked.getUserId().equals(userId)) {
            throw new ResourceNotFoundException("Booking was not found.");
        }

        BookingCancellation existing = cancellationRepository.findByBookingId(bookingId).orElse(null);
        if (locked.getStatus() == BookingStatus.CANCELLED && existing != null) {
            return toResponse(existing, requireDetailed(bookingId));
        }
        if (locked.getStatus() != BookingStatus.PENDING_PAYMENT) {
            throw new ApplicationConflictException(
                    "Customer cancellation is supported only for PENDING_PAYMENT bookings.");
        }

        Booking booking = requireDetailed(bookingId);
        List<UUID> itemIds = booking.getItems().stream().map(BookingItem::getId).toList();
        if (itemIds.isEmpty()) {
            throw new IllegalStateException("Booking has no items.");
        }
        List<TripSeatAllocation> allocations = allocationRepository.findByBookingItemIdInForUpdate(itemIds);
        if (allocations.size() != itemIds.size()) {
            throw new IllegalStateException("Booking allocation count does not match item count.");
        }
        if (allocations.stream().anyMatch(a -> a.getState() != TripSeatAllocationState.BOOKED)) {
            throw new ApplicationConflictException("Booking allocations are no longer cancellable.");
        }
        if (booking.getItems().stream().anyMatch(item -> item.getStatus() != BookingItemStatus.ACTIVE)) {
            throw new ApplicationConflictException("Booking items are no longer cancellable.");
        }

        booking.markCancelled();
        booking.getItems().forEach(BookingItem::markCancelled);
        allocations.forEach(TripSeatAllocation::cancel);

        String reason = request == null ? null : request.reason();
        BookingCancellation cancellation = cancellationRepository.save(new BookingCancellation(
                bookingId, userId, reason, booking.getCurrency(), now));
        outboxEventRepository.save(new OutboxEvent(
                "BOOKING_CANCELLED",
                "BOOKING",
                bookingId,
                "{\"bookingId\":\"" + bookingId
                        + "\",\"cancellationId\":\"" + cancellation.getId() + "\"}",
                now,
                cancellation.getId().toString(),
                null));

        allocationRepository.saveAllAndFlush(allocations);
        bookingRepository.flush();
        cancellationRepository.flush();
        return toResponse(cancellation, booking);
    }

    private Booking requireDetailed(UUID bookingId) {
        return bookingRepository.findDetailedById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking was not found."));
    }

    private BookingCancellationResponse toResponse(
            BookingCancellation cancellation,
            Booking booking) {
        return new BookingCancellationResponse(
                cancellation.getId(),
                cancellation.getBookingId(),
                cancellation.getPreviousStatus(),
                cancellation.getStatus(),
                cancellation.getReason(),
                cancellation.getPolicyCode(),
                cancellation.getRefundableAmount(),
                cancellation.getCurrency(),
                cancellation.getCancelledAt(),
                bookingViewMapper.toResponse(booking));
    }
}
