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
