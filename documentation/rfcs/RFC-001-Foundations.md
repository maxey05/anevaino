# RFC-001 — Repository, CI and Deployment Foundations

| | |
|---|---|
| **Status** | Draft |
| **Complexity** | Medium |
| **Phase** (RULES §5.2) | 1 — Foundations (wk 1–2) |
| **Features** | F44 (baseline only), F40 (backend half: `GET /api/health`) |
| **PRD** | §6 NFR Security / Cost / Cold starts, §6.1, §9 wk 1–2 |
| **Predecessors:** | none |
| **Successors:** | RFC-002, RFC-003, RFC-006, RFC-008 |

Sections omitted: *Data model* beyond Flyway wiring (no domain tables yet — they arrive in RFC-002/005/010), *UI/UX* beyond the app shell.

## 1. Summary

Creates the monorepo exactly as RULES §1.2 lays it out, a runnable Kotlin/Spring Boot backend and React/Vite frontend with every tool pinned to the RULES §1.1 versions, the cross-cutting plumbing every later RFC relies on (Problem Details error model, request-ID logging, security headers, Flyway, Testcontainers), CI, and a deployed empty app on Vercel + Render + Neon. Nothing user-facing beyond an app shell and a health check.

## 2. Scope

**In:** repo layout; Gradle + npm projects; `CLAUDE.md`; `.env.example`; Flyway with `ddl-auto=validate`; Problem Details registry; `@RestControllerAdvice`; structured JSON logging with request ID; security headers; `GET /api/health`; Vercel rewrite of `/api/*` to Render (R-2.15); GitHub Actions CI; Tailwind v4 with a `dark` variant driven by a `data-theme` attribute (so RFC-012 only adds the toggle); route skeleton with lazy loading (R-2.33).

**Out:** auth (RFC-002), any domain table, the friendly wake-up screen (RFC-012 owns F40's UI per RULES phase 5).

## 3. Technical approach

```
Browser ──> Vercel (static SPA)
              └─ rewrite /api/* ──> Render (Spring Boot, 512 MB) ──> Neon Postgres
```
Same origin from the browser's view (R-2.15), so no CORS config exists anywhere.

Backend package root `ph.anevaino` with the empty feature packages of RULES §2.2 plus `common/` populated:

- `common/error/ProblemTypes.kt` — the single registry of `type` slugs. Every later RFC adds its slugs here **and** to `frontend/src/api/problemTypes.ts`; a unit test on each side asserts the two lists are identical by reading the other file's slug list from `shared/problem-types.json` (both files are generated from / checked against that JSON).
- `common/error/DomainExceptions.kt` — sealed base `AnevainoException(type, status, detail, extensions)` with subclasses `RuleViolation`, `ParseFailure`, `NotFound`, `ValidationFailure` (R-4.6).
- `common/error/GlobalExceptionHandler.kt` — maps them to `application/problem+json`; any other exception → 500 `common/internal-error` with no stack trace.
- `common/web/RequestIdFilter.kt` — reads `X-Request-Id` or generates a UUID, puts it in MDC and echoes it in the response.
- `common/web/SecurityHeadersConfig.kt` — HSTS, CSP `default-src 'self'; script-src 'self'; img-src 'self' data:; frame-ancestors 'none'`, `X-Content-Type-Options: nosniff` (R-2.23). `Referrer-Policy: no-referrer` globally (stricter than R-2.23's share-only requirement; harmless).
- `common/health/HealthController.kt` — `GET /api/health` → `200 {"status":"UP"}`, no DB call (so a warm check is cheap), permitted without auth.

Initial problem types: `common/internal-error` (500), `common/not-found` (404), `common/validation` (400), `common/rate-limited` (429).

Frontend: `app/App.tsx` (providers: QueryClient, Router), `app/routes.tsx` with lazy routes `/`, `/upload`, `/review`, `/map`, `/settings`, `/privacy`, `/s/:token` each rendering a placeholder heading that later RFCs replace (route components are the only allowed stubs, and each later RFC's AC replaces its route — R-6.3 applies at merge of the whole set, see §7), `api/client.ts` (fetch wrapper that sends credentials, attaches CSRF header when RFC-002 provides it, parses Problem Details into a typed `ApiProblem`, validates success bodies with a Zod schema passed by the caller).

## 4. Interfaces

| Method | Path | Auth | Response |
|---|---|---|---|
| GET | `/api/health` | none | `200 {"status":"UP"}` |

Problem Details shape (all RFCs): `{ "type": "<slug>", "title": string, "status": int, "detail": string, "requestId": string, ...extensions }`. `type` is the bare slug (e.g. `rule/hard-prereq-unmet`), not a URL.

## 5. Implementation details

### File structure
```
CLAUDE.md
.env.example
.gitignore
.nvmrc
.github/workflows/ci.yml
shared/problem-types.json
shared/rule-vectors/.gitkeep
fixtures/flowcharts/.gitkeep
backend/settings.gradle.kts
backend/build.gradle.kts
backend/gradle/wrapper/gradle-wrapper.properties
backend/src/main/kotlin/ph/anevaino/AnevainoApplication.kt
backend/src/main/kotlin/ph/anevaino/common/error/ProblemTypes.kt
backend/src/main/kotlin/ph/anevaino/common/error/DomainExceptions.kt
backend/src/main/kotlin/ph/anevaino/common/error/GlobalExceptionHandler.kt
backend/src/main/kotlin/ph/anevaino/common/web/RequestIdFilter.kt
backend/src/main/kotlin/ph/anevaino/common/web/SecurityHeadersConfig.kt
backend/src/main/kotlin/ph/anevaino/common/health/HealthController.kt
backend/src/main/resources/application.yml
backend/src/main/resources/logback-spring.xml
backend/src/test/kotlin/ph/anevaino/common/GlobalExceptionHandlerTest.kt
backend/src/test/kotlin/ph/anevaino/common/ProblemTypesParityTest.kt
backend/src/test/kotlin/ph/anevaino/support/PostgresContainerSupport.kt
frontend/package.json
frontend/vite.config.ts
frontend/vercel.json
frontend/src/main.tsx
frontend/src/app/App.tsx
frontend/src/app/routes.tsx
frontend/src/app/index.css
frontend/src/api/client.ts
frontend/src/api/problemTypes.ts
frontend/src/api/client.test.ts
frontend/e2e/smoke.spec.ts
```

### Configuration
- `application.yml`: `spring.jpa.hibernate.ddl-auto=validate`, `spring.flyway.enabled=true`, Hikari `maximum-pool-size: 5` (R-1.4), `server.forward-headers-strategy=framework` (Render terminates TLS), `spring.jpa.open-in-view=false`.
- Render start command uses `-XX:MaxRAMPercentage=75` (R-1.4).
- `vercel.json` rewrites `/api/:path*` → `${RENDER_URL}/api/:path*` and all other paths → `/index.html`.
- CI (`ci.yml`), on every PR: ktlint, `./gradlew test`, `npm ci`, ESLint, Prettier check, `tsc --noEmit`, Vitest, Playwright smoke against `vite preview` with the API mocked (R-4.5). Later RFCs add jobs (rule-vector parity RFC-006, fixture accuracy RFC-004, axe RFC-014).

### Logging
JSON encoder in the `prod` profile, pattern with `requestId` in `dev`. One `INFO` line per request completion: method, path template, status, duration, `userId` if present (RFC-002 fills it). Never log request bodies (R-2.26).

### Testing
- `GlobalExceptionHandlerTest` (`@WebMvcTest` with a test controller): each exception subclass → correct status, content type `application/problem+json`, `requestId` present, no stack trace on a thrown `IllegalStateException`.
- `ProblemTypesParityTest` + `client.test.ts`: slug lists equal `shared/problem-types.json`.
- `PostgresContainerSupport`: shared Testcontainers Postgres base class used by every later persistence test (R-4.3).
- `smoke.spec.ts`: landing route renders.

## 6. Considerations

- **Rules applied:** R-1.1–R-1.5, R-2.3 (empty pure packages), R-2.9, R-2.13, R-2.15, R-2.23, R-2.24, R-2.33, R-4.5, R-4.6, R-4.8, R-6.8.
- **Placeholder routes vs R-6.3:** R-6.3 forbids placeholders in merged code. Route placeholders are unavoidable in a foundations RFC; each is replaced by a named later RFC (`/upload`,`/review` → RFC-009; `/map`,`/settings` → RFC-011; `/privacy` → RFC-015; `/s/:token` → RFC-017; `/` → RFC-002). RFC-018 AC verifies none remain. Flag this in the PR.
- **Cost:** every service on free tier (R-1.3). Vercel Hobby, Render free, Neon free.
- **Edge:** Render cold start makes the first rewrite time out on Vercel (Vercel rewrites have a proxy timeout). RFC-012's wake-up screen retries; nothing to do here besides keeping `/api/health` dependency-free.

## 7. Acceptance criteria

- **AC-001.1** The repository matches RULES §1.2 and contains every file listed in §5 "File structure" above.
- **AC-001.2** `backend` builds and tests pass on JDK 25 with Kotlin 2.4.20, Gradle wrapper 9.7.1, Spring Boot 4.1.1; no BOM-managed version is overridden. Lockfiles/versions match RULES §1.1 exactly.
- **AC-001.3** `frontend` builds with Node 22 / npm 10 and the exact versions in RULES §1.1 (TypeScript 6.0.3, not 7.x); `package-lock.json` is committed; `.nvmrc` and `engines` pin Node 22.
- **AC-001.4** `GET /api/health` returns `200 {"status":"UP"}` without authentication and without touching the database.
- **AC-001.5** Throwing each `AnevainoException` subclass from a controller yields `application/problem+json` with `type`, `title`, `status`, `detail`, `requestId`; an unexpected exception yields 500 `common/internal-error` with no stack trace in the body.
- **AC-001.6** Every response carries an `X-Request-Id` header, and that ID appears in the corresponding log line.
- **AC-001.7** Responses carry HSTS, the CSP above (no `unsafe-inline` script source), `X-Content-Type-Options: nosniff` and `Referrer-Policy: no-referrer`.
- **AC-001.8** `shared/problem-types.json`, `ProblemTypes.kt` and `problemTypes.ts` list identical slugs, enforced by tests on both sides.
- **AC-001.9** Flyway runs on startup against Testcontainers Postgres and Hibernate runs with `ddl-auto=validate`; `open-in-view` is off; Hikari max pool size is 5.
- **AC-001.10** `api/client.ts` sends same-origin credentials, turns a Problem Details response into a typed `ApiProblem`, and rejects a success body that fails its Zod schema (unit-tested).
- **AC-001.11** Routes `/`, `/upload`, `/review`, `/map`, `/settings`, `/privacy`, `/s/:token` exist and are lazy-loaded; the landing bundle does not include `@xyflow/react` (checked by inspecting the Vite build manifest in CI).
- **AC-001.12** Tailwind v4 is configured with a `dark` variant keyed on `[data-theme="dark"]` on `<html>`.
- **AC-001.13** CI runs ktlint, backend tests, ESLint, Prettier check, `tsc --noEmit`, Vitest and the Playwright smoke test on every PR, and a failure blocks merge.
- **AC-001.14** The app is deployed: the Vercel URL serves the SPA and `https://<vercel-url>/api/health` returns 200 through the rewrite from Render, with the database on Neon.
- **AC-001.15** `CLAUDE.md` contains the R-6.8 pointer text; `.env.example` lists every environment variable name with no values; `.env*` (except the example) is git-ignored.
