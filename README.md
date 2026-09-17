# BLUE BUS

BLUE BUS is a planned bus ticket booking and operator-management platform, initially serving Telangana and Andhra Pradesh and designed to expand across India.

## Current status

This repository contains the approved architecture documentation and a **modular-monolith backend** through the V5 segment-aware inventory correction:

- Phase 1 foundation: health API, security baseline, Flyway, error envelope
- Phase 2 operator supply: identity, operators, fleet, locations, routes/stops, scheduled trips
- V5 inventory correction: trip-stop and trip-point snapshots, route boarding/drop points, and physical `trip_seat_inventory`

It does **not** contain authentication flows, seat holds, segment allocations, booking, payments, notifications, or a frontend.

Seats remain reusable `SeatLayout` definitions. A bus references one operator-owned layout; seats are not duplicated per bus or per trip.

Physical inventory:

- `TripSeatInventory` — one snapshot row per trip + layout seat. Status is only `AVAILABLE` or `BLOCKED`.

Future sale occupancy (not implemented):

- `TripSeatAllocation` — held or booked occupancy for `int4range(origin_sequence, destination_sequence, '[)')`
- `SeatHold` — temporary checkout reservation
- `Booking` + `BookingItem` — confirmed or pending purchase records

`trips.base_fare` is a temporary draft/default amount. Origin–destination fares will later live on `trip_fares`, not on inventory. Inventory does not store fare, `booking_id`, or `locked_until`.

## Proposed technology direction

- Backend: Java 21, Spring Boot, Spring Security, Spring Data JPA, Hibernate, Maven, REST
- Frontend: Angular, TypeScript, Angular Material, Reactive Forms
- Data: PostgreSQL
- Temporary state/cache: Redis
- Asynchronous messaging: RabbitMQ
- Authentication: JWT
- Deployment shape: modular monolith first; extraction-ready module boundaries later

## Documentation

- [Architecture](docs/architecture.md)
- [Domain model](docs/domain-model.md)
- [Database design](docs/database-design.md)
- [API design](docs/api-design.md)
- [Event design](docs/event-design.md)
- [Development roadmap](docs/development-roadmap.md)

## Architecture approval gate

Architecture review has been completed. Business-domain implementation remains gated by its relevant approved phase.

## Run the backend locally

Prerequisites: Java 21 and a running PostgreSQL instance. Maven does **not** need to be installed; use the committed Maven Wrapper. On Windows, set `JAVA_HOME` to the JDK 21 installation directory (the directory containing `bin\java.exe`) if it is not already set.

1. Copy `.env.example` to `.env` and replace the local placeholder values. `.env` is intentionally ignored by Git. Spring Boot does not load `.env` by itself; load those values into your shell using your preferred local environment tool.
2. Create the PostgreSQL database named by `DB_NAME` and ensure the configured user can create the Flyway schema-history table and run migrations.
3. On PowerShell, set the environment variables for the current session, then run:

   ```powershell
   .\mvnw.cmd spring-boot:run
   ```

   On macOS/Linux:

   ```bash
   ./mvnw spring-boot:run
   ```

4. Verify the public endpoint:

   ```text
   GET http://localhost:8080/api/v1/health
   ```

   It returns a small JSON response with `status: "UP"` when the application is running.

To run tests:

```powershell
.\mvnw.cmd test
```

Tests explicitly use the `test` Spring profile. Fast foundation tests disable database auto-configuration through their test annotations; `FlywayPostgresIntegrationTest` starts PostgreSQL through Testcontainers and runs Flyway normally. It needs Docker, but does not need a developer-installed PostgreSQL instance. Production/local startup still requires PostgreSQL and runs Flyway. Redis and RabbitMQ are intentionally not configured yet.

The health endpoint is a liveness probe only: it confirms the application process can serve HTTP. It is not currently a PostgreSQL readiness check. Browser CORS is an explicit `blue-bus.cors.allowed-origins` allow-list (empty by default; never `*`). Local Angular development uses the `/api` proxy in `customer-web` and does not require CORS.

## Local E2E demo data

An opt-in Spring Boot bootstrap can seed a small local catalog for browser end-to-end checks of Search → Seats → Hold → Passengers → Booking. It is **disabled by default** and must never be enabled in production.

Enable it only in a local shell (do not commit a password):

```powershell
$env:DEMO_DATA_ENABLED = "true"
$env:DEMO_CUSTOMER_PASSWORD = "<choose-a-local-password>"
# Optional. Default is demo.customer@example.test
$env:DEMO_CUSTOMER_EMAIL = "demo.customer@example.test"
```

Equivalent Spring properties:

- `blue-bus.demo-data.enabled=true` (or `DEMO_DATA_ENABLED=true`)
- `blue-bus.demo-data.customer-password` from `DEMO_CUSTOMER_PASSWORD` (required when enabled; 8–72 characters, at least one letter and one digit)
- `blue-bus.demo-data.customer-email` from `DEMO_CUSTOMER_EMAIL`

Startup fails if demo data is enabled without a valid password. The password is never logged.

### Start the backend

With PostgreSQL running and `JWT_SECRET` plus database variables loaded as in `.env.example`:

```powershell
.\mvnw.cmd spring-boot:run
```

```bash
./mvnw spring-boot:run
```

### Start customer-web

From `customer-web/`, after the backend is up:

```bash
npm install
npm start
```

Open http://localhost:4200. The Angular dev server proxies `/api` to `http://localhost:8080`.

### Demo customer login

1. Open http://localhost:4200/login
2. Email: `demo.customer@example.test` unless you overrode `DEMO_CUSTOMER_EMAIL`
3. Password: the same local value you set in `DEMO_CUSTOMER_PASSWORD`

The account is `ACTIVE` with the `CUSTOMER` role only. No platform admin is created.

### Search Hyderabad → Vijayawada

1. On the home page, choose **Hyderabad** as origin and **Vijayawada** as destination. The demo locality label is `BLUE BUS local demo`.
2. Set the journey date to **2099-01-15** (the seeded trip stays inside the existing saleability window).
3. Search.

Expected result: one saleable trip (Demo Express / `DEMO-HYD-VJA`) with **4 available seats**. You can hold a seat, enter passengers, and create a `PENDING_PAYMENT` booking.

### Payments

Razorpay Checkout is **not** stubbed. Without valid Razorpay test credentials (`PAYMENT_PROVIDER=RAZORPAY` plus key id/secret/webhook secret):

- Search, seat map, hold, passengers, and booking create still work
- Payment initiation stops at the configured-provider boundary (`UNCONFIGURED` → HTTP 503)
- Browser Razorpay Checkout is not testable
- No fake payment success is created

The bootstrap never writes payment attempts, never marks payments successful, and never deletes unrelated users, bookings, trips, tickets, or refunds. Re-running the application does not duplicate the demo catalog.
