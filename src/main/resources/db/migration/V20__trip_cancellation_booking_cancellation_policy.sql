-- Trip-cancellation passenger handling: allow operator/admin cascade snapshots
-- without changing customer unpaid/confirmed cancellation shapes.

ALTER TABLE booking_cancellations
    DROP CONSTRAINT ck_booking_cancellations_policy_snapshot;

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
            OR
            (
                policy_code = 'TRIP_CANCELLED_FULL_REFUND_V1'
                AND previous_status = 'CONFIRMED'
                AND refundable_amount > 0
            )
            OR
            (
                policy_code = 'TRIP_CANCELLED_UNPAID_V1'
                AND previous_status = 'PENDING_PAYMENT'
                AND refundable_amount = 0
            )
        );
