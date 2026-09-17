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
import in.bluebustickets.bluebus.payments.application.RefundApplicationService;
import in.bluebustickets.bluebus.payments.application.RefundRetryProperties;
import in.bluebustickets.bluebus.payments.domain.PaymentAttempt;
import in.bluebustickets.bluebus.payments.domain.PaymentStatus;
import in.bluebustickets.bluebus.payments.domain.Refund;
import in.bluebustickets.bluebus.payments.repository.PaymentAttemptRepository;
import in.bluebustickets.bluebus.payments.repository.RefundRepository;
import in.bluebustickets.bluebus.scheduling.domain.Trip;
import in.bluebustickets.bluebus.scheduling.domain.TripSeatAllocation;
import in.bluebustickets.bluebus.scheduling.domain.TripSeatAllocationState;
import in.bluebustickets.bluebus.scheduling.domain.TripStatus;
import in.bluebustickets.bluebus.scheduling.repository.TripRepository;
import in.bluebustickets.bluebus.scheduling.repository.TripSeatAllocationRepository;
import in.bluebustickets.bluebus.ticket.domain.Ticket;
import in.bluebustickets.bluebus.ticket.repository.TicketRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class BookingCancellationService {

    private static final Logger log = LoggerFactory.getLogger(BookingCancellationService.class);

    private final BookingRepository bookingRepository;
    private final BookingCancellationRepository cancellationRepository;
    private final TripSeatAllocationRepository allocationRepository;
    private final TripRepository tripRepository;
    private final TicketRepository ticketRepository;
    private final PaymentAttemptRepository paymentAttemptRepository;
    private final RefundRepository refundRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final RefundApplicationService refundApplicationService;
    private final RefundRetryProperties refundRetryProperties;
    private final BookingViewMapper bookingViewMapper;
    private final Clock clock;

    public BookingCancellationService(
            BookingRepository bookingRepository,
            BookingCancellationRepository cancellationRepository,
            TripSeatAllocationRepository allocationRepository,
            TripRepository tripRepository,
            TicketRepository ticketRepository,
            PaymentAttemptRepository paymentAttemptRepository,
            RefundRepository refundRepository,
            OutboxEventRepository outboxEventRepository,
            @Lazy RefundApplicationService refundApplicationService,
            RefundRetryProperties refundRetryProperties,
            BookingViewMapper bookingViewMapper,
            Clock clock) {
        this.bookingRepository = bookingRepository;
        this.cancellationRepository = cancellationRepository;
        this.allocationRepository = allocationRepository;
        this.tripRepository = tripRepository;
        this.ticketRepository = ticketRepository;
        this.paymentAttemptRepository = paymentAttemptRepository;
        this.refundRepository = refundRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.refundApplicationService = refundApplicationService;
        this.refundRetryProperties = refundRetryProperties;
        this.bookingViewMapper = bookingViewMapper;
        this.clock = clock;
    }

    /**
     * Owner cancellation entry point.
     * Unpaid {@code PENDING_PAYMENT} → {@code CANCELLED}.
     * Confirmed → {@code REFUND_PENDING} with inventory/ticket release, then provider refund after commit.
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
        if (isIdempotentRepeat(locked.getStatus(), existing)) {
            BookingCancellationResponse response = toResponse(existing, requireDetailed(bookingId));
            if (locked.getStatus() == BookingStatus.REFUND_PENDING && existing != null) {
                scheduleRefundAfterCommit(userId, bookingId, existing.getId(), request);
            }
            return response;
        }

        return switch (locked.getStatus()) {
            case PENDING_PAYMENT -> cancelUnpaid(userId, bookingId, request, now);
            case CONFIRMED -> cancelConfirmed(userId, bookingId, request, now);
            default -> throw new ApplicationConflictException(
                    "Customer cancellation is not supported for booking status " + locked.getStatus() + ".");
        };
    }

    private BookingCancellationResponse cancelUnpaid(
            UUID userId,
            UUID bookingId,
            CancelBookingRequest request,
            Instant now) {
        Booking booking = requireDetailed(bookingId);
        List<TripSeatAllocation> allocations = lockBookedAllocations(booking);
        assertActiveItems(booking);

        booking.markCancelled();
        booking.getItems().forEach(BookingItem::markCancelled);
        allocations.forEach(TripSeatAllocation::cancel);

        String reason = request == null ? null : request.reason();
        BookingCancellation cancellation = cancellationRepository.save(new BookingCancellation(
                bookingId, userId, reason, booking.getCurrency(), now));
        writeBookingCancelled(bookingId, cancellation, now);

        allocationRepository.saveAllAndFlush(allocations);
        bookingRepository.flush();
        cancellationRepository.flush();
        return toResponse(cancellation, booking);
    }

    private BookingCancellationResponse cancelConfirmed(
            UUID userId,
            UUID bookingId,
            CancelBookingRequest request,
            Instant now) {
        Booking booking = requireDetailed(bookingId);
        Trip trip = tripRepository.findById(booking.getTripId())
                .orElseThrow(() -> new IllegalStateException("Booking trip was not found."));
        assertCancellableBeforeDeparture(trip, now);

        List<TripSeatAllocation> allocations = lockBookedAllocations(booking);
        assertActiveItems(booking);

        Ticket ticket = ticketRepository.findByBookingIdForUpdate(bookingId).orElse(null);
        PaymentAttempt payment = lockSucceededPayment(bookingId);

        booking.markRefundPending();
        booking.getItems().forEach(BookingItem::markCancelled);
        allocations.forEach(TripSeatAllocation::cancel);
        if (ticket != null) {
            ticket.cancel();
        }

        String reason = request == null ? null : request.reason();
        BookingCancellation cancellation = cancellationRepository.save(BookingCancellation.confirmedFullRefund(
                bookingId,
                userId,
                reason,
                payment.getCapturedAmount(),
                payment.getCurrency(),
                now));
        persistRequestedRefund(cancellation.getId(), payment, reason, now);
        writeBookingCancelled(bookingId, cancellation, now);

        allocationRepository.saveAllAndFlush(allocations);
        bookingRepository.flush();
        cancellationRepository.flush();

        scheduleRefundAfterCommit(userId, bookingId, cancellation.getId(), request);
        return toResponse(cancellation, booking);
    }

    private void persistRequestedRefund(
            UUID cancellationId,
            PaymentAttempt payment,
            String reason,
            Instant now) {
        String idempotencyKey = RefundApplicationService.cancellationIdempotencyKey(cancellationId);
        if (refundRepository.findByPaymentAttemptIdAndIdempotencyKey(payment.getId(), idempotencyKey).isPresent()) {
            return;
        }
        String refundReason = reason == null || reason.isBlank() ? "BOOKING_CANCELLED" : reason.trim();
        if (refundReason.length() > 100) {
            refundReason = refundReason.substring(0, 100);
        }
        Refund refund = new Refund(
                payment.getId(),
                payment.getBookingId(),
                payment.getProvider(),
                idempotencyKey,
                RefundApplicationService.requestFingerprint(
                        payment.getId(), payment.getCapturedAmount(), payment.getCurrency()),
                payment.getCapturedAmount(),
                payment.getCurrency(),
                refundReason,
                now);
        refundRepository.saveAndFlush(refund);
    }

    private void scheduleRefundAfterCommit(
            UUID userId,
            UUID bookingId,
            UUID cancellationId,
            CancelBookingRequest request) {
        if (refundRetryProperties != null && !refundRetryProperties.isAfterCommitEnabled()) {
            return;
        }
        String reason = request == null || request.reason() == null || request.reason().isBlank()
                ? "BOOKING_CANCELLED"
                : request.reason().trim();
        String idempotencyKey = RefundApplicationService.cancellationIdempotencyKey(cancellationId);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            initiateRefundSafely(userId, bookingId, idempotencyKey, reason);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                initiateRefundSafely(userId, bookingId, idempotencyKey, reason);
            }
        });
    }

    private void initiateRefundSafely(UUID userId, UUID bookingId, String idempotencyKey, String reason) {
        try {
            PaymentAttempt payment = paymentAttemptRepository.findByBookingIdOrderByCreatedAtDesc(bookingId).stream()
                    .filter(attempt -> attempt.getStatus() == PaymentStatus.SUCCEEDED
                            && attempt.getCapturedAmount() != null
                            && attempt.getProviderPaymentId() != null
                            && !attempt.getProviderPaymentId().isBlank())
                    .findFirst()
                    .orElse(null);
            if (payment == null) {
                log.warn("Confirmed cancellation {} has no succeeded payment for refund", bookingId);
                return;
            }
            refundApplicationService.refundForCancelledBooking(
                    userId, payment.getId(), idempotencyKey, reason);
        } catch (RuntimeException exception) {
            // Inventory/ticket/booking cancellation already committed; provider failure must not roll back.
            log.warn("Provider refund after cancellation failed for booking {}: {}",
                    bookingId, exception.getMessage());
        }
    }

    private PaymentAttempt lockSucceededPayment(UUID bookingId) {
        List<PaymentAttempt> attempts = paymentAttemptRepository.findByBookingIdOrderByCreatedAtDesc(bookingId);
        PaymentAttempt succeeded = attempts.stream()
                .filter(attempt -> attempt.getStatus() == PaymentStatus.SUCCEEDED
                        && attempt.getCapturedAmount() != null
                        && attempt.getCapturedAmount().signum() > 0
                        && attempt.getProviderPaymentId() != null
                        && !attempt.getProviderPaymentId().isBlank())
                .findFirst()
                .orElseThrow(() -> new ApplicationConflictException(
                        "Confirmed booking has no captured payment eligible for refund."));
        return paymentAttemptRepository.findByIdForUpdate(succeeded.getId())
                .orElseThrow(() -> new ApplicationConflictException(
                        "Confirmed booking has no captured payment eligible for refund."));
    }

    private List<TripSeatAllocation> lockBookedAllocations(Booking booking) {
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
        return allocations;
    }

    private static void assertActiveItems(Booking booking) {
        if (booking.getItems().stream().anyMatch(item -> item.getStatus() != BookingItemStatus.ACTIVE)) {
            throw new ApplicationConflictException("Booking items are no longer cancellable.");
        }
    }

    private static void assertCancellableBeforeDeparture(Trip trip, Instant now) {
        if (trip.getStatus() == TripStatus.DEPARTED || trip.getStatus() == TripStatus.COMPLETED) {
            throw new ApplicationConflictException(
                    "Confirmed booking cannot be cancelled after the trip has departed or completed.");
        }
        if (!now.isBefore(trip.getScheduledDepartureAt())) {
            throw new ApplicationConflictException(
                    "Confirmed booking cannot be cancelled at or after scheduled departure.");
        }
    }

    private static boolean isIdempotentRepeat(BookingStatus status, BookingCancellation existing) {
        if (existing == null) {
            return false;
        }
        return status == BookingStatus.CANCELLED
                || status == BookingStatus.REFUND_PENDING
                || status == BookingStatus.REFUNDED;
    }

    private void writeBookingCancelled(UUID bookingId, BookingCancellation cancellation, Instant now) {
        outboxEventRepository.save(new OutboxEvent(
                "BOOKING_CANCELLED",
                "BOOKING",
                bookingId,
                "{\"bookingId\":\"" + bookingId
                        + "\",\"cancellationId\":\"" + cancellation.getId() + "\"}",
                now,
                cancellation.getId().toString(),
                null));
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
