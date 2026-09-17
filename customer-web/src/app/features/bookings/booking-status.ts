import { BookingStatus, PaymentStatus, RefundStatus, TicketStatus } from '../../core/api/models';

export function badgeTone(
  status: BookingStatus | PaymentStatus | TicketStatus | RefundStatus | null | undefined
): 'neutral' | 'success' | 'warn' | 'danger' | 'info' {
  switch (status) {
    case 'CONFIRMED':
    case 'SUCCEEDED':
    case 'ACTIVE':
      return 'success';
    case 'PENDING_PAYMENT':
    case 'INITIATING':
    case 'PENDING':
    case 'REQUESTED':
    case 'PROCESSING':
    case 'INITIATED':
      return 'warn';
    case 'CANCELLED':
    case 'EXPIRED':
    case 'FAILED':
      return 'danger';
    case 'REFUND_PENDING':
    case 'REFUNDED':
      return 'info';
    default:
      return 'neutral';
  }
}

export function canCancel(status: BookingStatus): boolean {
  return status === 'PENDING_PAYMENT' || status === 'CONFIRMED';
}

export function hasTicket(ticketId: string | null, ticketStatus: TicketStatus | null): boolean {
  return !!ticketId || !!ticketStatus;
}
