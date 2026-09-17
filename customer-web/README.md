# BLUE BUS Customer Website

Angular 19 customer booking UI for BLUE BUS.

## Run locally

The API is proxied from `/api` to `http://localhost:8080`. Start the Spring Boot backend first.

```bash
npm install
npm start
```

Open http://localhost:4200

## Scripts

- `npm start` — development server with API proxy
- `npm test` — unit tests (ChromeHeadless)
- `npm run build` — production build

API paths use `environment.apiBaseUrl` (`/api/v1`). Do not hardcode hostnames in feature code.

## Local E2E with demo data

The backend can seed a local-only catalog. See the root `README.md` section **Local E2E demo data**.

1. Start PostgreSQL and the Spring Boot backend with `DEMO_DATA_ENABLED=true` and `DEMO_CUSTOMER_PASSWORD` set in the shell (never commit the password).
2. Start this app with `npm start` and open http://localhost:4200
3. Sign in at `/login` with `demo.customer@example.test` and the password from `DEMO_CUSTOMER_PASSWORD`
4. Search **Hyderabad → Vijayawada** on **2099-01-15**
5. Expected: one Demo Express trip with 4 seats; hold → passengers → booking works through `PENDING_PAYMENT`

Razorpay browser checkout requires valid Razorpay test credentials on the backend. Without them, payment initiation stays unconfigured. There is no fake payment success.
