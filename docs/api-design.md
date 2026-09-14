# API design

## API conventions

Use `/api/v1`, JSON, UTC ISO-8601 timestamps, UUID identifiers, cursor/page pagination, standard error envelopes, and an idempotency key for create/payment-sensitive requests. APIs expose DTOs, never persistence entities. The backend derives authorization scope from the JWT and rejects unauthorized IDs even if Angular guards permit navigation.

Implemented so far: `GET /api/v1/health` and the Phase 6A.1 admin master-data slice below. Future `GET /api/v1/trips/{tripId}/inventory` must derive seat state for the requested origin/destination stop sequences from physical `TripSeatInventory` plus overlapping allocations, not from a whole-trip BOOKED flag.

## Admin master data (Phase 6A.1)

Convention for this slice:

| Method | Pattern | Success |
|---|---|---|
| `POST` | `/api/v1/admin/{resource}` | `201 Created` |
| `GET` | `/api/v1/admin/{resource}` | `200 OK` (list/search) |
| `GET` | `/api/v1/admin/{resource}/{id}` | `200 OK` |
| `PUT` | `/api/v1/admin/{resource}/{id}` | `200 OK` (full replace of editable fields) |
| `POST` | `/api/v1/admin/{resource}/{id}/activate` | `200 OK` |
| `POST` | `/api/v1/admin/{resource}/{id}/deactivate` | `200 OK` |

Errors use the existing global handler: `400` validation/domain argument errors, `404` missing resource, `409` conflicts (including unique/data integrity). Endpoints require authentication; JWT login is not implemented in this phase.

### Bus types — `/api/v1/admin/bus-types`

- `POST /api/v1/admin/bus-types` — body: `code`, `displayName`
- `GET /api/v1/admin/bus-types` — optional `active`
- `GET /api/v1/admin/bus-types/{id}`
- `PUT /api/v1/admin/bus-types/{id}` — body: `displayName` (code is immutable)
- `POST /api/v1/admin/bus-types/{id}/activate`
- `POST /api/v1/admin/bus-types/{id}/deactivate`

### Locations — `/api/v1/admin/locations`

- `POST /api/v1/admin/locations` — body: `countryCode` (optional, ISO-3166 alpha-2, default `IN`), `state`, `district`, `city`, `locality`, `latitude`, `longitude`, `timeZone`
- `GET /api/v1/admin/locations` — optional `active`, `state`, `city` (substring search)
- `GET /api/v1/admin/locations/{id}`
- `PUT /api/v1/admin/locations/{id}` — same editable fields as create
- `POST /api/v1/admin/locations/{id}/activate`
- `POST /api/v1/admin/locations/{id}/deactivate`

### Operators — `/api/v1/admin/operators`

Uses the existing `OperatorStatus` lifecycle (`PENDING`, `ACTIVE`, `SUSPENDED`, `INACTIVE`). Create starts as `PENDING`. Activate moves from `PENDING`/`SUSPENDED`/`INACTIVE` to `ACTIVE`. Deactivate moves from `PENDING`/`ACTIVE`/`SUSPENDED` to `INACTIVE`.

- `POST /api/v1/admin/operators` — body: `legalName`, `displayName`, optional `supportEmail`, `supportPhoneE164` (E.164)
- `GET /api/v1/admin/operators` — optional `status`
- `GET /api/v1/admin/operators/{id}`
- `PUT /api/v1/admin/operators/{id}` — body: `legalName`, `displayName`, optional `supportEmail`, `supportPhoneE164`
- `POST /api/v1/admin/operators/{id}/activate`
- `POST /api/v1/admin/operators/{id}/deactivate`

### Seat layouts — `/api/v1/admin/seat-layouts` (Phase 6A.2)

Reusable fleet configuration: `SeatLayout` owns physical `Seat` definitions. Layout lifecycle uses existing `SeatLayoutStatus` (`DRAFT`, `PUBLISHED`, `ARCHIVED`). Admin `activate` maps to `publish()`; `deactivate` maps to `archive()`.

- `POST /api/v1/admin/seat-layouts` — body: `operatorId`, `name`, `version`, `deckCount`, `rowCount`, `columnCount`, optional `seats[]` (`seatNumber`, `deckNumber`, `rowNumber`, `columnNumber`, `seatType`, optional `sellable`)
- `GET /api/v1/admin/seat-layouts` — optional `operatorId`, `status`
- `GET /api/v1/admin/seat-layouts/{id}` — includes nested seats
- `PUT /api/v1/admin/seat-layouts/{id}` — metadata only: `name`, `deckCount`, `rowCount`, `columnCount` (operator/version immutable; seats are not replaced)
- `POST /api/v1/admin/seat-layouts/{id}/activate` — publish (`PUBLISHED`); rejected for `ARCHIVED`
- `POST /api/v1/admin/seat-layouts/{id}/deactivate` — archive (`ARCHIVED`)

Seat uniqueness within a layout follows the schema: unique seat number and unique `(deck,row,column)`. Physical seats carry no booking/inventory state.

## Endpoint groups (examples only)

| Group | Example responsibilities | Access |
|---|---|---|
| `/api/v1/auth` | register, login, refresh, logout, password recovery | public/authenticated as applicable |
| `/api/v1/users` | current profile, customer profile | authenticated owner/admin |
| `/api/v1/admin/users`, `/roles`, `/permissions` | user/role administration | authorized admin |
| `/api/v1/operators` | operator onboarding/profile; operator membership | operator admin/platform admin |
| `/api/v1/bus-types`, `/buses`, `/seat-layouts` | fleet master data | scoped operator/admin |
| `/api/v1/locations`, `/routes`, `/trips` | search network; manage routes/schedules | public read / scoped write |
| `/api/v1/search` | origin, destination, service date, passenger count search | public |
| `/api/v1/trips/{tripId}/inventory` | seat map and availability for requested origin/destination and boarding/drop points | public/signed-in per policy |
| `/api/v1/bookings` | hold seats, create booking, retrieve/cancel own booking | customer/scoped staff/admin |
| `/api/v1/payments` | initialize payment, status | booking owner/staff/admin |
| `/api/v1/payments/webhooks/{provider}` | provider callbacks | verified provider signature only |
| `/api/v1/refunds` | request/view refund | policy-authorized |
| `/api/v1/reviews` | submit own eligible review; moderation | customer/admin |
| `/api/v1/coupons` | validate/administer coupons | customer/admin |
| `/api/v1/settlements`, `/reports` | statement/reconciliation/report views | operator/admin according to permission |

## Booking flow contract

1. `POST /trips/{tripId}/holds` requests seat IDs, origin/destination trip-stop IDs, and boarding/drop point IDs. It returns a hold ID and expiry only if every requested seat is atomically allocated for that segment.
2. `POST /bookings` consumes that valid hold and creates a pending-payment booking with passenger, point, fare, tax, coupon, and commission snapshots.
3. `POST /payments` starts a provider payment for that booking using an `Idempotency-Key`.
4. The server trusts a signed provider webhook, not a client success callback, to confirm payment and booking.
5. `GET /bookings/{id}` returns current state; the UI may poll briefly or receive a later notification.

If a provider reports success after the hold has expired or the trip is no longer saleable, the API must show a non-confirmed resolution state. It must not claim a released seat; the payment/refund workflow resolves the money separately.

Never offer a general client API that changes inventory state. Operator/admin APIs must validate trip ownership and state transitions.

## Error and security expectations

- `401` means absent/invalid identity; `403` means authenticated but forbidden; do not leak whether another customer's booking exists.
- Return `409 Conflict` for seat state races and expired holds; clients must refresh availability.
- Rate-limit login, search, hold, coupon validation, and webhook endpoints appropriately.
- Payment webhooks verify signature, event timestamp, and provider event ID before enqueueing/processing.
