# API design

## API conventions

Use `/api/v1`, JSON, UTC ISO-8601 timestamps, UUID identifiers, cursor/page pagination, standard error envelopes, and an idempotency key for create/payment-sensitive requests. APIs expose DTOs, never persistence entities. The backend derives authorization scope from the JWT and rejects unauthorized IDs even if Angular guards permit navigation.

Implemented so far: `GET /api/v1/health`, customer registration + login + refresh/logout + `/auth/me`, admin master-data/trip APIs, public journey seat availability, public temporary seat holds (optional JWT ownership), and authenticated hold-to-booking (`/api/v1/bookings`). Payment provider integration, tickets, refunds, email verification, and profile editing remain deferred.

## Customer registration & identity — Phase 8.2

Self-service customer account creation. Registration does **not** issue a JWT; clients call login afterwards.

| Method | Path | Auth | Success |
|---|---|---|---|
| `POST` | `/api/v1/auth/register` | public | `201 Created` |
| `GET` | `/api/v1/auth/me` | Bearer JWT | `200 OK` |

Register request:

```json
{
  "firstName": "Rahul",
  "lastName": "Kumar",
  "email": "rahul@example.com",
  "password": "StrongPassword123!"
}
```

Required: `firstName`, `email`, `password` (8–72 chars, at least one letter and one digit). Optional: `lastName`. Email is trimmed and lower-cased (same normalization as login). Password is BCrypt-hashed with the Phase 8.1 encoder and never stored or returned in plaintext. Server always assigns platform role `CUSTOMER` and status `ACTIVE`. Clients cannot choose role, status, or `password_hash`.

Register / me response:

```json
{
  "userId": "...",
  "firstName": "Rahul",
  "lastName": "Kumar",
  "email": "rahul@example.com",
  "roles": ["CUSTOMER"],
  "status": "ACTIVE"
}
```

Duplicate email (including case-insensitive / whitespace variants) → `409` with a safe conflict message. DB unique index `uq_users_email_normalized` on `lower(email)` remains authoritative under races.

`GET /api/v1/auth/me` loads identity from the JWT `sub` (user id). Query parameters such as `userId` are ignored. Missing/invalid JWT → `401`.

## Authentication — Phase 8.1 / 8.3

Stateless JWT **access** tokens plus opaque **refresh** tokens (session families). Passwords are verified with BCrypt against `users.password_hash`. Access tokens are HS256 JWTs signed with `blue-bus.security.jwt.secret`. Refresh tokens are random 32-byte secrets (URL-safe Base64, no padding); only `SHA-256` digests are stored (`refresh_tokens.token_hash`).

| Method | Path | Auth | Success |
|---|---|---|---|
| `POST` | `/api/v1/auth/login` | public | `200 OK` |
| `POST` | `/api/v1/auth/refresh` | public (refresh credential) | `200 OK` |
| `POST` | `/api/v1/auth/logout` | public (refresh credential) | `204 No Content` |

Login request:

```json
{
  "email": "user@example.com",
  "password": "..."
}
```

Login / refresh success response:

```json
{
  "accessToken": "...",
  "tokenType": "Bearer",
  "expiresIn": 900,
  "refreshToken": "..."
}
```

Refresh / logout request:

```json
{
  "refreshToken": "..."
}
```

**Rotation:** each successful refresh revokes the presented token, inserts a replacement in the same `family_id`, and returns a new access + refresh pair. The old refresh token never works again.

**Concurrent refresh:** two clients presenting the same active token are serialized with `SELECT … FOR UPDATE`. Exactly one rotation succeeds (`200`). The loser sees the predecessor already rotated to a still-active successor within a short configured window (`blue-bus.security.refresh.concurrent-reuse-grace-seconds`, default `5`) and receives generic `401` **without** family revocation, so the winner’s new refresh token remains usable. The server cannot re-issue the winner’s opaque token (hash-only storage), so the loser must retry with a fresh login or the winner’s token if shared by the client.

**Reuse / replay:** presenting a revoked/replaced refresh token **after** that concurrency window (or when the successor is no longer the active family tip) revokes **all active tokens in that family** and returns generic `401`. Other families for the same user (other devices) stay active. Logout-revoked tokens (no `replaced_by_id`) are never treated as concurrent collisions.

**Multi-device:** each login creates a new family. Logout revokes only the presented family. Access JWTs are not revoked on logout and remain valid until `exp`.

**Errors:** blank/malformed refresh body → `400`. Unknown, expired, revoked, reused, or inactive/suspended user → generic `401` (`Invalid credentials.`). No token/family/user existence leaks.

JWT claims (non-sensitive): `sub` (user id), `iss`, `iat`, `exp`, `email`, `roles` (platform `user_roles` codes such as `ADMIN`, `CUSTOMER`). Operator memberships (`operator_users`) are not embedded yet. Refresh tokens are never placed in JWT claims.

Configuration:

- `blue-bus.security.jwt.issuer` / `JWT_ISSUER` (default `blue-bus`)
- `blue-bus.security.jwt.secret` / `JWT_SECRET` (**required**, ≥ 32 bytes; never commit production secrets)
- `blue-bus.security.jwt.access-token-ttl-seconds` / `JWT_ACCESS_TOKEN_TTL_SECONDS` (default `900`)
- `blue-bus.security.refresh.ttl-seconds` / `REFRESH_TOKEN_TTL_SECONDS` (default `1209600` / 14 days)
- `blue-bus.security.refresh.concurrent-reuse-grace-seconds` / `REFRESH_TOKEN_CONCURRENT_REUSE_GRACE_SECONDS` (default `5`; max `30`)

Public without a token: health, **register**, login, **refresh**, **logout**, **trip search**, seat-availability, and temporary seat-hold create/get/cancel. `GET /api/v1/auth/me` and all other APIs (including admin) require `Authorization: Bearer <accessToken>`. Invalid login (unknown user, wrong password, disabled/`SUSPENDED`/`INACTIVE`, missing hash) returns a generic `401` with message `Invalid credentials.` — no existence leak. Password hashes and refresh-token hashes are never returned.

**Phase 9.2A authorization:** protected application requests re-check `users.status` in the database. An otherwise valid access JWT for a `SUSPENDED` or `INACTIVE` user receives generic `401` (same envelope as a missing/invalid JWT). Existing `/api/v1/admin/**` application services additionally require `user_roles` to contain `ADMIN` or `SUPER_ADMIN`; authenticated callers without that privilege receive `403`. JWT `roles` claims are not used as the source of truth for platform admin. Customer booking/payment APIs remain owner-based (`JWT sub` == resource owner) and do **not** require a `CUSTOMER` role. Operator portal APIs are not in this slice.

Deferred: password reset, email/phone verification, profile editing, permission catalogs, operator-scoped HTTP authorization, refresh-token cleanup/reaper (retain revoked/expired rows ≥ 30 days for reuse detection; indexes support future cleanup), access-token denylist.

## Customer bookings — Phase 9.1 foundation + unpaid expiry + V12 views/cancellation

Authenticated hold-to-booking conversion, owner views, and unpaid customer cancellation. Does **not** process production payments, invent a confirmed-booking refund policy, or issue tickets. Unpaid `PENDING_PAYMENT` bookings expire automatically after the persisted payment deadline.

| Method | Path | Auth | Success |
|---|---|---|---|
| `POST` | `/api/v1/bookings` | Bearer JWT | `201 Created` |
| `GET` | `/api/v1/bookings` | Bearer JWT (owner list) | `200 OK` |
| `GET` | `/api/v1/bookings/{bookingId}` | Bearer JWT (owner) | `200 OK` |
| `POST` | `/api/v1/bookings/{bookingId}/cancel` | Bearer JWT (owner) | `200 OK` |

Create request:

```json
{
  "holdId": "...",
  "originStopId": "...",
  "destinationStopId": "...",
  "idempotencyKey": "client-retry-key",
  "passengers": [
    { "seatInventoryId": "...", "fullName": "Ada Lovelace", "age": 36, "gender": "FEMALE" }
  ]
}
```

Required: `holdId`, matching OD stop IDs, non-blank `idempotencyKey`, one passenger per held seat (`seatInventoryId` unique). Optional passenger `age`/`gender`.

**Transaction:** lock hold `FOR UPDATE` → validate owned + ACTIVE + unexpired → create `PENDING_PAYMENT` booking + passengers + items → mark allocations `HELD`→`BOOKED` → consume hold. All-or-nothing; GiST exclusion unchanged. Unique-constraint races abort that insert TX (rollback-only); the facade then reads the winner in a fresh transaction and returns the same booking — never continues the failed session.

**Fare assumption:** `totalAmount = trips.base_fare × seatCount` (tax/fee/discount = 0) until `trip_fares` exists.

**Allocation assumption:** seats become `BOOKED` at booking create so consumed holds are not left with expiring `HELD` rows. Booking status remains `PENDING_PAYMENT` until a verified provider event atomically sets the applied payment and booking to `CONFIRMED`.

**Payment deadline:** `paymentExpiresAt` is set at create (`now + blue-bus.bookings.unpaid.ttl-seconds`, default 900 / 15 minutes) and persisted. It is returned on booking responses. Clients never supply it. The unpaid reaper uses this stored deadline — it does not recompute TTL from `createdAt`.

**Unpaid expiry:** a scheduled job (default every 30s, batch 100, `blue-bus.bookings.expiry.*`) expires due `PENDING_PAYMENT` rows (`payment_expires_at <= now`). Each booking is locked `FOR UPDATE SKIP LOCKED` in its own transaction: booking → `EXPIRED`, items → `EXPIRED`, that booking's `BOOKED` allocations → `RELEASED`. Historical booking/passenger/item rows are kept. `CONFIRMED` / `CANCELLED` / other terminal states are never expired. Concurrent confirm/cancel lock the booking `FOR UPDATE` (wait); exactly one transition wins.

**Idempotency:** unique partial index `(user_id, idempotency_key)`. Same key + same request fingerprint returns the existing booking (`201`). Same key + different fingerprint → `409`. Concurrent same-key/same-fingerprint resolves to one booking (hold `FOR UPDATE` serialization and/or unique constraint + fresh read). Unique `hold_id` prevents double-consume.

**Hold ownership (booking):** `seat_holds.user_id` must equal the JWT booker. Anonymous holds (`user_id IS NULL`) → `409` (not bookable; UUID knowledge is not ownership). Another customer’s hold → `404`. Clients must create the hold with a Bearer JWT before `POST /bookings`.

**Booking ownership:** JWT `sub` is the booking owner. Clients never supply a user ID. Cross-customer get/cancel returns generic `404`. List returns only the caller’s bookings. An owner may retrieve an `EXPIRED` or `CANCELLED` booking (`200` with historical passengers/items). Responses include trip origin/destination snapshots, boarding/drop points, amounts, payment deadline, booking status, and item status. They never include payment secrets, webhook payloads, or internal resolution reasons.

**Unpaid customer cancellation (V12):** `POST /api/v1/bookings/{bookingId}/cancel` is supported only for `PENDING_PAYMENT`. The transaction locks the booking `FOR UPDATE` first, then the matching allocations, marks booking/items `CANCELLED`, transitions `BOOKED` allocations to `CANCELLED`, writes an immutable `booking_cancellations` row (`UNPAID_CUSTOMER_CANCELLATION_V1`, refundable amount `0`), and writes `BOOKING_CANCELLED` to the outbox. Historical booking/item/allocation rows are kept. Repeat cancel of an already-cancelled booking with a cancellation record is idempotent (`200`). Confirmed cancellation/refund policy is not defined yet — `CONFIRMED` and other unsupported states return `409` rather than inventing a refund. Concurrent expiry uses `SKIP LOCKED`; confirmation/cancellation wait on the booking lock. Exactly one of cancel, expiry, or confirmation wins; cancellation never recreates inventory.

**Errors:** invalid/expired/cancelled/consumed hold → `409`; anonymous hold → `409`; validation → `400`; unauthenticated → `401`; other customer’s hold/booking → `404`; unsupported cancellation state (including `CONFIRMED`) → `409`.

## Customer trip search — V12

Public read-only search. Does not mutate inventory or calculate whole-trip occupancy.

| Method | Path | Auth | Success |
|---|---|---|---|
| `GET` | `/api/v1/search/trips` | Public | `200 OK` |

Query parameters (all required):

- `originLocationId` — `locations.id`
- `destinationLocationId` — `locations.id` (must differ from origin)
- `serviceDate` — ISO date of `trips.service_date`

Only `SCHEDULED` / `ON_SALE` trips are returned, and only when the origin trip-stop sequence is strictly less than the destination trip-stop sequence. The response includes operator/bus/route identity, base fare, trip-specific boarding points at origin, drop points at destination, and `availableSeatCount` from the existing segment-overlap availability projection (active `HELD`/`BOOKED`/`BLOCKED` allocations on `[origin,destination)`). Adjacent non-overlapping segments remain independently countable. Same origin and destination → `400`. Unknown location → `404`. Empty result is `200 []`.

## Customer payment foundation — V11

| Method | Path | Auth | Success |
|---|---|---|---|
| `POST` | `/api/v1/bookings/{bookingId}/payments` | Bearer JWT (owner) + `Idempotency-Key` | `201 Created` |
| `GET` | `/api/v1/payments/{paymentAttemptId}` | Bearer JWT (owner) | `200 OK` |
| `POST` | `/api/v1/payments/webhooks/{provider}` | Provider signature through registered adapter; no JWT | `200 OK` |

Initiation has no amount/currency request fields. The server locks the owned `PENDING_PAYMENT` booking, requires its persisted deadline to be in the future, and snapshots the booking total/currency. A provider call is made only by the transaction that creates the attempt and occurs outside the DB transaction. The response is an initiation/pending result, never proof of payment. With no production adapter registered, initiation/webhook requests return `503`; `blue-bus.payments.default-provider` selects an installed adapter but stores no secret.

Database idempotency is `(user_id, idempotency_key)`. Same key/fingerprint returns the same attempt; different booking/provider fingerprint returns `409`; concurrent identical requests create one merchant/provider order. `GET` exposes safe status/disposition and monetary fields, not signatures, raw payload, provider secrets, or internal resolution reasons.

The webhook endpoint supplies the untouched request bytes and headers to the provider adapter. Only a verified, normalized event is persisted. `(provider, provider_event_id)` makes duplicate and concurrent delivery harmless. Pending/failure events never confirm a booking; verified success validates provider references and exact `NUMERIC(12,2)` amount/ISO currency.

Success processing locks `booking → payment_attempt → allocations`. On-time success for `PENDING_PAYMENT` atomically records `SUCCEEDED / APPLIED_TO_BOOKING` and confirms Booking. If expiry/cancellation won, the payment is `SUCCEEDED / REQUIRES_RESOLUTION`; Booking and released/cancelled allocations stay unchanged. Amount/currency/reference mismatch follows the same reconciliation path. Refund provider execution remains deferred.

Deferred: production payment provider selection/credentials, provider-specific webhook policy, actual refunds, outbox publishing/RabbitMQ, tickets.

## Customer seat holds — Phase 7.6

Temporary reservation of one or more seats on a trip segment. Creates an `ACTIVE` `SeatHold` and matching `HELD` `TripSeatAllocation` rows atomically. Does **not** create bookings or payments.

| Method | Path | Success |
|---|---|---|
| `POST` | `/api/v1/trips/{tripId}/holds` | `201 Created` |
| `GET` | `/api/v1/holds/{holdId}` | `200 OK` |
| `DELETE` | `/api/v1/holds/{holdId}` | `204 No Content` (cancel) |

`DELETE` cancels an `ACTIVE` hold (`ACTIVE` → `CANCELLED`, associated `HELD` → `CANCELLED`). Rows are not physically deleted. Repeated cancel of an already `CANCELLED` hold is safe/idempotent. `EXPIRED` / `CONSUMED` holds cannot become `CANCELLED` (`409`).

Create request body:

```json
{
  "originStopId": "...",
  "destinationStopId": "...",
  "seatInventoryIds": ["...", "..."],
  "idempotencyKey": "..."
}
```

Required: `originStopId`, `destinationStopId`, at least one unique `seatInventoryId`. Optional: `idempotencyKey`. Clients must **not** send `userId`, sequences, `status`, `expiresAt`, fare, booking, or payment fields. The server resolves stop IDs to trip sequences (same semantics as Phase 7.5), owns hold status/expiry, and sets `expiresAt` from configured `blue-bus.seat-holds.create.ttl-seconds`.

Create/get response (conceptual):

```json
{
  "holdId": "...",
  "tripId": "...",
  "originStopId": "...",
  "destinationStopId": "...",
  "originSequence": 1,
  "destinationSequence": 3,
  "status": "ACTIVE",
  "expiresAt": "...",
  "seatInventoryIds": ["...", "..."]
}
```

Lifecycle: `ACTIVE` → `CONSUMED` (internal/future booking), `EXPIRED` (reaper only), or `CANCELLED` (customer DELETE). `GET` is read-only and does **not** implicitly expire past-due `ACTIVE` holds; Phase 7.4 reaper remains the sole expiry mechanism.

Concurrency: PostgreSQL V6 GiST exclusion on active overlapping allocations remains authoritative. Overlap conflicts map to `409`. Multi-seat create is one transaction — if any seat conflicts, the entire hold rolls back (no partial `HELD` rows, no orphan hold).

Authentication: create/get/cancel remain publicly reachable (no JWT required). When `Authorization: Bearer` is present on create, the server persists `seat_holds.user_id` from the JWT (`sub`) — clients must **not** send `userId`. Anonymous creates still store `user_id = null` and may be used for temporary seat locking, but **cannot** be converted to a booking. Get/cancel still use the hold UUID as a capability token (not ownership). V7 uniquely enforces `(user_id, idempotency_key)` only when `user_id` is non-null; anonymous idempotency is accepted/stored but **not** DB-enforced.

Errors follow the existing `ApiError` envelope: `400` validation / bad segment / duplicate seats / blocked inventory / wrong-trip inventory; `404` unknown trip/stop/hold/inventory; `409` seat overlap or non-cancellable status.

## Customer journey seat availability (Phase 7.5)

Read-only public endpoint. Availability is **derived** from physical `TripSeatInventory` plus overlapping active segment allocations (`HELD`/`BOOKED`/`BLOCKED`). It is not a stored column. Past-due `HELD` rows still block until the hold expiry reaper transitions them to `EXPIRED`.

| Method | Path | Success |
|---|---|---|
| `GET` | `/api/v1/trips/{tripId}/seat-availability` | `200 OK` |

Query parameters (both required UUIDs):

- `originStopId` — `TripStop` id on the requested trip
- `destinationStopId` — `TripStop` id on the requested trip (must be after origin)

Callers must not send sequence numbers; the server resolves stop IDs to sequences.

Response (conceptual):

```json
{
  "tripId": "...",
  "originStopId": "...",
  "destinationStopId": "...",
  "originSequence": 1,
  "destinationSequence": 3,
  "seats": [
    {
      "inventoryId": "...",
      "seatNumber": "S1",
      "seatType": "SEATER",
      "deck": 1,
      "row": 1,
      "column": 1,
      "physicalStatus": "AVAILABLE",
      "availability": "AVAILABLE"
    }
  ]
}
```

`availability` is `AVAILABLE` or `UNAVAILABLE`. Validation: missing/malformed params → `400`; unknown trip or stop not on trip → `404`; origin not before destination → `400`. No mutation. No authentication in this phase.

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

Errors use the existing global handler: `400` validation/domain argument errors, `404` missing resource, `409` conflicts (including unique/data integrity). Endpoints require an ACTIVE platform `ADMIN` or `SUPER_ADMIN` (database `user_roles`, not JWT claims alone): anonymous `401`, authenticated non-admin `403`.

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

### Buses — `/api/v1/admin/buses` (Phase 6A.3)

Physical fleet vehicles. A bus references an existing operator, bus type, and reusable seat layout. Seats are not copied onto the bus. Lifecycle uses existing `BusStatus` (`ACTIVE`, `INACTIVE`, `MAINTENANCE`). Admin `activate` / `deactivate` map to domain `activate()` / `deactivate()`.

- `POST /api/v1/admin/buses` — body: `operatorId`, `busTypeId`, `seatLayoutId`, `registrationNumber`, optional `displayName` (creates as `ACTIVE`; layout must belong to the same operator)
- `GET /api/v1/admin/buses` — optional `operatorId`, `status`
- `GET /api/v1/admin/buses/{id}`
- `PUT /api/v1/admin/buses/{id}` — `displayName`, `busTypeId`, `seatLayoutId` (operator and registration number are immutable)
- `POST /api/v1/admin/buses/{id}/activate` — `ACTIVE`
- `POST /api/v1/admin/buses/{id}/deactivate` — `INACTIVE`

### Routes — `/api/v1/admin/routes` (Phase 6A.4)

Reusable route master data. Lifecycle uses existing `RouteStatus` (`ACTIVE`, `INACTIVE`). Stops are ordered by `sequenceNumber` with unique `(route_id, sequence_number)`. Points use existing `PointType` (`BOARDING`, `DROPPING`, `BOTH`) and belong to a route stop. Future trips will snapshot stops/points; this API mutates master data only.

**Route**

- `POST /api/v1/admin/routes` — body: `operatorId`, `code`, `name`, `sourceLocationId`, `destinationLocationId`, optional nested `stops[]` (each may include nested `points[]`)
- `GET /api/v1/admin/routes` — optional `operatorId`, `status`
- `GET /api/v1/admin/routes/{id}` — includes ordered stops and their points
- `PUT /api/v1/admin/routes/{id}` — metadata only: `name`, `sourceLocationId`, `destinationLocationId` (operator/code immutable; stops/points are not replaced)
- `POST /api/v1/admin/routes/{id}/activate`
- `POST /api/v1/admin/routes/{id}/deactivate`

**Route stops**

- `POST /api/v1/admin/routes/{routeId}/stops` — add a stop (`locationId`, `sequenceNumber`, `stopKind`, optional offsets/distance, optional nested `points[]`)
- `GET /api/v1/admin/routes/{routeId}/stops/{stopId}`
- `PUT /api/v1/admin/routes/{routeId}/stops/{stopId}` — update stop details via domain mutator (duplicate sequence rejected)

**Route points**

- `POST /api/v1/admin/routes/{routeId}/stops/{stopId}/points`
- `PUT /api/v1/admin/routes/{routeId}/stops/{stopId}/points/{pointId}`
- `POST /api/v1/admin/routes/{routeId}/stops/{stopId}/points/{pointId}/activate`
- `POST /api/v1/admin/routes/{routeId}/stops/{stopId}/points/{pointId}/deactivate`

Stop/point paths require the stop to belong to the given route (cross-route attachment → 404).

### Trips — `/api/v1/admin/trips` (Phase 6A.5)

One scheduled journey of one bus on one route. Create is atomic:

1. Persist `Trip`
2. Snapshot ordered `RouteStop` / `RoutePoint` master data into `TripStop` / `TripPoint`
3. Snapshot every physical `Seat` from the bus seat layout into `TripSeatInventory` (`AVAILABLE`, or `BLOCKED` when the master seat is not sellable)

After create, master-data changes to routes/layouts/seats do **not** rewrite existing trip snapshots. `PUT` does not rebuild snapshots.

Lifecycle uses existing `TripStatus` (`DRAFT`, `SCHEDULED`, `ON_SALE`, `CLOSED`, `DEPARTED`, `COMPLETED`, `CANCELLED`). Admin `activate` maps to `schedule()` (`DRAFT` → `SCHEDULED`). Admin `deactivate` maps to `cancel()` → `CANCELLED`.

- `POST /api/v1/admin/trips` — body: `busId`, `routeId`, `scheduledDepartureAt`, `scheduledArrivalAt`, `baseFare`, `bookingOpensAt`, `bookingClosesAt`, optional `timeZone` (default `Asia/Kolkata`; `serviceDate` derived). Bus and route must be active and share an operator (domain rule). Unique `(busId, serviceDate, scheduledDepartureAt)`.
- `GET /api/v1/admin/trips` — optional `busId`, `routeId`, `serviceDate`, `status`
- `GET /api/v1/admin/trips/{id}` — includes stop/point snapshots and seat inventory
- `PUT /api/v1/admin/trips/{id}` — commercial terms only: `baseFare`, `bookingOpensAt`, `bookingClosesAt` (allowed while `DRAFT`/`SCHEDULED`; bus/route/schedule/snapshots immutable)
- `POST /api/v1/admin/trips/{id}/activate`
- `POST /api/v1/admin/trips/{id}/deactivate`

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
