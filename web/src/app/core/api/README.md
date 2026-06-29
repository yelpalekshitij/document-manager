# Generated API Client

The `generated/` subdirectory is produced by `@openapitools/openapi-generator-cli`
from `../../../../openapi/vaultdoc.yaml`. **Do not commit** generated output —
it is git-ignored and recreated on every dev/build cycle.

## How it runs

- `npm run generate:api` calls the generator using `openapitools.json` at the
  web project root.
- `npm run start`, `npm run build`, and `npm run test` each have a `pre*`
  hook that runs `generate:api` first, so the generated code is always fresh
  before TypeScript compiles.

## What's generated

- `generated/api/auth.service.ts` — `AuthService` (Injectable, providedIn 'root')
  with one method per endpoint operationId (`register`, `login`, `verifyEmail`,
  `resendVerification`, `refresh`, `logout`, `getCurrentUser`).
- `generated/api/system.service.ts` — `SystemService` (`getHealth`).
- `generated/model/*.ts` — TypeScript interfaces / enums for every schema in
  the spec.
- `generated/index.ts` — re-exports the above; app code imports from this.

## How to use

```ts
import { AuthService } from './core/api/generated';
```

Wrap it in a store / facade rather than calling directly from components.
See `core/auth/auth.store.ts` for the canonical pattern.

## Adding a new endpoint

1. Edit `openapi/vaultdoc.yaml` (add the path, schemas, error responses).
2. `npm run generate:api` (or just let `npm run start` do it).
3. The new method shows up on the corresponding `*Service` class.
4. Wire it into the appropriate store.

Never hand-write API calls. The spec is the contract.
