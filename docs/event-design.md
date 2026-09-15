# Event design

## Event delivery approach

V11 persists business events in `outbox_events` in the same transaction as payment/booking changes. V12 also writes `BOOKING_CANCELLED` in the unpaid-cancellation transaction. Publishing is intentionally not implemented yet. When a broker is added, publish only after the owning transaction commits; consumers remain idempotent because at-least-once delivery can duplicate messages.

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

The webhook ingress first records a verified normalized provider event exactly once. Processing then locks booking, payment attempt and allocations and atomically updates payment disposition, confirms an eligible booking, completes the inbox event, and writes outbox rows. Booking confirmation does not depend on a future queue. If Booking is expired/cancelled, money is recorded as `SUCCEEDED / REQUIRES_RESOLUTION` and `PAYMENT_REQUIRES_RESOLUTION` is written; no seat is recreated.

## Reliability controls

- Unique `outbox_events.id`; a future publisher marks `published_at` only after broker acknowledgement.
- Consumer inbox/deduplication by event ID and provider event ID.
- Retry with bounded backoff; dead-letter queues with alerts and replay procedure.
- Version events compatibly; consumers ignore unknown additive fields.
- Preserve correlation IDs across hold, booking, payment, refund, and notifications.
