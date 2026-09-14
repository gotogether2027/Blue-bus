# Domain model

## Core language

- **Operator:** organization that supplies buses and operates trips.
- **Bus:** physical vehicle belonging to one operator.
- **Seat layout:** reusable template describing physical seats; a layout version must not be silently changed after trips use it.
- **Route:** reusable ordered network path. A route may have multiple boarding/drop points at its stops.
- **Trip:** one scheduled journey of one bus on one route, with an operating/service date.
- **Trip inventory:** the physical-seat snapshot for one trip. It is independent from the bus's permanent seat definition.
- **Seat allocation:** a held or booked seat for one origin-to-destination sequence range. It prevents overlap, while allowing the same physical seat to be sold for a later non-overlapping segment.
- **Hold:** short temporary claim over one or more seat allocations during checkout.
- **Booking:** customer purchase record containing one or more seats and passengers.

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
Seat hold / Booking item (future) *---1 Booking (future)
Booking 1---* Booking item *---0..1 Passenger
Booking 1---* Passenger
Booking 1---* Payment attempt; Payment attempt 1---* Refund
Operator 1---* Operator user *---1 User
```

Customer-facing `AVAILABLE` / `HELD` / `BOOKED` / `BLOCKED` for a requested journey is **derived** from physical inventory plus overlapping active allocations. It is not stored as a whole-trip flag on `TripSeatInventory`. Physical inventory status is only `AVAILABLE` or `BLOCKED`.

V4 `trip_seats` (whole-trip `HELD`/`BOOKED`, `locked_until`, `booking_id`) was replaced in V5. V6 implements the `TripSeatAllocation` foundation (segment ranges + active-state GiST exclusion). V7 implements `SeatHold` as the temporary multi-seat aggregate owning HELD allocations. Bookings and payments remain deferred.

`trips.base_fare` is a temporary draft/default. Future origin–destination prices belong on `trip_fares`.

Many-to-many relationships are represented explicitly when attributes matter: `user_role`, `role_permission`, `operator_user`, `route_stop`, and `booking_item` are join entities, not anonymous join tables.

## Lifecycle rules

1. A scheduler/operator creates a trip from a route, bus, and seat-layout version.
2. The system generates one inventory row per sellable layout seat. This snapshot preserves history if a bus layout later changes.
3. Checkout atomically creates HELD allocations for requested seats and the requested origin/destination sequence range. Active allocations for overlapping ranges cannot coexist for the same inventory seat.
4. A pending booking is created from held seats; payment attempts reference that booking.
5. Only a verified, idempotently processed payment success can change the booking to CONFIRMED and its held allocations to BOOKED.
6. Expiry, failed payment, cancellation, or refund changes allocations according to the cancellation/refund policy. A late payment after hold expiry is not allowed to recreate an allocation automatically. No state may be inferred only from a browser session.

## Recommended state machines

| Entity | States |
|---|---|
| Trip | DRAFT, SCHEDULED, ON_SALE, CLOSED, DEPARTED, COMPLETED, CANCELLED |
| Trip inventory seat | AVAILABLE, BLOCKED (physical-seat status; availability is calculated from allocations) |
| Seat allocation | HELD, BOOKED, RELEASED, CANCELLED, EXPIRED, BLOCKED |
| Booking | PENDING_PAYMENT, CONFIRMED, CANCELLED, EXPIRED, PAYMENT_FAILED, PARTIALLY_REFUNDED, REFUNDED |
| Payment | CREATED, INITIATED, PENDING, SUCCEEDED, FAILED, CANCELLED, REFUNDED, PARTIALLY_REFUNDED |
| Refund | REQUESTED, INITIATED, SUCCEEDED, FAILED, REJECTED |
| Review | PENDING_MODERATION, PUBLISHED, REJECTED, HIDDEN |

Use explicit allowed transitions in application logic. Keep booking/payment state distinct: a booking may be pending while payment is pending, but never confirmed solely because a payment initiation request returned successfully.

The customer-facing state vocabulary remains `AVAILABLE`, `HELD`, `BOOKED`, and `BLOCKED`: for the requested segment it is derived from the physical inventory row plus overlapping active allocations. `AVAILABLE` means no overlap; `HELD`/`BOOKED` mean an overlapping allocation exists; `BLOCKED` means the physical seat or that segment was blocked. This preserves the required UI/business states without incorrectly treating a whole route seat as booked.

## Scope and tenancy

The platform is not separate-database multi-tenant initially. Operator-owned data carries `operator_id`, and all operator-facing queries apply it. Bus, route, trip, and settlement records are operator-scoped. Locations, bus-type taxonomy, platform roles, and configuration are platform-owned. A bus, route, layout, and trip must have the same operator; enforce this with composite foreign keys where practical and application validation otherwise. One operator membership has one role in the MVP; add `operator_user_roles` later only if a membership needs multiple simultaneous roles.

## Missing domains intentionally noted for later decision

- Fare rules, taxes, insurance/add-ons, dynamic pricing, and formal commission policies.
- Service calendars, trip exceptions, driver/crew, vehicle compliance, live tracking, and manifests.
- Cancellation policy snapshots, customer support cases, disputes/chargebacks, and GST invoices.
- Media/documents and operator KYC.

These are not required to start the MVP, but fare/cancellation-policy snapshots and financial adjustments should be designed before real money is accepted.
