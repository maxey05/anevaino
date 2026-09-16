# RFC-002 — Google Sign-in, DLSU Domain Restriction and Session

| | |
|---|---|
| **Status** | Draft |
| **Complexity** | Medium |
| **Phase** | 1 — Foundations |
| **Features** | F1, F2, F3, F44 (user scoping + auth rate limit) |
| **PRD** | FR-1.1, FR-1.2, FR-1.3 (sign-out half), J1.1, NFR Security |
| **Predecessors:** | RFC-001 |
| **Successors:** | RFC-005 |

Sections omitted: none.

## 1. Summary

Implements the login flow fixed by R-2.16: the Google Identity Services button gives the frontend an ID token, the backend verifies it and enforces the DLSU domain, creates or loads a `User`, and starts a Spring Session JDBC session in an `HttpOnly; Secure; SameSite=Lax` cookie. Adds CSRF (cookie-to-header), sign-out, `GET /api/me`, the current-user resolver every later RFC uses for ownership (R-2.19), and rate limiting on the login endpoint (R-2.22).

## 2. Scope

**In:** `app_user` table; ID-token verification; domain check; session; CSRF; sign-out; `/api/me`; landing page with sign-in button and rejection message; route guard for authenticated routes; `CurrentUser` argument resolver; in-memory token-bucket rate limiter (reused by RFC-005, RFC-017).
**Out:** account deletion (RFC-015), settings fields' write API (RFC-010), theme toggle (RFC-012).

## 3. Technical approach

```
[Landing] GIS button ──credential(ID token)──> POST /api/auth/google
   AuthController → GoogleIdTokenVerifier (signature/JWKS, aud, iss, exp)
                  → DlsuDomainPolicy (email_verified, email suffix, hd)
                  → UserService.findOrCreate(email, name)
                  → SessionAuthenticator (new session id, SecurityContext)
   <── 200 MeDto + Set-Cookie: SESSION=...; HttpOnly; Secure; SameSite=Lax
```
Verification uses Google's JWKS via Spring Security's `NimbusJwtDecoder` with issuer validators for `https://accounts.google.com` and `accounts.google.com`, and an audience validator for `GOOGLE_CLIENT_ID`. No Google client library dependency is added (R-1.1).

**Domain policy (F2)** — pure function in `auth/DlsuDomainPolicy.kt`:
```
allowed(claims) =
  claims.email_verified == true
  AND claims.email.lowercase().endsWith("@dlsu.edu.ph")       // exact suffix incl. '@'
  AND claims.hd == "dlsu.edu.ph"
```
`x@dlsu.edu.ph.evil.com`, `x@sub.dlsu.edu.ph`, missing `hd`, and `email_verified=false` are all rejected.

**Session fixation:** the session id is rotated on login (`changeSessionId`). Session timeout 30 days idle (a planning aid used seasonally; revisit if Matthew wants shorter). Spring Session JDBC tables come from a Flyway migration, not auto-init.

**CSRF:** `CookieCsrfTokenRepository.withHttpOnlyFalse()`; `api/client.ts` reads `XSRF-TOKEN` and sends `X-XSRF-TOKEN` on non-GET. `POST /api/auth/google` is CSRF-protected too; the frontend first calls `GET /api/auth/csrf` to obtain the cookie.

**Rate limit:** `common/ratelimit/TokenBucketRateLimiter.kt`, keyed by client IP (`X-Forwarded-For` first hop, trusted because Render sets it). Login: 10 requests/minute per IP. Excess → 429 `common/rate-limited` with `Retry-After`.

## 4. Interfaces

| Method | Path | Auth | Request | Success | Errors |
|---|---|---|---|---|---|
| GET | `/api/auth/csrf` | none | – | 204, sets `XSRF-TOKEN` cookie | – |
| POST | `/api/auth/google` | none (CSRF) | `{ "credential": string }` | 200 `MeDto` + session cookie | 400 `auth/invalid-token`; 403 `auth/non-dlsu-account`; 429 |
| POST | `/api/auth/logout` | session | – | 204, session invalidated, cookie cleared | – |
| GET | `/api/me` | session | – | 200 `MeDto` | 401 `auth/unauthenticated` |

`MeDto = { id: string(uuid), email: string, displayName: string, unitCap: int|null, theme: "SYSTEM"|"LIGHT"|"DARK", hasCurriculum: boolean }`
(`hasCurriculum` is `false` until RFC-005 adds the table; RFC-005 makes it real.)

`auth/non-dlsu-account` detail text: "Anevaino is only for De La Salle University accounts. Please sign in with your @dlsu.edu.ph Google account."

## 5. Data model

`V1__create_spring_session.sql` — Spring Session JDBC PostgreSQL schema.
`V2__create_app_user.sql`:
```sql
CREATE TABLE app_user (
  id            UUID PRIMARY KEY,
  email         TEXT NOT NULL UNIQUE,
  display_name  TEXT NOT NULL,
  unit_cap      INTEGER NULL CHECK (unit_cap IS NULL OR unit_cap > 0),
  theme         TEXT NOT NULL DEFAULT 'SYSTEM' CHECK (theme IN ('SYSTEM','LIGHT','DARK')),
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
```
Table is `app_user`, not `user`: `user` is a reserved word in PostgreSQL. This is a deliberate deviation from R-2.10's wording ("from `user`") — flag for RULES correction.

## 6. Implementation details

### File structure
```
backend/src/main/resources/db/migration/V1__create_spring_session.sql
backend/src/main/resources/db/migration/V2__create_app_user.sql
backend/src/main/kotlin/ph/anevaino/auth/SecurityConfig.kt
backend/src/main/kotlin/ph/anevaino/auth/AuthController.kt
backend/src/main/kotlin/ph/anevaino/auth/GoogleIdTokenVerifier.kt
backend/src/main/kotlin/ph/anevaino/auth/DlsuDomainPolicy.kt
backend/src/main/kotlin/ph/anevaino/auth/CurrentUser.kt
backend/src/main/kotlin/ph/anevaino/auth/CurrentUserArgumentResolver.kt
backend/src/main/kotlin/ph/anevaino/user/User.kt
backend/src/main/kotlin/ph/anevaino/user/UserRepository.kt
backend/src/main/kotlin/ph/anevaino/user/UserService.kt
backend/src/main/kotlin/ph/anevaino/user/MeController.kt
backend/src/main/kotlin/ph/anevaino/user/MeDto.kt
backend/src/main/kotlin/ph/anevaino/common/ratelimit/TokenBucketRateLimiter.kt
backend/src/test/kotlin/ph/anevaino/auth/DlsuDomainPolicyTest.kt
backend/src/test/kotlin/ph/anevaino/auth/AuthControllerIntegrationTest.kt
backend/src/test/kotlin/ph/anevaino/common/TokenBucketRateLimiterTest.kt
frontend/src/features/auth/LandingPage.tsx
frontend/src/features/auth/GoogleSignInButton.tsx
frontend/src/features/auth/RequireAuth.tsx
frontend/src/features/auth/useMe.ts
frontend/src/features/auth/useSignOut.ts
frontend/src/api/schemas/me.ts
frontend/src/features/auth/LandingPage.test.tsx
frontend/e2e/auth.spec.ts
```

### UI
- **Landing (`/`)**: product name, one-sentence pitch, the GIS "Sign in with Google" button (`@react-oauth/google`, `hosted_domain` hint `dlsu.edu.ph` — a hint only, not the check), the "not affiliated with DLSU" line (full note lands in RFC-015), link to `/privacy`. On `auth/non-dlsu-account`, show the detail text in an `role="alert"` region under the button.
- After success: navigate to `/map` if `hasCurriculum` else `/upload`.
- `RequireAuth` wraps `/upload`, `/review`, `/map`, `/settings`; on 401 redirects to `/`.
- Header (shared layout) gets a "Sign out" button → `POST /api/auth/logout` → clear TanStack Query cache → `/`.

### Error handling / logging
`INFO login.success userId=…`; `WARN login.rejected reason=NON_DLSU|INVALID_TOKEN` — never the email (R-4.8).

### Testing
- `DlsuDomainPolicyTest`: table of cases — valid; `email_verified=false`; `@dlsu.edu.ph.evil.com`; `@sub.dlsu.edu.ph`; uppercase `@DLSU.EDU.PH` with correct `hd` (allowed); missing `hd`; `hd` mismatched.
- `AuthControllerIntegrationTest` (Testcontainers, verifier stubbed with `@MockkBean`): valid DLSU token → 200, one `app_user` row, cookie flags `HttpOnly`, `Secure`, `SameSite=Lax`; non-DLSU token → 403 and **zero** rows; forged request with no token → 400; logout → subsequent `/api/me` 401; POST without CSRF header → 403; 11th login in a minute → 429.
- E2E `auth.spec.ts`: backend auth route mocked; rejection message visible; signed-in redirect.

## 7. Considerations

- **Rules:** R-2.15–R-2.19, R-2.22, R-2.24, R-2.26, R-4.8.
- **Security:** never trust the `hosted_domain` UI hint; verification happens only server-side (F2 "UI-only check is never enough").
- **Edge:** Google key rotation — `NimbusJwtDecoder` caches JWKS and refetches on unknown `kid`. Clock skew allowance 60 s.
- **Display name:** from `name` claim; fall back to local part of email when absent.
- **Env vars:** `GOOGLE_CLIENT_ID` (backend), `VITE_GOOGLE_CLIENT_ID` (frontend).

## 8. Acceptance criteria

- **AC-002.1 [F1]** Clicking "Sign in with Google" on `/` and completing Google's flow with a DLSU account results in a `POST /api/auth/google`, a 200 `MeDto`, and navigation to `/upload` (no curriculum) — verified by E2E with the auth endpoint mocked and by the backend integration test.
- **AC-002.2 [F1]** A first login creates exactly one `app_user` row with `email`, `display_name`, `unit_cap = NULL`, `theme = 'SYSTEM'`; a second login with the same email reuses it.
- **AC-002.3 [F1]** The backend verifies ID-token signature, `aud == GOOGLE_CLIENT_ID`, `iss` ∈ Google issuers and expiry; any failure → 400 `auth/invalid-token`.
- **AC-002.4 [F2]** `DlsuDomainPolicy` allows a login only when `email_verified` is true, the lowercased email ends with `@dlsu.edu.ph`, and `hd == "dlsu.edu.ph"`; every case in §6 Testing is a passing named test.
- **AC-002.5 [F2]** A non-DLSU login returns 403 `auth/non-dlsu-account` with the §4 detail text, creates no `app_user` row, and the landing page shows that text in an alert region.
- **AC-002.6 [F1]** The session cookie is `HttpOnly; Secure; SameSite=Lax`, stored via Spring Session JDBC (tables from `V1__create_spring_session.sql`), and the session id rotates on login.
- **AC-002.7 [F1]** CSRF protection is on: a state-changing request without a valid `X-XSRF-TOKEN` header returns 403; `api/client.ts` sends the header automatically.
- **AC-002.8 [F3]** `POST /api/auth/logout` invalidates the server session; afterwards `GET /api/me` returns 401 and the UI is on `/` with the query cache cleared.
- **AC-002.9 [F44]** `CurrentUserArgumentResolver` supplies the authenticated user id to controllers; any `/api/**` endpoint other than health, csrf, auth/google and (later) the public share endpoint returns 401 when unauthenticated.
- **AC-002.10 [F44]** Login is rate-limited to 10 requests/minute per client IP; the 11th returns 429 `common/rate-limited` with `Retry-After`.
- **AC-002.11** `RequireAuth` redirects unauthenticated visits to `/upload`, `/review`, `/map`, `/settings` to `/`; the `/` route placeholder from RFC-001 is replaced by `LandingPage`.
- **AC-002.12** Log lines for login success/rejection contain the user id or rejection reason and never the email address.
- **AC-002.13** The table is named `app_user` (created by `V2__create_app_user.sql` with the §5 constraints) and the PR flags the R-2.10 naming deviation.
- **AC-002.14** Every file listed in §6 "File structure" exists, including the tests `DlsuDomainPolicyTest.kt`, `AuthControllerIntegrationTest.kt`, `TokenBucketRateLimiterTest.kt`, `LandingPage.test.tsx`, `auth.spec.ts`, and the frontend modules `GoogleSignInButton.tsx`, `useMe.ts`, `useSignOut.ts`, `schemas/me.ts`.
