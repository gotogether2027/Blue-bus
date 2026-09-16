# Domain model

## Core language

- **Operator:** organization that supplies buses and operates trips.
- **Bus:** physical vehicle belonging to one operator.
- **Seat layout:** reusable template describing physical seats; a layout version must not be silently changed after trips use it.
- **Route:** reusable ordered network path (`ACTIVE`/`INACTIVE`). A route may have multiple boarding/drop points at its stops. Create starts `ACTIVE`. Deactivate/activate are idempotent and do not cancel trips or rewrite trip snapshots. After **any** trip references the route, structural master edits (source/destination, stops, point details) are refused; name-only and point activate/deactivate remain allowed.
- **Trip:** one scheduled journey of one bus on one route, with an operating/service date. Operator administration (Phase 9.5C) creates trips as `DRAFT`, schedules to `SCHEDULED`, and cancels to `CANCELLED` (status-only; no booking/refund/ticket cascade). Commercial terms may change while `DRAFT`/`SCHEDULED`. Bus/route/layout/schedule are immutable after create. Snapshots (`trip_stops`, `trip_points`, `trip_seat_inventory`) are written at create and never rewritten by master edits. `ON_SALE`/`CLOSED`/`DEPARTED`/`COMPLETED` transitions are not implemented yet.
- **Trip inventory:** the physical-seat snapshot for one trip. It is independent from the bus's permanent seat definition.
- **Seat allocation:** a held or booked seat for one origin-to-destination sequence range. It prevents overlap, while allowing the same physical seat to be sold for a later non-overlapping segment.
- **Hold:** short temporary claim over one or more seat allocations during checkout.
- **Booking:** customer purchase record containing one or more seats and passengers (commercial transaction).
- **Ticket:** immutable customer-facing travel document issued from a `CONFIRMED` booking (one ticket per booking). Snapshots journey OD, schedule, passengers, seats, fares, and operator display name at issuance.

## Relationships

```text
Operator 1---* Bus 1---1 Seat layout 1---* Layout seat
Operator 1---* Route 1---* Route stop *---1 Location
Route stop 1---* Route point
Bus 1---* Trip *---1 Route
Trip 1---* Trip stop snapshot *---1 Location
Trip stop 1---* Trip point snapshot
Trip 1---* TripSeatInventory *---1 Layout seat
TripSeatInventory 1---* TripSeatAllocation *---0..1 SeatHold
SeatHold 1---* TripSeatAllocation (HELD)
SeatHold 1---0..1 Booking (consumed hold)
Booking 1---* BookingItem *---0..1 BookingPassenger
Booking 1---* BookingPassenger
Booking 1---0..1 BookingCancellation
Booking 1---* PaymentAttempt; PaymentAttempt 1---* ProviderEvent; PaymentAttempt 1---* Refund
Booking 1---0..1 Ticket 1---* TicketPassenger
Operator 1---* Operator user *---1 User
```

Customer-facing journey availability for a requested OD segment is a **read-only projection** (`SeatAvailabilityService`): physical `AVAILABLE`/`BLOCKED` inventory plus overlapping active allocations (`HELD`/`BOOKED`/`BLOCKED`). It is not a stored column. A `HELD` row whose `expires_at` is past still blocks until an explicit expire/cancel transition (no implicit expiry in the projection). Trip saleability (`ON_SALE` / booking window) is not gated yet.

Customer-facing UI vocabulary `AVAILABLE` / `HELD` / `BOOKED` / `BLOCKED` for a requested journey remains **derived** from physical inventory plus overlapping active allocations. It is not stored as a whole-trip flag on `TripSeatInventory`. Physical inventory status is only `AVAILABLE` or `BLOCKED`.

V4 `trip_seats` (whole-trip `HELD`/`BOOKED`, `locked_until`, `booking_id`) was replaced in V5. V6 implements the `TripSeatAllocation` foundation (segment ranges + active-state GiST exclusion). V7 implements `SeatHold` as the temporary multi-seat aggregate owning HELD allocations. Phase 7.4 adds an explicit hold expiry reaper: due `ACTIVE` holds (`expires_at <= now`) become `EXPIRED` and their `HELD` allocations become `EXPIRED` in one DB transaction per hold (`FOR UPDATE SKIP LOCKED`). Availability does **not** invent implicit expiry from timestamps — only the committed state change stops blocking. **V9 / Phase 9.1** implements bookings + passengers + hold-to-book: an authenticated customer converts an ACTIVE **owned** hold (`seat_holds.user_id` = booker) into a `PENDING_PAYMENT` booking with persisted `payment_expires_at`; allocations become `BOOKED` and the hold becomes `CONSUMED` in one transaction. Anonymous holds remain creatable but are not bookable. **V10 / unpaid expiry** expires due `PENDING_PAYMENT` bookings (`payment_expires_at <= now`) to `EXPIRED`, marks items `EXPIRED`, and releases matching allocations `BOOKED` → `RELEASED` (historical rows retained). Availability unblocks only after that committed release — timestamps are not implicit. **V12** adds customer booking views (JWT-subject ownership; cross-customer `404`), unpaid customer cancellation (`PENDING_PAYMENT` → `CANCELLED`, allocations `BOOKED` → `CANCELLED`, immutable `booking_cancellations`), and public OD search using trip-stop sequences plus the existing segment-overlap availability count. **Phase 9.3** adds Razorpay behind the provider-neutral payment adapter; Booking still has no Razorpay-specific fields. **Phase 9.4A / V13** adds `Ticket` / `TicketPassenger` snapshots with customer-facing ticket numbers (`BB` + 8 unambiguous chars via `SecureRandom`); status lifecycle is `ACTIVE` / `CANCELLED` (no auto-cancel wiring). **Phase 9.4B** consumes `BOOKING_CONFIRMED` via the local outbox processor and writes `TICKET_ISSUED`. Confirmed cancellation/refund policy, RabbitMQ, PDF/QR, and notifications remain deferred.

`trips.base_fare` is a temporary draft/default. Future origin–destination prices belong on `trip_fares`.

Many-to-many relationships are represented explicitly when attributes matter: `user_role`, `role_permission`, `operator_user`, `route_stop`, and `booking_item` are join entities, not anonymous join tables.

## Lifecycle rules

1. A scheduler/operator creates a trip from an **ACTIVE** route, bus, and seat-layout version. Trip creation snapshots `trip_stops` / `trip_points` from the master route; later master route edits must not rewrite those snapshots.
2. The system generates one inventory row per sellable layout seat. This snapshot preserves history if a bus layout later changes.
3. Checkout atomically creates HELD allocations for requested seats and the requested origin/destination sequence range. Active allocations for overlapping ranges cannot coexist for the same inventory seat.
4. A pending booking is created from held seats; payment attempts reference that booking.
5. Only a verified, idempotently processed payment success can change the booking to CONFIRMED. Allocations are already BOOKED at hold-to-book; unpaid expiry releases them (`BOOKED` → `RELEASED`) if payment never arrives. A late payment after unpaid expiry must not recreate an allocation automatically.
6. Expiry, failed payment, cancellation, or refund changes allocations according to the cancellation/refund policy. No state may be inferred only from a browser session. Unpaid booking expiry uses the persisted `payment_expires_at` and PostgreSQL row locks (`FOR UPDATE SKIP LOCKED` per booking). Unpaid customer cancellation locks the booking first, then allocations, and is refused with a conflict when the booking is already `CONFIRMED` because confirmed refund policy is not yet defined.
7. A ticket may be issued only for a `CONFIRMED` booking. Issuance is idempotent (database unique on `booking_id`). The ticket stores a customer-facing snapshot and does not silently follow later booking/trip/fleet changes. **Phase 9.4B** issues tickets automatically from unpublished `BOOKING_CONFIRMED` outbox events; manual customer issue remains compatible. PDF/QR and notifications remain deferred.

## Recommended state machines

| Entity | States |
|---|---|
| Trip | DRAFT, SCHEDULED, ON_SALE, CLOSED, DEPARTED, COMPLETED, CANCELLED |
| Trip inventory seat | AVAILABLE, BLOCKED (physical-seat status; availability is calculated from allocations) |
| Seat allocation | HELD, BOOKED, RELEASED, CANCELLED, EXPIRED, BLOCKED |
| Booking | INITIATED, PENDING_PAYMENT, CONFIRMED, CANCELLED, EXPIRED, REFUND_PENDING, REFUNDED |
| BookingCancellation (V12) | COMPLETED (unpaid customer cancellation only) |
| Ticket (V13) | ACTIVE, CANCELLED |
| PaymentAttempt (V11) | INITIATING, PENDING, SUCCEEDED, FAILED, CANCELLED, EXPIRED |
| Payment disposition (V11) | UNAPPLIED, APPLIED_TO_BOOKING, REQUIRES_RESOLUTION |
| Refund | REQUESTED, INITIATED, SUCCEEDED, FAILED, REJECTED |
| Review | PENDING_MODERATION, PUBLISHED, REJECTED, HIDDEN |

Use explicit allowed transitions in application logic. Keep booking/payment state distinct: a booking may be pending while payment is pending, but never confirmed solely because a payment initiation request returned successfully.

V11 allows `INITIATING → PENDING`, `INITIATING/PENDING → FAILED`, and any non-success attempt to record verified `SUCCEEDED`. A successful attempt is either `APPLIED_TO_BOOKING` or `REQUIRES_RESOLUTION`; success is never downgraded by a later pending/failure event. A verified on-time success locks Booking and confirms only `PENDING_PAYMENT`. Success against `EXPIRED`, `CANCELLED`, an already-confirmed booking, a post-deadline financial timestamp, or mismatched money remains auditable but requires resolution and never recreates inventory.

The customer-facing state vocabulary remains `AVAILABLE`, `HELD`, `BOOKED`, and `BLOCKED`: for the requested segment it is derived from the physical inventory row plus overlapping active allocations. `AVAILABLE` means no overlap; `HELD`/`BOOKED` mean an overlapping allocation exists; `BLOCKED` means the physical seat or that segment was blocked. This preserves the required UI/business states without incorrectly treating a whole route seat as booked.

## Scope and tenancy

The platform is not separate-database multi-tenant initially. Operator-owned data carries `operator_id`, and all operator-facing queries apply it. Bus, route, trip, and settlement records are operator-scoped. Locations, bus-type taxonomy, platform roles, and configuration are platform-owned. A bus, route, layout, and trip must have the same operator; enforce this with composite foreign keys where practical and application validation otherwise. One operator membership has one role in the MVP; add `operator_user_roles` later only if a membership needs multiple simultaneous roles.

Bus lifecycle statuses are `ACTIVE`, `INACTIVE`, and `MAINTENANCE`. New trips require an `ACTIVE` bus. A trip freezes the bus's seat-layout id at create time; changing a bus's layout while any trip row exists is rejected (application `409` plus composite FK). Registration numbers are globally unique ignoring case.

## Missing domains intentionally noted for later decision

- Fare rules, taxes, insurance/add-ons, dynamic pricing, and formal commission policies.
- Service calendars, trip exceptions, driver/crew, vehicle compliance, live tracking, and manifests.
- Cancellation policy snapshots for **confirmed** bookings, customer support cases, disputes/chargebacks, and GST invoices.
- Media/documents and operator KYC.

These are not required to start the MVP, but fare/cancellation-policy snapshots and financial adjustments should be designed before real money is accepted.
