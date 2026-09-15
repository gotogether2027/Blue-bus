-- Phase 9.2: persist unpaid-booking payment deadline and allow EXPIRED booking items.
-- Payments, tickets, and refunds remain deferred. Allocations stay segment-aware (no whole-trip sale state).

ALTER TABLE bookings
    ADD COLUMN payment_expires_at TIMESTAMPTZ;

UPDATE bookings
   SET payment_expires_at = created_at + INTERVAL '15 minutes'
 WHERE payment_expires_at IS NULL;

ALTER TABLE bookings
    ALTER COLUMN payment_expires_at SET NOT NULL;

CREATE INDEX ix_bookings_pending_payment_expires
    ON bookings (payment_expires_at)
    WHERE status = 'PENDING_PAYMENT';

ALTER TABLE booking_items
    DROP CONSTRAINT ck_booking_items_status;

ALTER TABLE booking_items
    ADD CONSTRAINT ck_booking_items_status
    CHECK (status IN ('ACTIVE', 'CANCELLED', 'REFUNDED', 'EXPIRED'));
