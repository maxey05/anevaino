# RFC-015 — Account Deletion, Privacy Note and Non-Affiliation Disclaimer

| | |
|---|---|
| **Status** | Draft |
| **Complexity** | Medium |
| **Phase** | 6 — Share, privacy, a11y |
| **Features** | F4, F43 |
| **PRD** | FR-1.3 (deletion), NFR Privacy, §10 decision 8 |
| **Predecessors:** | RFC-011 |
| **Successors:** | RFC-017, RFC-018 |

Sections omitted: none.

## 1. Summary

Lets a student permanently delete their account and every row derived from it in one transaction (including sessions), proves with a schema-aware test that nothing remains, and adds the `/privacy` page following RA 10173's principles plus a site-wide "not affiliated with DLSU" footer. Declared after RFC-011 because the Settings page it extends is created there; all Must-have tables exist by then (share token columns arrive in RFC-017, which extends this RFC's test).

## 2. Interfaces

| Method | Path | Auth | Request | Success | Errors |
|---|---|---|---|---|---|
| DELETE | `/api/me` | session + CSRF | `{ "confirmEmail": string }` | 204, session cookie cleared | 400 `account/confirmation-mismatch` ("Type your email address exactly to confirm.") |

## 3. Technical approach

### 3.1 Deletion (F4, R-2.28)
```
AccountDeletionService.delete(userId, confirmEmail)   @Transactional
  user = userRepository.findById(userId) (from session)       // own row only
  if confirmEmail.trim().lowercase() != user.email → 400
  jdbc: DELETE FROM spring_session WHERE principal_name = :userId      // all devices
  userRepository.delete(user)                          // FK ON DELETE CASCADE: curriculum → course → prerequisite, attempt
  after commit: invalidate current HttpSession, clear SESSION cookie
  log INFO account.deleted userId=…
```
The session principal name is the user id (RFC-002), so all of a user's sessions are removable by one statement. Nothing is soft-deleted, archived or retained.

### 3.2 Zero-rows test
`AccountDeletionIntegrationTest` seeds two users with curricula, prerequisites, attempts and sessions, deletes user A, then:
1. For each table in `information_schema.tables` (schema `public`, excluding `flyway_schema_history`), runs an ownership query from `OwnedTables.kt` — a map `table → SQL counting rows belonging to user A` (e.g. `attempt` via `course → curriculum.user_id`; `spring_session` via `principal_name`; `spring_session_attributes` via its session FK). Every count must be 0.
2. **Fails if a table exists that isn't in the map** — so any future table (e.g. RFC-017's share columns live on `app_user`, but a new table would trip this) must declare how it's cleaned up.
3. User B's rows are untouched.

### 3.3 Settings UI
`DeleteAccountSection` on `/settings` in a visually separated "Danger zone": explanatory text "This permanently deletes your account, your curriculum, grades, professors and share link. It can't be undone." → button "Delete account" → `<dialog>` with an email input and "Delete permanently" (disabled until the typed email matches, case-insensitive) → on 204 clear the query cache and go to `/` with a status message "Your account has been deleted."

### 3.4 Privacy note (F43)
`/privacy` (public, no auth) with sections:
- **What we store:** your DLSU email and name from Google; your curriculum (courses, units, prerequisites); course states; grades, professor names and attempt history you enter; your unit cap and theme; a login session.
- **What we don't keep:** the uploaded PDF is read in memory and discarded immediately after parsing, whether or not it parses; no analytics or trackers.
- **Who can see it:** only you. A share link, if you create one, shows your course map, course states and progress bar — never grades, CGPA or professors.
- **Your choices:** edit anything at any time; delete your account from Settings, which permanently removes all of the above.
- **Principles:** a short paragraph that the app follows the Data Privacy Act of 2012 (RA 10173) principles of transparency, legitimate purpose and proportionality — collecting only what the features need.
- **Contact:** the address in `VITE_PRIVACY_CONTACT` (build fails if unset — no placeholder address, R-6.3).
- **Not affiliated:** "Anevaino is an independent student project. It is not affiliated with, endorsed by, or an official system of De La Salle University."

Copy is plain language. It is a good-faith note, not legal advice; Matthew may want someone familiar with RA 10173 to read it before launch.

### 3.5 Site-wide disclaimer
`SiteFooter` on every route (including `/s/:token` later): "Independent student project — not affiliated with De La Salle University." + link to `/privacy`. No DLSU logos, seals or official colours used as branding anywhere (checked by review; assets folder contains only self-drawn art).

### 3.6 No analytics (F43, R-2.27)
`noThirdParty.spec.ts` loads every route and asserts all network requests go to the app's own origin (plus Google Identity Services on `/`, required for sign-in).

## 4. Implementation details

### File structure
```
backend/src/main/kotlin/ph/anevaino/user/AccountDeletionController.kt
backend/src/main/kotlin/ph/anevaino/user/AccountDeletionService.kt
backend/src/main/kotlin/ph/anevaino/user/DeleteAccountRequest.kt
backend/src/test/kotlin/ph/anevaino/user/AccountDeletionIntegrationTest.kt
backend/src/test/kotlin/ph/anevaino/user/OwnedTables.kt
frontend/src/api/account.ts
frontend/src/features/settings/DeleteAccountSection.tsx
frontend/src/features/settings/DeleteAccountSection.test.tsx
frontend/src/features/privacy/PrivacyPage.tsx
frontend/src/components/SiteFooter.tsx
frontend/e2e/account-deletion.spec.ts
frontend/e2e/noThirdParty.spec.ts
shared/problem-types.json   (account/confirmation-mismatch)
.env.example                (VITE_PRIVACY_CONTACT)
```

### Testing
- `AccountDeletionIntegrationTest` (§3.2), plus: wrong email → 400 and nothing deleted; unauthenticated → 401; after deletion the old session cookie → 401 on `/api/me`; a second session for the same user (another device) is also dead.
- `DeleteAccountSection.test.tsx`: button disabled until email matches; success navigates and shows status; error shown.
- `account-deletion.spec.ts`: settings → delete → landing with message; axe clean on `/privacy`.
- `noThirdParty.spec.ts` (§3.6).

## 5. Considerations

- **Rules:** R-2.19, R-2.26, R-2.27, R-2.28, R-4.3, R-4.12, R-6.1, R-6.3.
- **Edge:** the user deletes while an upload parse is in flight — the parse result has nowhere to save (save requires a session) and is discarded.
- **Logging:** only the user id; never email (R-4.8).

## 6. Acceptance criteria

- **AC-015.1 [F4]** `/settings` has a Danger-zone "Delete account" action with a confirmation dialog requiring the user's email; the backend rejects a mismatched confirmation with 400 `account/confirmation-mismatch` and deletes nothing.
- **AC-015.2 [F4]** A confirmed `DELETE /api/me` removes the `app_user` row and, by cascade, every curriculum, course, prerequisite and attempt row, plus all of the user's `spring_session` rows, in one transaction — a hard delete.
- **AC-015.3 [F4]** `AccountDeletionIntegrationTest` asserts zero rows remain for the deleted user in every table in `public` (except `flyway_schema_history`), fails if any table lacks an `OwnedTables` entry, and confirms another user's data is untouched.
- **AC-015.4 [F4]** After deletion, every session of that user (current and other devices) returns 401, and the UI lands on `/` with "Your account has been deleted."
- **AC-015.5 [F43]** `/privacy` is public and contains the §3.4 sections: what is stored, that PDFs aren't kept, no analytics, who can see data (incl. share-link limits), how to delete, RA 10173 principles, the contact from `VITE_PRIVACY_CONTACT`, and the non-affiliation statement; the build fails if the contact is unset.
- **AC-015.6 [F43]** Every route shows `SiteFooter` with the non-affiliation line and a privacy link; no DLSU marks are used.
- **AC-015.7 [F43]** `noThirdParty.spec.ts` confirms no third-party requests other than Google Identity Services on the sign-in page.
- **AC-015.8** The `/privacy` placeholder from RFC-001 is gone; axe reports no serious/critical violations on `/privacy` and `/settings`.
- **AC-015.9** Every file listed in §4 "File structure" exists, including `DeleteAccountRequest.kt`, `OwnedTables.kt`, `api/account.ts`, `DeleteAccountSection.test.tsx`, `SiteFooter.tsx`, the new problem slug and the `VITE_PRIVACY_CONTACT` entry in `.env.example`.
