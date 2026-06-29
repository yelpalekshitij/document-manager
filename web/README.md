# VaultDoc Web

Angular 18 standalone app. Talks to the backend at `http://localhost:8080` via the dev proxy.

## Dev loop

```bash
npm install
npm run start          # http://localhost:4200
```

Make sure the backend is running first:

```bash
cd ../infra && docker compose up -d
cd ../backend && ./gradlew :api-gateway:bootRun
```

## Scripts

- `npm run start` — dev server with API proxy.
- `npm run build` — production build into `dist/`.
- `npm run test` — Karma + Jasmine.

## Phase 1.0 scope

This shell only renders a placeholder login form and a home page that polls `/api/v1/health` so you can confirm the front-end is wired to the back-end.

Real authentication, document list, upload, viewer, and search land in subsequent milestones.
