-- Phase 9.4B correction: at most one TICKET_ISSUED outbox row per ticket aggregate.
-- Other event types may legitimately repeat for the same aggregate_id; do not globalize uniqueness.

CREATE UNIQUE INDEX ux_outbox_ticket_issued_aggregate
    ON outbox_events (aggregate_id)
    WHERE event_type = 'TICKET_ISSUED';
