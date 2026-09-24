package in.bluebustickets.bluebus.notification.application;

import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.bluebustickets.bluebus.booking.domain.Booking;
import in.bluebustickets.bluebus.booking.repository.BookingRepository;
import in.bluebustickets.bluebus.foundation.outbox.OutboxEvent;
import in.bluebustickets.bluebus.notification.domain.NotificationEventType;
import in.bluebustickets.bluebus.payments.domain.PaymentAttempt;
import in.bluebustickets.bluebus.payments.domain.Refund;
import in.bluebustickets.bluebus.payments.repository.PaymentAttemptRepository;
import in.bluebustickets.bluebus.payments.repository.RefundRepository;
import in.bluebustickets.bluebus.ticket.domain.Ticket;
import in.bluebustickets.bluebus.ticket.repository.TicketRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class NotificationRecipientResolver {

    private final BookingRepository bookingRepository;
    private final TicketRepository ticketRepository;
    private final PaymentAttemptRepository paymentAttemptRepository;
    private final RefundRepository refundRepository;
    private final ObjectMapper objectMapper;

    public NotificationRecipientResolver(
            BookingRepository bookingRepository,
            TicketRepository ticketRepository,
            PaymentAttemptRepository paymentAttemptRepository,
            RefundRepository refundRepository,
            ObjectMapper objectMapper) {
        this.bookingRepository = bookingRepository;
        this.ticketRepository = ticketRepository;
        this.paymentAttemptRepository = paymentAttemptRepository;
        this.refundRepository = refundRepository;
        this.objectMapper = objectMapper;
    }

    public Optional<ResolvedRecipient> resolve(OutboxEvent event, NotificationEventType notificationType) {
        return switch (event.getEventType()) {
            case "BOOKING_CONFIRMED", "BOOKING_CANCELLED" -> bookingRecipient(event.getAggregateId());
            case "TICKET_ISSUED" -> ticketRecipient(event.getAggregateId());
            case "PAYMENT_FAILED" -> paymentRecipient(event.getAggregateId());
            case "REFUND_REQUESTED" -> refundRecipient(event.getAggregateId());
            case "REFUND_SUCCEEDED", "REFUND_FAILED" -> refundOrPaymentRecipient(event);
            default -> Optional.empty();
        };
    }

    private Optional<ResolvedRecipient> bookingRecipient(UUID bookingId) {
        return bookingRepository.findById(bookingId)
                .map(booking -> new ResolvedRecipient(
                        booking.getUserId(),
                        booking.getId(),
                        booking.getBookingReference(),
                        null,
                        null,
                        null));
    }

    private Optional<ResolvedRecipient> ticketRecipient(UUID ticketId) {
        return ticketRepository.findById(ticketId)
                .flatMap(ticket -> bookingRepository.findById(ticket.getBookingId())
                        .map(booking -> fromTicket(ticket, booking)));
    }

    private Optional<ResolvedRecipient> paymentRecipient(UUID paymentAttemptId) {
        return paymentAttemptRepository.findById(paymentAttemptId)
                .flatMap(attempt -> bookingRepository.findById(attempt.getBookingId())
                        .map(booking -> fromPayment(attempt, booking, null)));
    }

    private Optional<ResolvedRecipient> refundRecipient(UUID refundId) {
        return refundRepository.findById(refundId)
                .flatMap(refund -> paymentAttemptRepository.findById(refund.getPaymentAttemptId())
                        .flatMap(attempt -> bookingRepository.findById(refund.getBookingId())
                                .map(booking -> fromPayment(attempt, booking, refund))));
    }

    private Optional<ResolvedRecipient> refundOrPaymentRecipient(OutboxEvent event) {
        JsonNode payload = payload(event);
        if (payload != null && payload.hasNonNull("refundId")) {
            try {
                return refundRecipient(UUID.fromString(payload.get("refundId").asText()));
            } catch (IllegalArgumentException ignored) {
                // fall through to payment aggregate
            }
        }
        return paymentRecipient(event.getAggregateId());
    }

    private JsonNode payload(OutboxEvent event) {
        try {
            return objectMapper.readTree(event.getPayloadJson());
        } catch (Exception exception) {
            return null;
        }
    }

    private static ResolvedRecipient fromTicket(Ticket ticket, Booking booking) {
        return new ResolvedRecipient(
                ticket.getUserId(),
                booking.getId(),
                booking.getBookingReference(),
                ticket.getId(),
                ticket.getTicketNumber(),
                null);
    }

    private static ResolvedRecipient fromPayment(PaymentAttempt attempt, Booking booking, Refund refund) {
        return new ResolvedRecipient(
                attempt.getUserId(),
                booking.getId(),
                booking.getBookingReference(),
                null,
                null,
                refund == null ? null : refund.getId());
    }

    public record ResolvedRecipient(
            UUID userId,
            UUID bookingId,
            String bookingReference,
            UUID ticketId,
            String ticketNumber,
            UUID refundId) {
    }
}
