import { canCancel, hasTicket } from './booking-status';

describe('booking status helpers', () => {
  it('allows cancel only for PENDING_PAYMENT and CONFIRMED', () => {
    expect(canCancel('PENDING_PAYMENT')).toBeTrue();
    expect(canCancel('CONFIRMED')).toBeTrue();
    expect(canCancel('INITIATED')).toBeFalse();
    expect(canCancel('CANCELLED')).toBeFalse();
  });

  it('shows ticket actions when a ticket id or status is present', () => {
    expect(hasTicket(null, null)).toBeFalse();
    expect(hasTicket('ticket-1', null)).toBeTrue();
    expect(hasTicket(null, 'ACTIVE')).toBeTrue();
  });
});
