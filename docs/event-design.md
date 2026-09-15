# Event design

## Event delivery approach

V11 persists business events in `outbox_events` in the same transaction as payment/booking changes. V12 also writes `BOOKING_CANCELLED` in the unpaid-cancellation transaction. **Phase 9.4B** adds a local scheduled database outbox processor that consumes unpublished `BOOKING_CONFIRMED` rows and issues tickets in-process. Broker publishing (RabbitMQ) remains deferred: other event types stay unpublished until a future publisher/consumer path exists.

An event should include `event_id`, `event_type`, `occurred_at`, `aggregate_type`, `aggregate_id`, `schema_version`, correlation/causation IDs, and minimal non-sensitive payload. Do not send full passenger PII by default. Ticket processors load authoritative booking data from PostgreSQL using `aggregate_id` / `bookingId` — the payload is not the source of passenger/fare/seat snapshots.

## Candidate events and consumers

| Event | Publisher | Consumers / reason |
|---|---|---|
| `BOOKING_CREATED` | Booking | audit/read-model update; notifies only when useful |
| `BOOKING_CONFIRMED` | Booking confirmation TX (`BookingPaymentService` / `BookingLifecycleService`) | **Phase 9.4B local outbox processor → ticket issuance**; future RabbitMQ: notification, operator dashboard, settlement accrual, analytics |
| `TICKET_ISSUED` | Ticket issuance TX | future notification/PDF/QR consumers; not re-consumed as booking confirmation |
| `BOOKING_CANCELLED` | Booking | notification, inventory projection, refund workflow trigger |
| `PAYMENT_INITIATED` | Payments | audit/monitoring |
| `PAYMENT_SUCCEEDED` | Payments | receipt notification / audit (booking confirm is synchronous in the payment TX) |
| `PAYMENT_REQUIRES_RESOLUTION` | Payments | late/invalid-success compensation or refund workflow, alerting |
| `PAYMENT_FAILED` | Payments | release/expire pending booking workflow, notification |
| `REFUND_INITIATED` | Payments | customer/operator notification and reconciliation |
| `REFUND_COMPLETED` | Payments | booking/refund status projection, notification, settlement adjustment |
| `TRIP_CANCELLED` | Scheduling | affected-booking workflow, customer notification, refund review |
| `TRIP_REMINDER` | Scheduler | notification delivery |
| `BOARDING_REMINDER` | Scheduler | notification delivery |
| `REVIEW_ELIGIBLE` | Booking/Scheduler | invite/review eligibility projection |
| `SETTLEMENT_GENERATED` | Settlement | operator notification/export |

## Phase 9.4B automatic ticket issuance

Flow:

```text
Payment success / confirmLockedPendingPayment
        → Booking PENDING_PAYMENT → CONFIRMED
        → BOOKING_CONFIRMED outbox row (same TX)
        → OutboxProcessorService (scheduled, SKIP LOCKED)
        → TicketApplicationService (idempotent; UNIQUE tickets.booking_id)
        → TICKET_ISSUED outbox row (same TX as ticket create)
        → BOOKING_CONFIRMED.published_at set
```

- Only `BOOKING_CONFIRMED` is processed by the local processor in this phase.
- Failed handling increments `attempt_count` in a separate short TX and leaves `published_at` null for retry.
- Re-confirming an already `CONFIRMED` booking does not write another `BOOKING_CONFIRMED`.
- Manual `POST /api/v1/bookings/{id}/tickets` remains compatible and also writes `TICKET_ISSUED` when creating.
- Notifications, PDF/QR, and RabbitMQ remain deferred.

## What must stay synchronous

Seat conditional update, hold validation, pending booking creation, and recording a payment webhook must be transactional request/workflow actions. Do not make availability or payment confirmation depend solely on a queued message; queue delay/failure would create inconsistent customer-visible state.

The webhook ingress first records a verified normalized provider event exactly once. Razorpay Checkout verification uses the same inbox (`provider_event_id` = `checkout:{payment_id}`) after HMAC over the stored order id. Processing then locks booking, payment attempt and allocations and atomically updates payment disposition, confirms an eligible booking (which writes `BOOKING_CONFIRMED`), completes the inbox event, and writes payment outbox rows. Booking confirmation does not depend on a future queue. If Booking is expired/cancelled, money is recorded as `SUCCEEDED / REQUIRES_RESOLUTION` and `PAYMENT_REQUIRES_RESOLUTION` is written; no seat is recreated. `REFUND_SUCCEEDED` is written when a refund reaches a terminal success either from the Razorpay refund API or a later `refund.processed` webhook.

Razorpay webhook handlers must acknowledge within ~5s. That path never performs outbound provider HTTP; it only verifies the raw body, records the inbox row, and runs the bounded local state machine above before returning 2xx. Ticket issuance is asynchronous via the outbox processor and must not run inside the webhook path.

## Reliability controls

- Unique `outbox_events.id`; the Phase 9.4B local processor marks `published_at` only after successful handling of supported types. A future broker publisher can reuse the same column after broker acknowledgement.
- Consumer inbox/deduplication by event ID and provider event ID.
- Retry with bounded backoff for broker consumers; local processor retries unpublished rows on the next poll (`attempt_count` visibility).
- Version events compatibly; consumers ignore unknown additive fields.
- Preserve correlation IDs across hold, booking, payment, refund, and notifications.
