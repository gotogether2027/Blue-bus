# Architecture

## Decision summary

BLUE BUS should begin as a **modular monolith**: one deployable Spring Boot backend and one PostgreSQL database, internally divided into independently owned business modules. Angular is a separate client application with customer, operator, and admin experiences. This keeps early delivery and transactions simple while preventing the codebase from becoming one undifferentiated application.

The initial service supports every Indian state through generic geographic data; Telangana and Andhra Pradesh are seeded/operational scope, not hard-coded assumptions.

## What this means

Each module owns its use cases, domain objects, repository access, API controllers, and permission checks. A module may read another module through a defined application interface. It must not directly alter the other module's tables or internal models. In the shared monolith, PostgreSQL foreign keys are useful for integrity; where future extraction is realistic, document the ID dependency and avoid join-heavy cross-module behavior.

```text
Angular clients
  customer | operator portal | admin portal
                 |
            REST / JWT
                 |
Spring Boot modular monolith
  Identity & Access | Operator | Fleet | Network & Scheduling
  Inventory & Booking | Payments | Settlement | Engagement
                 |
 PostgreSQL     Redis (expiring holds)     RabbitMQ (post-commit events)
```

## Module boundaries

| Module | Owns | Key dependencies |
|---|---|---|
| Identity & Access | users, roles, permissions, credentials, sessions/audit | none |
| Operator | operator organizations and memberships | Identity |
| Fleet | bus types, buses, reusable seat layouts and seats | Operator |
| Network & Scheduling | locations, routes, stops/points, trips | Operator, Fleet |
| Inventory & Booking | per-trip inventory, holds, bookings, passengers, cancellations | Scheduling, Identity |
| Payments | payment attempts, provider references, webhook records, refunds | Booking |
| Settlement | operator payout statements and line items | Operator, Booking, Payments |
| Engagement | notifications, delivery records, reviews, coupons | Booking, Identity, Operator |
| Reporting & Configuration | read models, configuration and admin reporting | events from all modules |

`Locations` and `Coupons` are deliberately separate concepts: locations are master geography/network data; coupons are commercial rules. `Trip inventory` is placed with Booking because its primary purpose is protecting and selling seats, even though trips belong to Scheduling.

## Important decisions

### Modular monolith first

**What:** one backend deployment and transactional database, with package/module boundaries and module-owned tables.

**Why:** booking and payment state need strong consistency early; operational complexity, distributed tracing, service deployment, and cross-service failure handling would slow an MVP.

**Alternative:** microservices from day one. This isolates deployments but makes seat reservation, payment confirmation, data consistency, and developer operations materially harder.

**Trade-off:** a shared database requires discipline. Module interfaces, schema ownership, event contracts, and no cross-module writes make later extraction practical.

### PostgreSQL as the system of record; Redis only for temporary acceleration

**What:** authoritative seat state, booking state, payments, and money records live in PostgreSQL. Redis may hold a short-lived hold lookup/expiry signal but never be the sole record of a hold.

**Why:** Redis expiration alone cannot provide auditable, transactional booking guarantees.

**Alternative:** Redis-only holds. It is fast but risks lost state and difficult recovery.

### Database-backed inventory locking

**What:** one `trip_seat_inventory` row exists per `(trip_id, layout_seat_id)` as the physical seat snapshot. It does **not** transition to HELD or BOOKED. Future occupancy is inserted as a `trip_seat_allocation` for a half-open stop-sequence range. PostgreSQL (GiST exclusion on overlapping active ranges) is the final correctness boundary across application instances. Redis locks are optional optimization, not a substitute.

**Why:** a `BOOKED` flag on the physical inventory row cannot express non-overlapping segment resale of the same seat.

**Trade-off:** hot trips create contention on overlapping range inserts; keep transactions short, use targeted indexes, and retry a narrowly scoped conflict.

### Segment-aware inventory from the first sellable journey

**What:** `trip_seat_inventory` represents the physical seat on a trip; a separate allocation/reservation row (booking phase) records each held or booked origin/destination stop-sequence range for that seat as `int4range(origin_sequence, destination_sequence, '[)')`. Example: Hyderabad(1) → Vijayawada(3) is `[1,3)`; Vijayawada(3) → Guntur(4) is `[3,4)`; those ranges do not overlap. Hyderabad→Vijayawada `[1,3)` and Suryapet→Guntur `[2,4)` do overlap and must be rejected.

**Implementation status:** trip stops, trip points, route points, physical inventory, segment allocations, seat holds, bookings, unpaid-booking expiry, customer booking views, unpaid cancellation, and origin/destination search are in the schema. V11 adds provider-neutral payment attempts, a verified-provider-event inbox, refund persistence foundation, and a transactional outbox. **Phase 9.3** adds `RazorpayPaymentProvider` behind `PaymentProvider` (Orders, Checkout HMAC, raw-body webhooks, refunds). **Phase 9.4A** adds immutable `tickets` / `ticket_passengers` snapshots. **Phase 9.4B** processes unpublished `BOOKING_CONFIRMED` outbox rows locally to issue tickets and append `TICKET_ISSUED`. Confirmed-booking cancellation policy, RabbitMQ publishing, PDF/QR, and notifications remain deferred.

### Ticket vs booking

**What:** a **Booking** is the commercial transaction (`PENDING_PAYMENT` → `CONFIRMED` / `EXPIRED` / `CANCELLED`). A **Ticket** is a separate customer-facing travel document issued only from a `CONFIRMED` booking. Ticket rows snapshot journey/passenger/seat/fare/operator display data at issuance so later operational edits do not silently rewrite the customer document.

**Phase 9.4A:** `TicketApplicationService.issueForBooking` is idempotent (`UNIQUE(tickets.booking_id)`). Customer `POST /api/v1/bookings/{bookingId}/tickets` and `GET /api/v1/tickets/{ticketId}` use JWT subject ownership (cross-customer `404`). **Phase 9.4B:** booking confirmation persists `BOOKING_CONFIRMED` in the same TX; a scheduled local outbox processor (`FOR UPDATE SKIP LOCKED`) issues the ticket and writes `TICKET_ISSUED` atomically, then marks the confirmation event published. RabbitMQ, PDF/QR, and notifications remain deferred.

### Outbox pattern for events

**What:** a domain change and an `outbox_event` record commit in the same PostgreSQL transaction. A publisher later sends it to RabbitMQ; consumers are idempotent.

**Why:** avoids the dual-write failure where the database commits but the message is lost.

**Alternative:** publish directly after save. Simpler but unreliable under process/network failure.

## Authorization model

JWT establishes identity (`POST /api/v1/auth/login` issues a Bearer access token plus an opaque refresh token; resource-server validation of the access JWT is stateless). `POST /api/v1/auth/refresh` rotates refresh tokens inside a DB-backed family (`SELECT … FOR UPDATE` + partial unique active-family index). `POST /api/v1/auth/logout` revokes the presented family only; access JWTs remain valid until expiry. Backend policy checks establish access. Platform roles from `user_roles` are embedded in the token `roles` claim as `ROLE_<code>` authorities, but **Phase 9.2A** treats the database as authoritative for platform admin: `/api/v1/admin/**` requires an ACTIVE user with `ADMIN` or `SUPER_ADMIN` in `user_roles`, and a valid access JWT for a `SUSPENDED`/`INACTIVE` user is rejected with generic `401` on protected APIs. Permissions are named actions (for example `TRIP_WRITE`, `BOOKING_READ_ALL`) and roles group permissions — fine-grained permission checks remain future work.

**Phase 9.2B** keeps platform and operator authorization conceptually separate. `AuthorizationService` remains responsible for ACTIVE users and platform admin/super-admin. `OperatorAuthorizationService` authorizes `/api/v1/operator/{operatorId}/**` from `operator_users` using the cryptographically validated JWT `sub` plus the path `operatorId`. JWT operator/role claims, body/query `userId`, and body/query `operatorId` are never treated as proof of tenancy. A writable body `operatorId` that is not on the PATCH allow-list is rejected (`400`); the server never switches tenant from a body field. Platform admins are not auto-authorized on the operator namespace.

Operator resource reads are tenant-scoped in the repository (`operator_id = path operatorId`, or `(resourceId, operatorId)`). Operator booking reads additionally require both `bookings.operator_id` and `trips.operator_id` to equal the path operator (the booking column has no FK). Cross-tenant or missing membership looks like generic `404`; an ACTIVE member with the wrong role, or whose operator is not `ACTIVE`, receives `403`. Admin access is privilege-checked in application services rather than a blanket JWT-role bypass. Customer booking access requires `booking.user_id == authenticatedUserId`.

## Future extraction seams

Likely later services: Identity, Fleet, Scheduling, Booking/Inventory, Payments, and Notifications. At extraction time, retain IDs such as `user_id`, `operator_id`, `trip_id`, and `booking_id` as opaque references across service databases; replace cross-service foreign keys and joins with APIs, replicated read models, and events. Money records and booking/inventory should remain co-located until a carefully designed saga is justified.

## Non-functional foundations to plan for

- Store timestamps in UTC (`timestamptz`); retain India-local service dates and IANA time zone (`Asia/Kolkata`) where operationally meaningful.
- Use UUID primary keys externally and internally to make future data movement easier.
- Add audit metadata to mutable operational entities: `created_at`, `updated_at`, and actor fields where relevant.
- Treat personal data as sensitive: encrypt/separate secrets, minimize access, and define retention/deletion policies before launch.
- Use immutable payment/refund/settlement financial records; corrections should be reversal/adjustment entries, not edits.

## Architecture review before implementation

### Missing decisions or fields to settle

- Fare policy needs a formal owner and snapshot: base fare, GST/tax treatment, platform/operator commission, convenience fee, insurance/add-ons, and rounding.
- Cancellation/refund policy for **confirmed** bookings still needs eligibility windows, fee calculation, and partial cancellation. Unpaid `PENDING_PAYMENT` customer cancellation is implemented (V12) and records `UNPAID_CUSTOMER_CANCELLATION_V1` with refundable amount `0`. Confirmed customer cancellation records `CONFIRMED_FULL_REFUND_CUSTOMER_CANCELLATION_V1`. Operator/admin trip cancellation records `TRIP_CANCELLED_FULL_REFUND_V1` / `TRIP_CANCELLED_UNPAID_V1` (V20) and reuses the V19 refund retry path.
- Define whether a booking can cover only one origin/destination pair (recommended MVP) and whether multi-seat passengers always share that segment.
- Decide required passenger fields by regulation and operator policy; avoid collecting government ID, gender, and date of birth unless justified.
- Plan service calendars, trip exceptions, bus substitution, crew/driver, boarding manifests, delays, vehicle compliance, KYC, support/disputes, invoices/GST, and chargebacks as later domains.
- Decide tax-invoice/GST requirements and whether the platform or the operator is merchant of record; this determines invoice and settlement responsibility.

### Normalization review

The design is normalized for mutable master data: buses reference layouts, routes reference ordered stops, and bookings reference their children. The apparently repeated seat labels, trip stops, passenger/fare values, and money totals are purposeful transaction snapshots so that later changes to a layout, route, or fare do not rewrite history. Avoid JSON for relational search/filter fields; JSONB is reserved for flexible metadata/rule payloads with versioning. Do not persist an editable “available seats count” as truth—derive it or maintain it as a rebuildable read model.

### Concurrency review

The highest-risk race is two customers selecting the same seat **over overlapping route segments**. The required defense is a unique per-trip inventory row plus atomic insertion/transition of an active segment allocation protected by a PostgreSQL exclusion constraint, with short transactions and conflict handling. Holds carry an expiry and are reclaimed safely. Unpaid `PENDING_PAYMENT` bookings carry a persisted `payment_expires_at` and are reclaimed by a scheduled reaper (`FOR UPDATE SKIP LOCKED` per booking): booking → `EXPIRED`, items → `EXPIRED`, allocations `BOOKED` → `RELEASED`. A concurrent confirm/cancel that locks the booking first wins entirely; a confirmed booking cannot subsequently expire. Payment failure/expiry cannot leave seats stuck. A late successful payment after a released/expired unpaid booking must never re-book the seat automatically: record it, mark it as requiring compensation/refund workflow, and notify support/customer. The payment webhook ledger and payment/provider ID uniqueness make duplicates harmless. The design still needs a load test for hot trips, delayed webhooks, worker crashes between database commit and publish, cancellation while payment is pending, and same-seat overlapping/non-overlapping segment requests.

Payment processing follows the global lock order `booking → payment_attempt → allocations`. Verified success, payment disposition, booking confirmation, provider-event completion, and outbox records commit atomically. Expiry/cancellation winning first leaves the booking terminal and records later captured money as `SUCCEEDED / REQUIRES_RESOLUTION`; it never recreates an allocation. Provider calls occur outside database transactions. Operator/admin trip cancellation locks `trip → bookings (id order) → per-booking allocations/ticket/succeeded payment → active holds (id order)` and never calls Razorpay while those locks are held. After a successful trip-cancel commit, no `CONFIRMED` booking remains on that `CANCELLED` trip.

### Security review

Primary risks are insecure direct object references, operator tenant leakage, JWT/session theft, privilege escalation through client-supplied scope, payment-webhook forgery/replay, PII in logs/events, coupon abuse, and overpowered admin accounts. Mitigations are backend row scope checks, least-privilege permissions, hashed refresh tokens and revocation, signed/timestamp-checked webhook validation with event deduplication, encrypted/limited PII access, audit logs, rate limits, input validation, and secret management. Angular route guards improve usability only; they are not an authorization control.

### Scale review

Search and inventory will become hot first. Index service date/route/location query paths, cache read-only search/seat-map projections carefully, and invalidate/reload availability from the authoritative database. Partition/archive large booking, audit, notification, and event tables by time when volume warrants it. Reporting and settlement queries should move to event-driven read models rather than burden booking transactions. RabbitMQ consumers need dead-letter/replay operations. Do not split services merely for anticipated scale; extract only where measured load, deployment cadence, or team ownership makes the trade-off worthwhile.
