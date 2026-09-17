-- Phase 9.6: allow confirmed-booking cancellation records with full refund policy.
-- Unpaid cancellation constraints remain valid; confirmed path adds a second allowed snapshot shape.

ALTER TABLE booking_cancellations
    DROP CONSTRAINT ck_booking_cancellations_previous_status;

ALTER TABLE booking_cancellations
    DROP CONSTRAINT ck_booking_cancellations_policy;

ALTER TABLE booking_cancellations
    DROP CONSTRAINT ck_booking_cancellations_refundable_amount;

ALTER TABLE booking_cancellations
    ADD CONSTRAINT ck_booking_cancellations_policy_snapshot
        CHECK (
            (
                policy_code = 'UNPAID_CUSTOMER_CANCELLATION_V1'
                AND previous_status = 'PENDING_PAYMENT'
                AND refundable_amount = 0
            )
            OR
            (
                policy_code = 'CONFIRMED_FULL_REFUND_CUSTOMER_CANCELLATION_V1'
                AND previous_status = 'CONFIRMED'
                AND refundable_amount > 0
            )
        );
