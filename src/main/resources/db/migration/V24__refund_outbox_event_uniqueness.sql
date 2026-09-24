-- One REFUND_REQUESTED and one REFUND_FAILED outbox row per refund aggregate.
-- Other event types, including REFUND_SUCCEEDED, may still repeat for an aggregate.

CREATE UNIQUE INDEX ux_outbox_refund_requested_aggregate
    ON outbox_events (aggregate_id)
    WHERE event_type = 'REFUND_REQUESTED';

CREATE UNIQUE INDEX ux_outbox_refund_failed_aggregate
    ON outbox_events (aggregate_id)
    WHERE event_type = 'REFUND_FAILED';
