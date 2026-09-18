# Production operations checklist

Operational work that must be completed before BLUE BUS is deployed to production. Source of truth: `README.md`, `docs/development-roadmap.md`, `docs/architecture.md`, `docs/database-design.md`, `.env.example`, `src/main/resources/application.yml`, `src/main/resources/application-prod.yml`, and `.github/workflows/ci.yml`.

This repository does **not** provision cloud infrastructure, automated backups, monitoring products, or a restore drill. Those are operational obligations, not implemented features. Do not treat placeholders in `.env.example` as production values. Never commit secrets.

**When to complete**

- **Required before production** — blocking launch gate
- **Required during deployment** — blocking the release procedure
- **Required after deployment** — blocking “production is live”
- **Deferred / non-blocking** — product or architecture follow-up; not a substitute for the infrastructure items above

## 1. Production environment

Set **both** runtime markers. `ProductionConfigurationGuard` treats production as active when Spring profile `prod` is active **or** `BLUE_BUS_ENVIRONMENT=production`. Deployments must set both so omitting the profile still fail-fasts.

| Setting | Production requirement | When |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `prod` | Required before production |
| `BLUE_BUS_ENVIRONMENT` | `production` | Required before production |
| `DEMO_DATA_ENABLED` (`blue-bus.demo-data.enabled`) | `false` (startup fails if demo data is enabled in production, and also if Razorpay is selected with demo data enabled) | Required before production |
| `BLUE_BUS_ADMIN_MASTER_DATA_ENABLED` (`blue-bus.admin-master-data.enabled`) | `true` (must not be `false`; this flag registers the core business API, not only admin master data) | Required before production |
| `PAYMENT_PROVIDER` (`blue-bus.payments.default-provider`) | `RAZORPAY` (startup fails if missing or `UNCONFIGURED`) | Required before production |

**Razorpay credentials** (required before production when `PAYMENT_PROVIDER=RAZORPAY`; values are never logged):

- `RAZORPAY_KEY_ID`
- `RAZORPAY_KEY_SECRET`
- `RAZORPAY_WEBHOOK_SECRET`

Optional override: `RAZORPAY_BASE_URL` (application default `https://api.razorpay.com`). Do not put secrets in YAML or Git.

**JWT** (required before production):

- `JWT_SECRET` is required and must be **at least 32 bytes** (UTF-8) for HS256
- Never commit a real secret; `.env` is Git-ignored
- Defaults from `.env.example` / `application.yml` unless overridden: `JWT_ISSUER=blue-bus`, `JWT_ACCESS_TOKEN_TTL_SECONDS=900`, `REFRESH_TOKEN_TTL_SECONDS=1209600`
- Logout revokes the refresh family only; access JWTs remain valid until their short TTL (no access-token denylist)

**PostgreSQL** (required before production; database must be reachable at startup):

- `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`
- JDBC URL is `jdbc:postgresql://${DB_HOST}:${DB_PORT}/${DB_NAME}`
- `spring.jpa.hibernate.ddl-auto=validate`
- Flyway is enabled (`classpath:db/migration`, `baseline-on-migrate: false`) and runs on application startup

**CORS / public origin** (required before production):

- Fail-closed default: empty `blue-bus.cors.allowed-origins` (never `*`)
- Recommended production: serve the Angular `customer-web` production build and `/api/v1` on **one public origin** so the empty allow-list is correct (`customer-web` uses relative `/api/v1`; do not hard-code a public API hostname in the SPA)
- Split-origin hosting only: set explicit allowed origins **and** `BLUE_BUS_CORS_REQUIRE_ALLOWED_ORIGINS=true` (startup fails if that flag is true with an empty allow-list)
- Local Angular development uses the `customer-web` `/api` proxy and does not require CORS

Also keep `BLUE_BUS_RATE_LIMIT_ENABLED=true` unless an explicit operational decision disables the in-process limiter. It is **per-instance** (servlet remote address only; no trusted-proxy / `X-Forwarded-For`). Production must still rate-limit at the reverse proxy / API gateway / WAF. Redis is deferred.

Do not enable the outbox processor while the core API flag is `false`; production already refuses `admin-master-data.enabled=false`.

## 2. Database

PostgreSQL is the system of record. This repository defines schema via Flyway (currently through V21) and does **not** implement backup jobs, retention, or restore automation.

**Required before production**

- Automated PostgreSQL backups of the production database identified by `DB_NAME`
- A documented backup retention policy (financial and booking rows must not be silently deleted; see `docs/database-design.md`)
- A documented restore procedure (restore a backup into PostgreSQL, then start a known-good application commit so Flyway `validate` matches the restored schema)
- A restore drill using that procedure, completed successfully before launch
- `docs/development-roadmap.md` Phase 1: no production rollout before backups, monitoring, and secret management are defined

**Flyway migration procedure (required during deployment)**

1. Deploy a known Git commit whose `src/main/resources/db/migration` set is the intended schema.
2. Ensure PostgreSQL is reachable and the configured user can create/update the Flyway schema-history table.
3. Start the application so Flyway migrates, then Hibernate `ddl-auto=validate` confirms the schema.
4. Do not use `baseline-on-migrate` in production (`false` in `application.yml`).
5. Do not rely on Hibernate to create or alter production tables.

**Rollback / recovery (required during deployment; must be written before first production migrate)**

The repository does not ship Flyway undo scripts. If a migration or release fails:

1. Stop serving traffic on the failed release.
2. Restore PostgreSQL from the pre-migration backup (restore drill must have proven this).
3. Start the previous known-good Git commit.
4. Confirm Flyway history and `ddl-auto=validate` succeed, then re-run smoke checks.

Do not “roll forward” by editing applied migrations. New schema changes are new versioned files only.

## 3. Application security

**Required before production**

- **HTTPS** in front of the public origin. The Spring app does not set `Strict-Transport-Security`; terminate TLS at the reverse proxy / API gateway.
- **Reverse proxy / API gateway** serving the SPA and `/api/v1` (same-origin) or forwarding to the backend with explicit CORS if split-origin.
- **Edge rate limiting** on login, register, refresh, holds, and Razorpay webhook paths (`POST /api/v1/payments/webhooks/*`). The JVM limiter is per instance only.
- **Secret management** for `JWT_SECRET`, `DB_PASSWORD`, and Razorpay secrets (process environment or an operations secret store—not Git, not `application-prod.yml`).
- **No secrets in Git.** `.env` is ignored; only `.env.example` placeholders are committed. `application-prod.yml` contains no secrets.
- **Security headers** already emitted by the application: `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, cache-control no-store, `Referrer-Policy: strict-origin-when-cross-origin`. Content-Security-Policy is not set by the app.
- **CORS fail-closed:** empty allow-list grants no browser CORS headers; never `*`.

**Required during deployment**

- Confirm production process environment has no `DEMO_DATA_ENABLED=true` and no `PAYMENT_PROVIDER=UNCONFIGURED`.
- Confirm webhook URL configuration at Razorpay points at this deployment’s `POST /api/v1/payments/webhooks/{provider}` path (provider identity is the adapter signature verifier).

## 4. Monitoring and alerting

The application exposes `GET /api/v1/health` as a **liveness** probe (`status: "UP"`, service `blue-bus-backend`). It is **not** a PostgreSQL or Flyway readiness check and does not mean the core business API is registered. No monitoring vendor, dashboard, or on-call roster is defined in this repository.

**Required before production** (define, implement operationally, and assign owners):

- Application health/liveness monitoring of `GET /api/v1/health`
- Application error monitoring (HTTP 5xx, failed startups, `ProductionConfigurationGuard` refusals)
- Database monitoring (availability, connections, disk, migration failures)
- Payment/webhook monitoring (Razorpay webhook receive/process, `payment_provider_events` inbox, refund retry / INITIATING recovery workers)
- Booking/payment failure monitoring (unpaid expiry, `REFUND_PENDING` stuck refunds, provider 503/unavailable)
- Host disk / CPU / memory monitoring for the application and PostgreSQL processes
- Alert ownership and escalation (named operator, not an unimplemented in-app feature)

**Required after deployment**

- Alerts fire to the assigned owner on health failure, database unavailability, and payment/webhook error spikes

## 5. Deployment

CI is `.github/workflows/ci.yml`: `git diff --check`, `./mvnw -B test` (Java 21), and `customer-web` `npm ci` / `npm test -- --watch=false` / `npm run build`. Triggers: `push`/`pull_request` to `main` and `workflow_dispatch`.

**Required before production**

- Production artifacts built from a **known Git commit** (immutable revision)
- **CI must pass** on that commit (validate + backend tests + frontend test/build)

**Required during deployment**

1. Back up PostgreSQL immediately before applying a release that includes Flyway versions not yet on production.
2. Run database migration by starting the new commit (Flyway then `ddl-auto=validate`). Do not start application traffic before migration succeeds.
3. Application startup verification: process starts, production guard accepts config, Flyway completes.
4. Production smoke test at minimum: `GET /api/v1/health` returns `status: "UP"` over the public HTTPS origin.
5. If startup, migrate, or smoke fails: execute the restore/rollback procedure in section 2 (previous commit + backup).

**Required after deployment**

- Repeat health smoke on the live origin
- Confirm Razorpay webhooks are accepted (signature verification; duplicate `provider_event_id` is idempotent)

## 6. Operational verification

**Required after deployment** (implemented product paths):

- **Customer booking flow:** location search, trip search, seat availability, authenticated hold, hold-to-booking (`PENDING_PAYMENT`)
- **Payment flow:** Razorpay Checkout initiation, webhook confirmation, booking `CONFIRMED` (local `UNCONFIGURED` → HTTP 503 is not acceptable in production)
- **Ticket issuance:** local outbox processor issues a ticket after `BOOKING_CONFIRMED` (owner-scoped ticket APIs)
- **Cancellation/refund flow:** unpaid cancel; confirmed cancel → `REFUND_PENDING` → captured refund → `REFUNDED`; refund retry worker
- **Operator access / tenant isolation:** `/api/v1/operator/{operatorId}/**` uses `operator_users` (not JWT role claims); cross-tenant resources stay `404`; inactive membership cannot use the operator namespace
- **Admin access:** `/api/v1/admin/**` requires an ACTIVE database user with `ADMIN` or `SUPER_ADMIN` in `user_roles` (JWT claims are not authoritative)

**Deferred / non-blocking for this launch** (product, not a substitute for backups/monitoring):

- Channel notifications (email/SMS/WhatsApp/push), PDF/QR ticketing, RabbitMQ publishing/consumers, settlements, Admin SPA, HttpOnly-cookie BFF, GST/`trip_fares`, Redis, access-token denylist, refresh-token cleanup/reaper

Razorpay **webhooks** are in scope (required). Generic customer/operator notification adapters are not.

## 7. Launch gate

### Required before production

- [ ] `SPRING_PROFILES_ACTIVE=prod` and `BLUE_BUS_ENVIRONMENT=production` will both be set
- [ ] `DEMO_DATA_ENABLED=false`
- [ ] `BLUE_BUS_ADMIN_MASTER_DATA_ENABLED=true`
- [ ] `PAYMENT_PROVIDER=RAZORPAY` with `RAZORPAY_KEY_ID`, `RAZORPAY_KEY_SECRET`, and `RAZORPAY_WEBHOOK_SECRET` supplied outside Git
- [ ] `JWT_SECRET` ≥ 32 bytes, not in Git
- [ ] PostgreSQL `DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USERNAME` / `DB_PASSWORD` supplied outside Git
- [ ] Same-origin SPA + `/api/v1` **or** explicit CORS origins plus `BLUE_BUS_CORS_REQUIRE_ALLOWED_ORIGINS=true`
- [ ] HTTPS at the reverse proxy / API gateway
- [ ] Edge rate limits on login, register, refresh, holds, and webhooks
- [ ] Secret management process documented and used
- [ ] Automated PostgreSQL backups, retention policy, restore procedure, and a successful restore drill
- [ ] Monitoring and alerting defined for health, errors, database, payments/webhooks, booking/payment failures, and host resources, with named escalation
- [ ] Production migration rollback/recovery procedure written (backup + previous Git commit)
- [ ] CI workflow is green on the commit intended for production

### Required during deployment

- [ ] Deploy the known Git commit that passed CI
- [ ] Pre-release PostgreSQL backup taken
- [ ] Flyway runs on startup; Hibernate validate succeeds
- [ ] Process starts with production fail-fast configuration
- [ ] `GET /api/v1/health` returns `status: "UP"`
- [ ] Failed release uses backup restore + previous commit (no in-place migration rewrite)

### Required after deployment

- [ ] Live health check on the public HTTPS origin
- [ ] Customer search → hold → booking verified
- [ ] Razorpay payment + webhook confirmation verified
- [ ] Ticket issuance after confirmation verified
- [ ] Cancellation/refund path verified
- [ ] Operator tenant isolation verified
- [ ] Platform admin access verified
- [ ] Razorpay webhook delivery to this environment verified
- [ ] Alerts reachable by the assigned owner

### Deferred / non-blocking

- [ ] Redis (cluster-wide rate limit / temporary acceleration) — deferred
- [ ] RabbitMQ publisher/consumers — deferred
- [ ] PDF/QR ticketing — deferred
- [ ] Channel notifications — deferred
- [ ] Settlements — deferred
- [ ] Admin SPA — deferred
- [ ] HttpOnly-cookie BFF — deferred
- [ ] GST / `trip_fares` — deferred
- [ ] Access-token denylist and refresh-token reaper — deferred
- [ ] Application-emitted HSTS/CSP — not currently set; HTTPS remains a proxy concern
