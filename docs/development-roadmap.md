# Development roadmap

## Approval gate (now)

Review and explicitly approve the architecture documents. Decide the payment provider, cancellation/refund policy, fare/tax/commission rules, operator onboarding/KYC requirements, and ticket/legal requirements before a production payment implementation.

## Phase 1 — foundations

Create the repository structure, modular-monolith conventions, PostgreSQL migrations, security baseline, audit conventions, CI, error handling, observability, and reference data. Define transactional-outbox conventions now; add the outbox table/publisher only with the first transactional business module, when it has events to publish. No production rollout before backups, monitoring, and secret management are defined.

## Phase 2 — operator supply

Implement identity/access, operators and memberships, fleet, layout versioning, locations, routes/stops/points, and scheduled trips. Add operator-scoped authorization tests.

**Implemented in schema/entities:** identity, operators, fleet, locations, routes/stops, route points, trips with `service_date`/`time_zone`, trip-stop and trip-point snapshots, and physical `trip_seat_inventory`. **Phase 8.1–8.3:** email/password login (JWT access + opaque refresh), customer self-registration with automatic `CUSTOMER` role, `GET /api/v1/auth/me`, refresh rotation with reuse detection (family revoke), and logout (family revoke). Refresh-token cleanup/reaper is deferred. **Phase 9.2A:** existing `/api/v1/admin/**` APIs require an ACTIVE database user with `ADMIN` or `SUPER_ADMIN` in `user_roles` (JWT role claims are not authoritative). Suspended/inactive users receive generic `401` on protected APIs. **Phase 9.2B:** operator-scoped `/api/v1/operator/{operatorId}/**` reads (profile, buses, trips, trip bookings/manifest) plus `GET /api/v1/auth/operator-memberships` and `OPERATOR_ADMIN` support-contact PATCH. Authorization uses `operator_users` (not JWT roles). **Phase 9.5E:** operator membership administration (`GET`/`POST`/`PATCH`/`deactivate` under `/api/v1/operator/{operatorId}/members`) with last-ACTIVE-`OPERATOR_ADMIN` protection via `operators FOR UPDATE`. Still outstanding: operator trip/inventory/route/layout writes, operator payment/refund/settlement APIs, reporting, permission catalogs, audit log, Redis, and access-token denylist.

The V4 whole-trip `trip_seats` sale-state model was replaced by V5. Do not reintroduce `HELD`/`BOOKED` on physical inventory.

## Phase 3 — customer discovery and booking

Implement search, trip seat-map projection, segment-aware database-safe holds, bookings/passengers, expiry jobs, and customer booking views. Use trip-specific boarding/drop-point snapshots. Load-test simultaneous overlapping and non-overlapping seat selections before connecting real payments.

**Implemented:** segment inventory/allocations, seat holds + reaper, journey availability, auth/refresh, hold-to-book, unpaid-booking expiry, V11 payment foundation, V12 customer booking views/unpaid cancellation/search, **Phase 9.3 Razorpay**, **Phase 9.4A Ticket Foundation**, and **Phase 9.4B automatic ticket issuance** (local outbox processor for `BOOKING_CONFIRMED` → ticket + `TICKET_ISSUED`; no RabbitMQ/PDF/QR/notifications). **Still outstanding:** confirmed-booking cancellation/refund *policy*, RabbitMQ publishing/consumers, PDF/QR, channel notifications, and operational reconciliation of abandoned `INITIATING` attempts that the customer never retries.

## Phase 4 — payments and transactional communication

**Phase 9.3 done:** Razorpay is wired behind the V11 `PaymentProvider` adapter with signed Checkout/webhook rules and refund execution. **Phase 9.4A/B done:** Ticket domain + ownership APIs + local DB outbox processor issuing tickets after confirmation. Remaining: RabbitMQ publisher/consumers, PDF/QR, and channel-agnostic notification adapters. Preserve V11's database idempotency and booking-first lock order.

## Phase 5 — operations

Implement admin workflows, reporting, settlements, reviews, coupons, reconciliation, policy enforcement, and support tooling.

## Recommended MVP boundary

MVP should support a small vetted set of operators, bus/seat-layout setup, route/trip scheduling, Telangana/Andhra Pradesh search, point-to-point seat selection, one payment provider, confirmed e-ticket, booking lookup/cancellation, and basic operator/admin oversight. It supports one continuous trip, including non-overlapping segment resale of a seat, but excludes transfers/multi-leg itineraries. Exclude dynamic pricing, loyalty, broad coupon campaigns, automated settlements, live tracking, crew management, multiple payment providers, and sophisticated reporting until the core booking/payment path is reliable.

## Review checklist before implementation

- Confirm domain ownership and API/event contracts.
- Confirm fare, tax, cancellation, refund, and settlement policy snapshots.
- Confirm data-retention, privacy, consent, and support requirements.
- Prototype and load-test inventory contention and webhook duplication.
- Threat-model authentication, tenancy isolation, webhooks, admin actions, and PII access.
