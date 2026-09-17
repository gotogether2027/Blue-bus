# API design

## API conventions

Use `/api/v1`, JSON, UTC ISO-8601 timestamps, UUID identifiers, cursor/page pagination, standard error envelopes, and an idempotency key for create/payment-sensitive requests. APIs expose DTOs, never persistence entities. The backend derives authorization scope from the JWT and rejects unauthorized IDs even if Angular guards permit navigation.

Implemented so far: `GET /api/v1/health`, customer registration + login + refresh/logout + `/auth/me`, admin master-data/trip APIs, public journey seat availability, public temporary seat holds (optional JWT ownership), authenticated hold-to-booking (`/api/v1/bookings`), Razorpay payment APIs, and **Phase 9.4A tickets** (`POST /api/v1/bookings/{bookingId}/tickets`, `GET /api/v1/tickets/{ticketId}`). Email verification, profile editing, PDF/QR, and notifications remain deferred.

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

**Phase 9.2A authorization:** protected application requests re-check `users.status` in the database. An otherwise valid access JWT for a `SUSPENDED` or `INACTIVE` user receives generic `401` (same envelope as a missing/invalid JWT). Existing `/api/v1/admin/**` application services additionally require `user_roles` to contain `ADMIN` or `SUPER_ADMIN`; authenticated callers without that privilege receive `403`. JWT `roles` claims are not used as the source of truth for platform admin. Customer booking/payment APIs remain owner-based (`JWT sub` == resource owner) and do **not** require a `CUSTOMER` role.

**Phase 9.2B operator portal:** `/api/v1/operator/{operatorId}/**` is a separate tenant namespace. The path `operatorId` is the only tenant selector. `OperatorAuthorizationService` loads `operator_users` for `(path operatorId, JWT sub)` on every request; JWT `roles` and any client-supplied `userId`/`operatorId` are not proof of membership. Platform `ADMIN`/`SUPER_ADMIN` are **not** auto-admitted. See the operator-portal section below.

Deferred: password reset, email/phone verification, profile editing, permission catalogs, operator payment/refund/settlement APIs, refresh-token cleanup/reaper (retain revoked/expired rows ≥ 30 days for reuse detection; indexes support future cleanup), access-token denylist, Redis, audit log.

## Customer bookings — Phase 9.1 foundation + unpaid expiry + V12 views/cancellation + Phase 9.6 confirmed cancellation

Authenticated hold-to-booking conversion, owner views, unpaid customer cancellation, and confirmed-booking cancellation with full refund orchestration. Tickets are issued via the dedicated ticket APIs (Phase 9.4A), not inside booking create/confirm. Unpaid `PENDING_PAYMENT` bookings expire automatically after the persisted payment deadline.

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

**Unpaid customer cancellation (V12):** `POST /api/v1/bookings/{bookingId}/cancel` for `PENDING_PAYMENT` locks the booking `FOR UPDATE` first, then matching allocations, marks booking/items `CANCELLED`, transitions `BOOKED` allocations to `CANCELLED`, writes an immutable `booking_cancellations` row (`UNPAID_CUSTOMER_CANCELLATION_V1`, refundable amount `0`), and writes `BOOKING_CANCELLED` to the outbox. Repeat cancel with an existing cancellation record is idempotent (`200`).

**Confirmed customer cancellation (Phase 9.6):** the same endpoint cancels `CONFIRMED` bookings before scheduled departure when a `SUCCEEDED` captured payment exists. Eligibility rejects `current time >= scheduledDepartureAt`, trip `DEPARTED`, and trip `COMPLETED`. Transaction (no provider HTTP): lock booking → verify ownership/status/departure → lock `BOOKED` allocations → lock ticket if present → lock succeeded payment → booking `CONFIRMED`→`REFUND_PENDING`, items `ACTIVE`→`CANCELLED`, allocations `BOOKED`→`CANCELLED`, ticket `ACTIVE`→`CANCELLED` when present → immutable cancellation row (`CONFIRMED_FULL_REFUND_CUSTOMER_CANCELLATION_V1`, refundable amount = captured payment amount) → `refunds.REQUESTED` (local idempotency `booking-cancel-{cancellationId}`, `attempt_count=0`, `next_retry_at` null) → `BOOKING_CANCELLED` outbox. After commit, an optional fast path and the scheduled refund-retry worker (`blue-bus.payments.refund-retry.*`) call Razorpay using **`refunds.id`** as `X-Razorpay-Idempotency-Key`. Provider/network failure leaves the booking `REFUND_PENDING` with seats/ticket already cancelled; the `REQUESTED`/`FAILED` refund row remains due after bounded backoff (`5s × 2^n`, cap 15 minutes, claim lease ~45s). Repeat cancel and `POST /api/v1/payments/{paymentAttemptId}/refunds` reuse that row. Crash after cancellation commit is recovered by the worker without a customer retry. Verified refund success (provider API or `refund.processed` webhook) transitions `REFUND_PENDING`→`REFUNDED`. Missing ticket does not block cancellation. Concurrent cancels serialize on the booking row; unique `booking_cancellations.booking_id` and `refunds (payment_attempt_id, idempotency_key)` keep one logical cancellation/refund.

**Errors:** invalid/expired/cancelled/consumed hold → `409`; anonymous hold → `409`; validation → `400`; unauthenticated → `401`; other customer’s hold/booking → `404`; unsupported cancellation state / after departure / no captured payment → `409`.

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

## Customer payment foundation — V11 + Phase 9.3 Razorpay

| Method | Path | Auth | Success |
|---|---|---|---|
| `POST` | `/api/v1/bookings/{bookingId}/payments` | Bearer JWT (owner) + `Idempotency-Key` | `201 Created` |
| `GET` | `/api/v1/payments/{paymentAttemptId}` | Bearer JWT (owner) | `200 OK` |
| `POST` | `/api/v1/payments/{paymentAttemptId}/checkout` | Bearer JWT (owner) | `200 OK` |
| `POST` | `/api/v1/payments/{paymentAttemptId}/refunds` | Bearer JWT (owner) + `Idempotency-Key` | `201 Created` |
| `POST` | `/api/v1/payments/webhooks/{provider}` | Provider signature through registered adapter; no JWT | `200 OK` |

Initiation has no amount/currency request fields. The server locks the owned `PENDING_PAYMENT` booking, requires its persisted deadline to be in the future, and snapshots the booking total/currency. A provider call is made only by the transaction that creates the attempt and occurs outside the DB transaction. The response is an initiation/pending result, never proof of payment.

`blue-bus.payments.default-provider` selects the installed adapter (`RAZORPAY` or a test adapter). Credentials are never stored in this property. With no matching adapter, initiation/webhook/checkout/refund requests return `503`.

Database idempotency is `(user_id, idempotency_key)`. Same key/fingerprint returns the same attempt; different booking/provider fingerprint returns `409`; concurrent identical requests create one merchant/provider order. `GET` exposes safe status/disposition and monetary fields, not signatures, raw payload, provider secrets, or internal resolution reasons. `checkoutReference` may contain the public Razorpay Key ID required by Checkout; the Key Secret and webhook secret are never returned.

### Razorpay adapter

Set `PAYMENT_PROVIDER=RAZORPAY` plus environment secrets. Test and Live are separated by which Key ID/secret/webhook secret/base URL are supplied (Razorpay test keys vs live keys). Missing required credentials fail application startup.

| Variable | Purpose | Secret? |
|---|---|---|
| `PAYMENT_PROVIDER` | `RAZORPAY` to enable the production adapter | no |
| `RAZORPAY_KEY_ID` | Public Key ID returned as `checkoutReference` | no (public) |
| `RAZORPAY_KEY_SECRET` | Orders API Basic auth and Checkout HMAC | yes |
| `RAZORPAY_WEBHOOK_SECRET` | Webhook HMAC of the raw body | yes |
| `RAZORPAY_BASE_URL` | Defaults to `https://api.razorpay.com` | no |

Order creation uses the persisted INR amount converted to paise with `BigDecimal` (`₹850.00` → `85000`). Receipt/notes carry only the payment-attempt id and merchant reference. The Razorpay order id is stored on `payment_attempts.provider_order_id`. Checkout verification HMAC is `order_id|payment_id` using the **stored** order id and `RAZORPAY_KEY_SECRET`. The browser-supplied order id is rejected unless it matches the stored value.

Webhook URL: `POST /api/v1/payments/webhooks/RAZORPAY`. Authentication is `X-Razorpay-Signature` over the **raw** request bytes (not re-serialized JSON). `X-Razorpay-Event-Id` is the inbox idempotency key. Normalized events: `payment.captured` / `order.paid` → `PAYMENT_SUCCEEDED` (only these confirm an eligible booking); `payment.failed` → `PAYMENT_FAILED`; `payment.authorized` → `PAYMENT_PENDING` (booking stays `PENDING_PAYMENT`; never final success by itself); `refund.processed` / `refund.failed` → refund outcomes. Unknown events are ignored after a verified inbox insert. Out-of-order pending/authorized events cannot downgrade `SUCCEEDED`.

**Razorpay acknowledgement window (~5s):** the webhook path is intentionally free of outbound Razorpay HTTP. Order is: verify raw-body signature → require event id → durable `(provider, provider_event_id)` inbox insert → bounded local payment/booking DB transitions (existing state machine + outbox rows) → `200` for accepted and duplicate deliveries. Orders/Refunds API calls are never made from this path; they remain on payment initiation and refund initiation only. Duplicate delivery still re-enters local process so a crash after inbox insert but before state-machine completion is recovered without calling Razorpay.

Checkout verification and webhooks both enter the existing verified-event processor. Frontend “payment success” is never sufficient by itself.

Refunds use the captured amount from the database (client `amount` is ignored), Razorpay `X-Razorpay-Idempotency-Key` = our refund id, and the existing `refunds` row. Duplicate refund requests and duplicate refund webhooks do not create a second provider refund. **Phase 9.6:** direct `POST /api/v1/payments/{paymentAttemptId}/refunds` is rejected unless the booking is already `REFUND_PENDING` or `REFUNDED` (refund initiation belongs to confirmed booking cancellation). Orphan refunds that would leave booking `CONFIRMED` with `BOOKED` seats / `ACTIVE` ticket are not allowed.

### INITIATING recovery

If Razorpay accepts an order and BLUE BUS crashes before persisting `provider_order_id`, the attempt remains `INITIATING`. Retrying the same customer `Idempotency-Key` calls Orders again with `X-Razorpay-Idempotency-Key` = `payment_attempt_id`, which returns the existing Razorpay order instead of creating another charge. Remaining operational requirement: if the customer never retries, an `INITIATING` row with a null provider order id should be reconciled from the Razorpay dashboard using the receipt/merchant reference; there is no automatic poller in this phase.

The webhook endpoint supplies the untouched request bytes and headers to the provider adapter. Only a verified, normalized event is persisted. `(provider, provider_event_id)` makes duplicate and concurrent delivery harmless. Pending/failure events never confirm a booking; verified success validates provider references and exact `NUMERIC(12,2)` amount/ISO currency.

Success processing locks `booking → payment_attempt → allocations`. On-time success for `PENDING_PAYMENT` atomically records `SUCCEEDED / APPLIED_TO_BOOKING` and confirms Booking. If expiry/cancellation won, the payment is `SUCCEEDED / REQUIRES_RESOLUTION`; Booking and released/cancelled allocations stay unchanged. Amount/currency/reference mismatch follows the same reconciliation path.

Deferred: additional providers, RabbitMQ publishing, PDF/QR, notifications.

**Phase 9.4B:** When a booking transitions `PENDING_PAYMENT → CONFIRMED`, the confirmation transaction writes `BOOKING_CONFIRMED` to `outbox_events`. A scheduled local processor (`blue-bus.outbox.processor.*`, default every 5s, batch 50, `FOR UPDATE SKIP LOCKED`) issues the ticket idempotently and writes `TICKET_ISSUED` in the same transaction before marking the confirmation event published. Manual ticket POST remains safe.

## Customer tickets — Phase 9.4A foundation

Booking = commercial transaction. Ticket = immutable customer-facing travel document issued from a `CONFIRMED` booking. One ticket per booking (`UNIQUE(tickets.booking_id)`). Ticket numbers are customer-facing references: `BB` + 8 unambiguous uppercase alphanumerics (alphabet omits `I`/`O`/`0`/`1`), generated with `SecureRandom`, uniqueness enforced by the database. Snapshot fields (operator display name, OD stop names, schedule, passenger/seat/fare) are copied at issuance and do not silently follow later operational edits. PDF/QR and notifications are deferred.

| Method | Path | Auth | Success |
|---|---|---|---|
| `POST` | `/api/v1/bookings/{bookingId}/tickets` | Bearer JWT (booking owner) | `201 Created` |
| `GET` | `/api/v1/tickets/{ticketId}` | Bearer JWT (ticket owner) | `200 OK` |

**Issuance rules:** only `CONFIRMED` bookings; idempotent (repeat calls return the same ticket); concurrent races resolve via `uq_tickets_booking` and return the winner. Non-owners and unknown IDs → `404`. Unauthenticated → `401`. Amounts come from persisted booking/item values — never recalculated and never accepted from the client. **Phase 9.4B** also issues tickets automatically from `BOOKING_CONFIRMED` outbox events; confirmation never depends on ticket generation completing inside the payment webhook.

Example response:

```json
{
  "ticketId": "...",
  "ticketNumber": "BB7K4M92",
  "status": "ACTIVE",
  "issuedAt": "2026-09-15T12:00:00Z",
  "bookingReference": "BB...",
  "bookingId": "...",
  "operator": { "name": "Blue Travels" },
  "journey": {
    "origin": "Hyderabad, Telangana",
    "destination": "Vijayawada, Andhra Pradesh",
    "departure": "2026-12-01T10:00:00Z",
    "arrival": "2026-12-01T13:00:00Z"
  },
  "passengers": [
    { "name": "Rider 1", "age": 25, "gender": "F", "seat": "S1", "fareAmount": 900.00, "currency": "INR" }
  ],
  "amount": 900.00,
  "currency": "INR"
}
```

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

Physical fleet vehicles. A bus references an existing operator, bus type, and reusable seat layout. Seats are not copied onto the bus. Lifecycle uses existing `BusStatus` (`ACTIVE`, `INACTIVE`, `MAINTENANCE`). Admin `activate` / `deactivate` map to domain `activate()` / `deactivate()`. Registration numbers are globally unique **case-insensitively** (V15 `ux_buses_registration_number_lower`).

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

## Operator portal — Phase 9.2B foundation

Operator APIs live under `/api/v1/operator/{operatorId}/...`. HTTP security remains `authenticated()`; application services call `OperatorAuthorizationService.requireMember(pathOperatorId, allowedOperatorRoles...)`. Platform `AuthorizationService` is unchanged and still owns ACTIVE-user and `/api/v1/admin/**` checks.

Membership is one row in `operator_users` per `(operator_id, user_id)` with a single operator-scoped role (`OPERATOR_ADMIN` or `OPERATOR_STAFF`) and `ACTIVE`/`INACTIVE` status. JWT `roles` are ignored for operator authorization. Membership and operator status are re-read from PostgreSQL on every request (no Redis, no active-operator session, no membership claims in the access JWT).

| Method | Path | Allowed membership | Success |
|---|---|---|---|
| `GET` | `/api/v1/auth/operator-memberships` | any ACTIVE user | `200` list of ACTIVE memberships on ACTIVE operators (`operatorId`, `operatorDisplayName`, `role`). Empty list when none. Query `userId`/`operatorId` are ignored. |
| `GET` | `/api/v1/operator/{operatorId}` | `OPERATOR_ADMIN`, `OPERATOR_STAFF` | `200` safe operator profile |
| `PATCH` | `/api/v1/operator/{operatorId}` | `OPERATOR_ADMIN` | `200` support email/phone only. Authorization and update share one transaction. Unknown body fields (including `id`, `operatorId`, `userId`, `status`, legal/display name, role) → `400`. |
| `GET` | `/api/v1/operator/{operatorId}/buses` | `OPERATOR_ADMIN`, `OPERATOR_STAFF` | `200` buses where `buses.operator_id = path operatorId` |
| `GET` | `/api/v1/operator/{operatorId}/buses/{busId}` | `OPERATOR_ADMIN`, `OPERATOR_STAFF` | `200` loaded by `(busId, operatorId)` |
| `POST` | `/api/v1/operator/{operatorId}/buses` | `OPERATOR_ADMIN` | `201` create bus as `ACTIVE`. Body: `busTypeId`, `seatLayoutId`, `registrationNumber`, optional `displayName`. Path `operatorId` only. Bus type must be active; seat layout must be same-operator `PUBLISHED`. Registration globally unique case-insensitively (`409`). Cross-operator layout → `404`. |
| `PATCH` | `/api/v1/operator/{operatorId}/buses/{busId}` | `OPERATOR_ADMIN` | `200` allow-list: `displayName`, `busTypeId`, `seatLayoutId` (presence-aware). Empty/unknown fields → `400`. Layout change blocked if **any** trip exists for the bus → `409`. Registration/`operatorId`/`status` rejected. |
| `POST` | `/api/v1/operator/{operatorId}/buses/{busId}/activate` | `OPERATOR_ADMIN` | `200` → `ACTIVE` (idempotent) |
| `POST` | `/api/v1/operator/{operatorId}/buses/{busId}/deactivate` | `OPERATOR_ADMIN` | `200` → `INACTIVE` (idempotent). Does not cancel/alter trips. |
| `POST` | `/api/v1/operator/{operatorId}/buses/{busId}/maintenance` | `OPERATOR_ADMIN` | `200` → `MAINTENANCE` (idempotent). Does not cancel/alter trips. |
| `GET` | `/api/v1/operator/{operatorId}/trips` | `OPERATOR_ADMIN`, `OPERATOR_STAFF` | `200` trips where `trips.operator_id = path operatorId`. Optional `serviceDate`/`status` filters. |
| `GET` | `/api/v1/operator/{operatorId}/trips/{tripId}` | `OPERATOR_ADMIN`, `OPERATOR_STAFF` | `200` loaded by `(tripId, operatorId)` |
| `POST` | `/api/v1/operator/{operatorId}/trips` | `OPERATOR_ADMIN` | `201` create `DRAFT` trip + route/seat snapshots. Body: `busId`, `routeId`, schedule instants, `baseFare`, booking window, optional `timeZone`. Path `operatorId` only. Layout from bus current layout (not client-selected). Bus+route must be same-operator `ACTIVE`. Exact `(bus, serviceDate, departure)` unique (`409`). Same-bus interval overlap among non-`CANCELLED` trips (`409`). Route needs ≥2 stops. |
| `PATCH` | `/api/v1/operator/{operatorId}/trips/{tripId}` | `OPERATOR_ADMIN` | `200` allow-list commercial terms: `baseFare`, `bookingOpensAt`, `bookingClosesAt` (presence-aware). Allowed while `DRAFT`/`SCHEDULED` only. Empty/unknown/immutable fields → `400`. |
| `POST` | `/api/v1/operator/{operatorId}/trips/{tripId}/schedule` | `OPERATOR_ADMIN` | `200` `DRAFT`→`SCHEDULED` (idempotent if already `SCHEDULED`) |
| `POST` | `/api/v1/operator/{operatorId}/trips/{tripId}/cancel` | `OPERATOR_ADMIN` | `200` → `CANCELLED` (idempotent). Status-only: does not cancel bookings, refunds, tickets, holds, inventory, or write outbox. Refused from `DEPARTED`/`COMPLETED`. |
| `GET` | `/api/v1/operator/{operatorId}/trips/{tripId}/inventory` | `OPERATOR_ADMIN`, `OPERATOR_STAFF` | `200` physical trip seat inventory (no customer PII) |
| `GET` | `/api/v1/operator/{operatorId}/trips/{tripId}/inventory/{inventoryId}` | `OPERATOR_ADMIN`, `OPERATOR_STAFF` | `200` one inventory row scoped by `(inventoryId, tripId, operatorId)` |
| `POST` | `/api/v1/operator/{operatorId}/trips/{tripId}/inventory/{inventoryId}/block` | `OPERATOR_ADMIN` | `200` physical `AVAILABLE`→`BLOCKED` (global for all segments). Body: required non-blank `reason`. Already `BLOCKED` is idempotent and preserves existing reason. Does not cancel/release holds or bookings. |
| `POST` | `/api/v1/operator/{operatorId}/trips/{tripId}/inventory/{inventoryId}/unblock` | `OPERATOR_ADMIN` | `200` physical `BLOCKED`→`AVAILABLE` (clears `blockReason`). Already `AVAILABLE` is idempotent. |
| `GET` | `/api/v1/operator/{operatorId}/seat-layouts` | `OPERATOR_ADMIN`, `OPERATOR_STAFF` | `200` layouts where `seat_layouts.operator_id = path operatorId`. Optional `status` filter. |
| `GET` | `/api/v1/operator/{operatorId}/seat-layouts/{layoutId}` | `OPERATOR_ADMIN`, `OPERATOR_STAFF` | `200` one layout + nested seats, scoped by `(layoutId, operatorId)` |
| `POST` | `/api/v1/operator/{operatorId}/seat-layouts` | `OPERATOR_ADMIN` | `201` create `DRAFT` layout + seats (≥1 seat required). Body: `name`, `version`, dimensions, `seats[]`. Path `operatorId` only. `(operatorId, name, version)` unique case-insensitively (`409`). |
| `PATCH` | `/api/v1/operator/{operatorId}/seat-layouts/{layoutId}` | `OPERATOR_ADMIN` | `200` DRAFT-only metadata: `name`, `deckCount`, `rowCount`, `columnCount` (presence-aware). Seats/version/status immutable. Shrinking dimensions below existing seats → `400`. |
| `POST` | `/api/v1/operator/{operatorId}/seat-layouts/{layoutId}/activate` | `OPERATOR_ADMIN` | `200` publish (`DRAFT`/`PUBLISHED`→`PUBLISHED`, idempotent). Requires ≥1 seat. `ARCHIVED` → `400`. |
| `POST` | `/api/v1/operator/{operatorId}/seat-layouts/{layoutId}/deactivate` | `OPERATOR_ADMIN` | `200` archive (idempotent). Does not detach buses or rewrite trip snapshots/inventory. |
| `GET` | `/api/v1/operator/{operatorId}/trips/{tripId}/bookings` | `OPERATOR_ADMIN`, `OPERATOR_STAFF` | `200` trip-scoped manifest |
| `GET` | `/api/v1/operator/{operatorId}/trips/{tripId}/bookings/{bookingId}` | `OPERATOR_ADMIN`, `OPERATOR_STAFF` | `200` one booking |
| `GET` | `/api/v1/operator/{operatorId}/members` | `OPERATOR_ADMIN`, `OPERATOR_STAFF` | `200` ordered membership list (`userId`, `email`, `firstName`, `lastName`, `role`, `status`). No password/refresh/secret fields. |
| `POST` | `/api/v1/operator/{operatorId}/members` | `OPERATOR_ADMIN` | `201` create membership for an **existing** platform user (`userId`, `role` ∈ `OPERATOR_ADMIN`/`OPERATOR_STAFF`). Path `operatorId` only; body `operatorId` rejected. Initial status `ACTIVE`. Duplicate `(operatorId,userId)` → `409`. Missing user → `404`. |
| `PATCH` | `/api/v1/operator/{operatorId}/members/{userId}` | `OPERATOR_ADMIN` | `200` allow-list update of `role` and/or `status` (`ACTIVE`/`INACTIVE`). Empty body / unknown fields → `400`. Reactivates existing `INACTIVE` row (no second insert). |
| `POST` | `/api/v1/operator/{operatorId}/members/{userId}/deactivate` | `OPERATOR_ADMIN` | `200` soft-deactivate (`ACTIVE`→`INACTIVE`). Already `INACTIVE` is idempotent `200`. |
| `GET` | `/api/v1/operator/{operatorId}/routes` | `OPERATOR_ADMIN`, `OPERATOR_STAFF` | `200` routes where `routes.operator_id = path operatorId`. Optional `status` filter. |
| `GET` | `/api/v1/operator/{operatorId}/routes/{routeId}` | `OPERATOR_ADMIN`, `OPERATOR_STAFF` | `200` route plus ordered stops/points, loaded by `(routeId, operatorId)` |
| `POST` | `/api/v1/operator/{operatorId}/routes` | `OPERATOR_ADMIN` | `201` create route as `ACTIVE`. Body: `code`, `name`, `sourceLocationId`, `destinationLocationId`, optional nested `stops`/`points`. Path `operatorId` only. Zero stops allowed. Code unique per operator case-insensitively (`409`). Source ≠ destination; locations must exist. |
| `PATCH` | `/api/v1/operator/{operatorId}/routes/{routeId}` | `OPERATOR_ADMIN` | `200` allow-list: `name`, `sourceLocationId`, `destinationLocationId` (presence-aware). Empty/unknown/`code`/`status`/`operatorId` → `400`. Name-only is safe with trips; source/destination change blocked if **any** trip exists → `409`. |
| `POST` | `/api/v1/operator/{operatorId}/routes/{routeId}/activate` | `OPERATOR_ADMIN` | `200` → `ACTIVE` (idempotent) |
| `POST` | `/api/v1/operator/{operatorId}/routes/{routeId}/deactivate` | `OPERATOR_ADMIN` | `200` → `INACTIVE` (idempotent). Does not cancel/alter trips or rewrite snapshots. |
| `POST` | `/api/v1/operator/{operatorId}/routes/{routeId}/stops` | `OPERATOR_ADMIN` | `201` add stop (+ optional points). Structural; blocked if any trip exists → `409`. Sequence unique per route. |
| `PATCH` | `/api/v1/operator/{operatorId}/routes/{routeId}/stops/{stopId}` | `OPERATOR_ADMIN` | `200` update stop fields. Structural; blocked if any trip exists → `409`. Stop must belong to path route/operator (`404` otherwise). |
| `POST` | `/api/v1/operator/{operatorId}/routes/{routeId}/stops/{stopId}/points` | `OPERATOR_ADMIN` | `201` add point. Structural detail mutation; blocked if any trip exists → `409`. |
| `PATCH` | `/api/v1/operator/{operatorId}/routes/{routeId}/stops/{stopId}/points/{pointId}` | `OPERATOR_ADMIN` | `200` update point details. Structural; blocked if any trip exists → `409`. |
| `POST` | `/api/v1/operator/{operatorId}/routes/{routeId}/points/{pointId}/activate` | `OPERATOR_ADMIN` | `200` → active (idempotent). Safe even when trips exist; does not rewrite `trip_points`. Point scoped by `(routeId, operatorId)`. |
| `POST` | `/api/v1/operator/{operatorId}/routes/{routeId}/points/{pointId}/deactivate` | `OPERATOR_ADMIN` | `200` → inactive (idempotent). Safe even when trips exist; does not rewrite `trip_points`. |

**Membership administration (Phase 9.5E):** Mutations run in one transaction. Sequence: early `requireMember(OPERATOR_ADMIN)` → `operators` row `FOR UPDATE` → **re-read/revalidate** the caller's ACTIVE `OPERATOR_ADMIN` membership and operator `ACTIVE` status from the database (refresh; not the pre-lock persistence snapshot) → target `operator_users` `PESSIMISTIC_WRITE` → count ACTIVE `OPERATOR_ADMIN` rows and apply the change. Concurrent demotion/deactivation of the caller between the early check and the lock cannot complete a privileged mutation on stale authorization (`403` if demoted to staff, `404` if membership inactive/missing). Any demote/deactivate that would leave zero ACTIVE `OPERATOR_ADMIN` memberships returns `409` (including self-demotion/self-deactivate of the last admin). Concurrent membership creates for the same pair serialize on the operator row; the composite PK remains authoritative (`409` on conflict). Platform `ADMIN`/`SUPER_ADMIN` are still not auto-admitted. Inactive/suspended **actor** platform users remain `401`. Target platform users may be inactive when assigned a membership row; they still cannot authorize into the operator namespace until the platform user is `ACTIVE` again. No membership outbox event in this phase.

**Bus administration (Phase 9.5A):** Dedicated `OperatorBusAdminService` (does not call `BusAdminService`). Existing-bus mutations: early `requireMember(OPERATOR_ADMIN)` → load/lock `buses` by `(id, operatorId)` `FOR UPDATE` → revalidate ACTIVE `OPERATOR_ADMIN` + operator `ACTIVE` → mutate. Create uses app ignore-case registration check plus DB-authoritative unique index `ux_buses_registration_number_lower` on `lower(registration_number)` (V15; case-sensitive column unique dropped). Seat-layout reassignment requires same-operator `PUBLISHED` layout and **zero** trip rows for the bus (`409` otherwise); composite FK `fk_trips_bus_selected_layout` remains final integrity. Deactivate/maintenance do not cancel trips; new trips still require `bus.isActive()`. No bus outbox events.

**Route administration (Phase 9.5B):** Dedicated `OperatorRouteAdminService` (does not call `RouteAdminService`). Existing-route mutations: early `requireMember(OPERATOR_ADMIN)` → load/lock `routes` by `(id, operatorId)` `FOR UPDATE` → revalidate ACTIVE `OPERATOR_ADMIN` + operator `ACTIVE` → if structural, refuse when **any** trip references the route (`existsByRoute_Id`) → mutate. Create uses app ignore-case `(operatorId, code)` check plus DB unique index `ux_routes_operator_code_lower` on `(operator_id, lower(code))` (V16; case-sensitive `uq_routes_operator_code` dropped). Only violations of that index map to `"Route code already exists for this operator."` (`409`). Name-only PATCH and route/point activate/deactivate remain safe after trips; source/destination, stop mutations, and point detail mutations are structural (`409`). Master route edits never rewrite `trip_stops` / `trip_points`. No route DELETE, no status PATCH, no `ROUTE_*` outbox events.

**Trip administration (Phase 9.5C):** Dedicated `OperatorTripAdminService` (does not call `TripAdminService`). Create: early `requireMember(OPERATOR_ADMIN)` → lock `buses` then `routes` by `(id, operatorId)` `FOR UPDATE` → revalidate ACTIVE admin + operator → require bus/route ACTIVE → create `DRAFT` with layout from `bus.getSeatLayout()` → exact uniqueness `(bus, serviceDate, departure)` plus same-bus interval overlap check among non-`CANCELLED` trips under the bus lock (`newDeparture < existingArrival AND newArrival > existingDeparture`; adjacent endpoints allowed) → snapshot stops/points/inventory. Existing-trip mutations: trip row `FOR UPDATE` by `(id, operatorId)` → revalidate → commercial PATCH / `schedule()` / `cancel()`. No `ON_SALE`/`CLOSED`/`DEPARTED`/`COMPLETED` transitions. Cancel is status-only (no booking/refund/ticket/outbox side effects). No trip events. No migration for overlap (application check under bus lock).

**Seat layout administration (Phase 9.5F):** Dedicated `OperatorSeatLayoutAdminService` (does not call `SeatLayoutAdminService`). Create: early `requireMember(OPERATOR_ADMIN)` → lock `operators` `FOR UPDATE` → revalidate ACTIVE admin + operator → app ignore-case `(name, version)` check → insert `DRAFT` + seats (≥1) → DB unique index `ux_seat_layouts_operator_name_version_lower` (V17) is authoritative (`409` only for that index). Existing-layout mutations: early authorize → layout `(id, operatorId)` `FOR UPDATE` → revalidate → `updateDraftMetadata` / `publish()` / `archive()`. PATCH allowed only while `DRAFT`. Archive does not detach buses or alter `trip_seat_inventory`. No layout outbox events.

**Inventory administration (Phase 9.5D):** Dedicated `OperatorTripInventoryAdminService` (does not call platform-admin services). Reads: ACTIVE `OPERATOR_ADMIN`/`OPERATOR_STAFF`. Writes: ACTIVE `OPERATOR_ADMIN` only. Sequence for block/unblock: early authorize → optional TOCTOU barrier → trip `(id, operatorId)` `FOR UPDATE` → revalidate ACTIVE admin + operator → require trip status ∈ `DRAFT`/`SCHEDULED`/`ON_SALE` → inventory `(id, trip_id)` `FOR UPDATE` → mutate physical status. Allocations are **not** locked. Physical `BLOCKED` is **global** across all route segments; it prevents **new** holds via the existing physical `AVAILABLE` check. It does **not** cancel, release, or expire existing `HELD`/`BOOKED` allocations; held seats may still convert to bookings. Segment-aware GiST exclusion on `trip_seat_allocations` is unchanged. Segment-specific operator blocking and HTTP exposure of allocation state `BLOCKED` are out of scope. No inventory outbox events. No migration (schema already supports `AVAILABLE`/`BLOCKED` + `block_reason`).

**Resource ownership:** buses, trips, routes, seat layouts, and trip inventory are loaded with path `operatorId` plus nested ownership (`operatorId → layoutId`, `operatorId → tripId → inventoryId`). A resource that exists for another operator returns generic `404`. Nested stop/point paths are scoped `operatorId → routeId → stopId → pointId` (point activate/deactivate use `operatorId → routeId → pointId`). Membership rows are loaded by `(operatorId, userId)` under the path operator; a user who is only a member of another operator returns generic `404` (no existence leak).

**Booking dual-check:** `bookings.operator_id` has no FK to `operators`. After authorizing membership and loading the trip by `(tripId, operatorId)`, a booking is visible only when `booking.operator_id == path operatorId` **and** `booking.trip_id` is that trip (whose `operator_id` also equals the path). Disagreement → `404` and no payload. Passengers/items are never authorized apart from the parent booking.

**Operator booking DTO:** operational fields only (reference, status, trip identity, origin/destination, seat number, passenger name/age/gender, booking total, currency). Excludes customer email/phone, `userId`, hold id, idempotency key, payment expiry, payment-attempt/provider/refund/ledger fields, checkout URLs. Customer `GET /api/v1/bookings/{bookingId}` and `GET /api/v1/payments/{paymentAttemptId}` ownership `404` behavior is unchanged.

**Error semantics (operator namespace):**

| Status | When |
|---|---|
| `401` | missing/invalid/expired JWT, or `SUSPENDED`/`INACTIVE` user |
| `404` | unknown operator, no membership, inactive membership, cross-operator resource, booking not on the requested trip/operator, target platform user not found on create, cross-operator seat layout, missing bus/type/route/stop/point/location, IDOR |
| `403` | ACTIVE member with insufficient role (for example STAFF on PATCH/members/bus/route/trip mutate), stale demoted caller after post-lock revalidation, or ACTIVE member whose operator is not `ACTIVE` |
| `400` | validation / unknown PATCH fields / malformed input / empty membership, bus, route, or trip PATCH / invalid operator membership role / source == destination / invalid trip schedule or commercial terms / invalid lifecycle transition |
| `409` | duplicate membership create, last ACTIVE `OPERATOR_ADMIN` protection, bus registration conflict (case-insensitive), seat layout name+version conflict (case-insensitive per operator), seat-layout change while any trip exists, inactive bus type / non-PUBLISHED layout assignment, route code conflict (case-insensitive per operator), stop sequence conflict, structural route/stop/point mutation while any trip exists, trip exact departure duplicate, same-bus overlapping trip |

Do not leak membership existence, another operator's existence, or another user's ownership. A customer without membership receives the same generic `404` as an unknown operator UUID.

**Role matrix:**

| Caller | `/api/v1/admin/**` | Own ACTIVE operator | Other operator | PATCH support contact | Members list | Members mutate | Bus mutate | Route mutate | Trip mutate |
|---|---|---|---|---|---|---|---|---|---|
| `ADMIN` / `SUPER_ADMIN` | allowed (9.2A) | not auto-authorized (`404` unless they also have an ACTIVE membership) | `404` | n/a | n/a | n/a | n/a | n/a | n/a |
| `CUSTOMER` (no membership) | `403` | `404` | `404` | `404` | `404` | `404` | `404` | `404` | `404` |
| `OPERATOR_ADMIN` | `403` | allowed | `404` | allowed | allowed | allowed | allowed | allowed | allowed |
| `OPERATOR_STAFF` | `403` | reads allowed | `404` | `403` | allowed | `403` | `403` | `403` | `403` |

A user may hold ACTIVE memberships in multiple operators; each path `operatorId` is authorized independently.

## Endpoint groups (examples only)

| Group | Example responsibilities | Access |
|---|---|---|
| `/api/v1/auth` | register, login, refresh, logout, `GET /operator-memberships` | public/authenticated as applicable |
| `/api/v1/users` | current profile, customer profile | authenticated owner/admin |
| `/api/v1/admin/users`, `/roles`, `/permissions` | user/role administration | authorized admin |
| `/api/v1/operator/{operatorId}` | operator profile, fleet/trip/route reads, bus/route/trip writes, trip booking manifest, membership administration | ACTIVE `operator_users` membership; path `operatorId` is the tenant |
| `/api/v1/bus-types`, `/buses`, `/seat-layouts` | fleet master data | scoped operator/admin |
| `/api/v1/locations`, `/routes`, `/trips` | search network; manage routes/schedules | public read / scoped write |
| `/api/v1/search` | origin, destination, service date, passenger count search | public |
| `/api/v1/trips/{tripId}/inventory` | seat map and availability for requested origin/destination and boarding/drop points | public/signed-in per policy |
| `/api/v1/bookings` | hold seats, create booking, retrieve/cancel own booking | customer/scoped staff/admin |
| `/api/v1/tickets` | issue ticket for confirmed booking; retrieve own ticket | booking/ticket owner |
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
4. Payment confirmation requires a verified Razorpay Checkout HMAC and/or a verified webhook. Both use the stored provider order id and the existing payment state machine. An unsigned browser success callback is never enough.
5. `GET /bookings/{id}` returns current state; the UI may poll briefly or receive a later notification.

If a provider reports success after the hold has expired or the trip is no longer saleable, the API must show a non-confirmed resolution state. It must not claim a released seat; the payment/refund workflow resolves the money separately.

Never offer a general client API that changes inventory state. Operator/admin APIs must validate trip ownership and state transitions.

## Error and security expectations

- `401` means absent/invalid identity; `403` means authenticated but forbidden; do not leak whether another customer's booking exists.
- Return `409 Conflict` for seat state races and expired holds; clients must refresh availability.
- Rate-limit login, search, hold, coupon validation, and webhook endpoints appropriately.
- Payment webhooks verify signature, event timestamp, and provider event ID before enqueueing/processing.
