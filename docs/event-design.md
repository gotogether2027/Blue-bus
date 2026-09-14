# Event design

## Event delivery approach

Publish business events from a transactional outbox after the owning database transaction commits. RabbitMQ is appropriate for work that does not need to delay the customer response: notifications, projections, reminders, reports, and integrations. Consumers must be idempotent because at-least-once delivery can duplicate messages.

An event should include `event_id`, `event_type`, `occurred_at`, `aggregate_type`, `aggregate_id`, `schema_version`, correlation/causation IDs, and minimal non-sensitive payload. Do not send full passenger PII by default.

## Candidate events and consumers

| Event | Publisher | RabbitMQ consumers / reason |
|---|---|---|
| `BOOKING_CREATED` | Booking | audit/read-model update; notifies only when useful |
| `BOOKING_CONFIRMED` | Booking | ticket notification, operator dashboard, settlement accrual, analytics |
| `BOOKING_CANCELLED` | Booking | notification, inventory projection, refund workflow trigger |
| `PAYMENT_INITIATED` | Payments | audit/monitoring |
| `PAYMENT_SUCCEEDED` | Payments | booking confirmation command/workflow, receipt notification |
| `PAYMENT_REQUIRES_RESOLUTION` | Payments | late/invalid-success compensation or refund workflow, alerting |
| `PAYMENT_FAILED` | Payments | release/expire pending booking workflow, notification |
| `REFUND_INITIATED` | Payments | customer/operator notification and reconciliation |
| `REFUND_COMPLETED` | Payments | booking/refund status projection, notification, settlement adjustment |
| `TRIP_CANCELLED` | Scheduling | affected-booking workflow, customer notification, refund review |
| `TRIP_REMINDER` | Scheduler | notification delivery |
| `BOARDING_REMINDER` | Scheduler | notification delivery |
| `REVIEW_ELIGIBLE` | Booking/Scheduler | invite/review eligibility projection |
| `SETTLEMENT_GENERATED` | Settlement | operator notification/export |

## What must stay synchronous

Seat conditional update, hold validation, pending booking creation, and recording a payment webhook must be transactional request/workflow actions. Do not make availability or payment confirmation depend solely on a queued message; queue delay/failure would create inconsistent customer-visible state.

The webhook transaction records the verified provider event exactly once and updates the payment state. In the modular monolith, the booking-confirmation application workflow is invoked reliably from that committed work (or a durable internal command), locks the held allocations, and writes its own outbox event. A consumer must re-check booking/hold/trip state rather than assuming `PAYMENT_SUCCEEDED` is still actionable. If the hold has expired, publish `PAYMENT_REQUIRES_RESOLUTION`; do not book a seat or silently lose the payment.

## Reliability controls

- Unique `outbox_event.event_id`; publisher marks sent only after broker acknowledgement.
- Consumer inbox/deduplication by event ID and provider event ID.
- Retry with bounded backoff; dead-letter queues with alerts and replay procedure.
- Version events compatibly; consumers ignore unknown additive fields.
- Preserve correlation IDs across hold, booking, payment, refund, and notifications.
