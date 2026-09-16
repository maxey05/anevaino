# RFC-017 — Read-Only Share Link

| | |
|---|---|
| **Status** | Draft |
| **Complexity** | Medium |
| **Phase** | 6 — Share, privacy, a11y (Should-have; start after RFC-014/015 per R-5.1) |
| **Features** | F41, F42, F4 (share-token part), F44 (public endpoint hardening) |
| **PRD** | FR-9.1, FR-9.2, J5, NFR Privacy |
| **Predecessors:** | RFC-012, RFC-015 |
| **Successors:** | RFC-018 |

Sections omitted: none.

## 1. Summary

A student can create, copy, regenerate and revoke one unguessable read-only link. Anyone with the link sees the quest map, course states and progress bar through a **separate public DTO that has no fields at all** for grades, CGPA, professors or attempts (R-2.14). Tokens are stored only as SHA-256 hashes (R-2.21), so the full link is shown once at creation.

## 2. Data model — `V5__add_share_token.sql`

```sql
ALTER TABLE app_user ADD COLUMN share_token_hash BYTEA NULL UNIQUE;
ALTER TABLE app_user ADD COLUMN share_token_created_at TIMESTAMPTZ NULL;
ALTER TABLE app_user ADD CONSTRAINT share_token_pair CHECK ((share_token_hash IS NULL) = (share_token_created_at IS NULL));
```
**Contradiction handled:** PRD §6.2 has `User.shareToken`; RULES R-2.21 requires storing only a hash. Followed RULES (engineering convention, and it doesn't change product behaviour except that the link can't be re-displayed later). Flagged as RFCS.md C6.

## 3. Interfaces

| Method | Path | Auth | Success | Errors |
|---|---|---|---|---|
| GET | `/api/me/share-link` | session | 200 `{ active: boolean, createdAt: string \| null }` | – |
| POST | `/api/me/share-link` | session + CSRF | 201 `{ url: string, createdAt }` (replaces any existing token) | 404 if no curriculum |
| DELETE | `/api/me/share-link` | session + CSRF | 204 (idempotent) | – |
| GET | `/api/shared/{token}` | **none** | 200 `ShareViewDto`, headers `Cache-Control: no-store`, `X-Robots-Tag: noindex` | 404 `share/not-found` ("This share link doesn't exist or was turned off."); 429 |

```kotlin
// share/ShareViewDto.kt — its own type, never a nulled-out owner DTO (R-2.14)
data class ShareViewDto(
  val displayName: String, val programLabel: String?,
  val totalUnits: Int, val totalExtraUnits: Int,
  val courses: List<SharedCourse>, val prerequisites: List<SharedPrerequisite>,
  val progress: SharedProgress)
data class SharedCourse(val id: String, val code: String, val title: String, val units: Int, val extraUnits: Int,
  val year: Int, val term: Int, val slotType: SlotType, val slotLabel: String?, val state: CourseState)
data class SharedPrerequisite(val courseId: String, val requiresCourseId: String, val type: PrerequisiteType)
data class SharedProgress(val fillBasisPoints: Int, val earnedUnits: Int, val requiredUnits: Int)
```
Not included, by design: email, unit cap/load, CGPA, grades, professors, attempts, exemption notes (data minimisation).

## 4. Technical approach

- **Create:** 32 bytes from `SecureRandom` → base64url without padding (43 chars) → store `SHA-256(raw bytes)` + `now()`. URL = `${APP_BASE_URL}/s/${token}`. Returned once; `GET /api/me/share-link` never returns it.
- **Lookup:** decode token (invalid base64url or length ≠ 32 bytes → 404 without DB hit) → hash → `userRepository.findByShareTokenHash` → load curriculum via `MapQueryService`'s queries minus attempts → map to `ShareViewDto` (progress via RFC-007's calculator).
- **Revoke:** set both columns null; subsequent lookups 404 immediately (no caching anywhere).
- **Account deletion (F4):** the columns live on `app_user`, so RFC-015's hard delete removes them; `AccountDeletionIntegrationTest` gains "a previously valid share token returns 404 after deletion".
- **Rate limit:** 60 requests/minute per IP on `/api/shared/**` (R-2.22).
- **Security config:** `/api/shared/**` permitted without auth and without CSRF (GET only); `Referrer-Policy: no-referrer` already global (RFC-001).
- **Logging:** `INFO share.viewed ownerId=…` (never the token or its hash); `INFO share.created|revoked userId=…`.

### Frontend
- `/s/:token` → `SharedMapPage` (lazy, no auth, never calls `/api/me`): heading "{displayName}'s quest map" + programme label, `ProgressBar`, `CourseGraph` with `SharedCourseNode` (state visuals from RFC-011, **no** eligibility badges or actions) and `BiomeBackground`, a "List" tab rendering `CourseListView` in `readOnly` mode (no actions column), `SiteFooter`, and `<meta name="robots" content="noindex">`. 404 → "This share link doesn't exist or was turned off."
- `/settings` → `ShareLinkSection`: inactive → "Create share link" + explanation "People with the link can see your map, course states and progress — never your grades, CGPA or professors." Active → after creation, the URL in a read-only input with "Copy link" (Clipboard API, fallback select-all) and a note "Copy it now — for your privacy we can't show this link again."; later visits show "Share link active since {date}" with "Create new link" (confirm: "The old link will stop working.") and "Turn off link".
- `CourseListView` gains a `readOnly` prop (hides the actions column and details actions).

## 5. Implementation details

### File structure
```
backend/src/main/resources/db/migration/V5__add_share_token.sql
backend/src/main/kotlin/ph/anevaino/share/ShareTokenService.kt
backend/src/main/kotlin/ph/anevaino/share/ShareLinkController.kt
backend/src/main/kotlin/ph/anevaino/share/SharedViewController.kt
backend/src/main/kotlin/ph/anevaino/share/ShareViewDto.kt
backend/src/main/kotlin/ph/anevaino/share/ShareViewMapper.kt
backend/src/test/kotlin/ph/anevaino/share/ShareTokenServiceTest.kt
backend/src/test/kotlin/ph/anevaino/share/ShareViewDtoShapeTest.kt
backend/src/test/kotlin/ph/anevaino/share/ShareIntegrationTest.kt
backend/src/test/kotlin/ph/anevaino/user/AccountDeletionIntegrationTest.kt   (share-token case)
frontend/src/api/share.ts
frontend/src/api/schemas/shareView.ts
frontend/src/features/share/SharedMapPage.tsx
frontend/src/features/share/SharedCourseNode.tsx
frontend/src/features/settings/ShareLinkSection.tsx
frontend/src/features/map/CourseListView.tsx                                   (readOnly prop)
frontend/src/features/share/SharedMapPage.test.tsx
frontend/src/features/settings/ShareLinkSection.test.tsx
frontend/e2e/j5-sharing.spec.ts
shared/problem-types.json   (share/not-found)
.env.example                (APP_BASE_URL)
```

### Testing
- `ShareTokenServiceTest`: 32 random bytes; base64url 43 chars; only the hash is persisted; regenerate replaces hash; malformed tokens rejected before DB.
- `ShareViewDtoShapeTest`: reflection over `ShareViewDto` and nested types — fails if any property name matches `/grade|cgpa|gpa|professor|attempt|email|exemption/i`.
- `ShareIntegrationTest`: owner with grades/professors/attempts → public JSON (raw string) contains none of the grade values' keys, professor names or `attempts`; revoke → 404 immediately; regenerate → old 404, new 200; no auth required; headers `no-store` + `noindex`; 61st request/min → 429; token absent from captured logs.
- `SharedMapPage.test.tsx`: renders states and progress; no eligibility badges, action buttons, CGPA or load counter; 404 message.
- `ShareLinkSection.test.tsx`: create shows URL + copy note; later shows active-since; regenerate confirm; revoke.
- `j5-sharing.spec.ts` (J5): settings → create → copy → open link in a fresh context (no cookies) → map + progress visible, no "CGPA" text → owner revokes → link shows not-found. Axe clean on `/s/:token`.

## 6. Considerations

- **Rules:** R-2.14, R-2.19, R-2.21, R-2.22, R-2.23, R-2.26, R-4.8, R-6.1.
- **Threat model:** 256-bit tokens (≥ 128 bits required by F41) aren't guessable; rate limiting blunts scraping; `no-referrer` keeps the token out of outbound Referer headers.
- **Edge:** owner replaces their curriculum (RFC-009 Q8) — the link keeps working and shows the new curriculum; owner has no curriculum → public 404.

## 7. Acceptance criteria

- **AC-017.1 [F41]** `V5__add_share_token.sql` adds `share_token_hash` (unique) and `share_token_created_at` with the pair CHECK; only the SHA-256 hash of a 32-byte `SecureRandom` token is stored.
- **AC-017.2 [F41]** The owner can create a link (URL shown once with a copy button and the "copy it now" note), see that a link is active and since when, regenerate it (old link 404 immediately), and revoke it (link 404 immediately).
- **AC-017.3 [F42]** `GET /api/shared/{token}` needs no auth and returns `ShareViewDto`, a separate type with no grade, CGPA, professor, attempt, email or exemption fields (`ShareViewDtoShapeTest`), and the raw response for a graded owner contains none of that data.
- **AC-017.4 [F42]** `/s/:token` shows the owner's display name, programme label, quest map with course states and biomes, progress bar and a read-only list view — no eligibility, actions, CGPA or load — with `noindex`.
- **AC-017.5 [F44]** The public endpoint is rate-limited to 60/min per IP, sends `Cache-Control: no-store` and `X-Robots-Tag: noindex`, rejects malformed tokens without a DB query, and never logs the token or hash.
- **AC-017.6 [F4]** After account deletion a previously valid share token returns 404 (added to `AccountDeletionIntegrationTest`).
- **AC-017.7** `j5-sharing.spec.ts` passes (J5), axe reports no serious/critical violations on `/s/:token`, and the `/s/:token` placeholder from RFC-001 is gone.
- **AC-017.8** Every file listed in §5 "File structure" exists or is updated as noted, including `ShareTokenService.kt`, `ShareLinkController.kt`, `SharedViewController.kt`, `ShareViewMapper.kt`, `api/share.ts`, `api/schemas/shareView.ts`, `SharedCourseNode.tsx`, both frontend tests, the `share/not-found` slug and `APP_BASE_URL` in `.env.example`.
