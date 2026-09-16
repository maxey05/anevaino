# FEATURES — Anevaino: DLSU Flowchart Quest Map

| | |
|---|---|
| **Source** | `PRD.md` Draft v3 (2026-09-16) — parser architecture, CGPA formula and minor-program scope finalized |
| **Generated** | 2026-09-16 |
| **ID policy** | IDs are permanent. New features take the next unused number; removed features are marked `[REMOVED]`, never deleted or renumbered. |

## Product overview

Anevaino is a free, account-based web app (React + TypeScript frontend, Kotlin + Spring Boot backend, Postgres on Neon) for DLSU students. A student signs in with a `@dlsu.edu.ph` Google account and uploads the official curriculum flowchart PDF. One universal parser reads the PDF's "Program Checklist" table into courses, units and typed prerequisites. The student reviews and fixes the result, then gets a left-to-right course graph with game styling: one biome per year and a pixel-art hero on a progress bar. On that graph the student tracks each course through a 7-state lifecycle. The app enforces hard/co-requisite rules and a unit cap, logs grades and professors per attempt, and computes a units-weighted CGPA. A read-only share link hides all grade data. The biggest technical risk is betting on one parser for every college (PRD §9, §10 decision 9). A parse-confidence check and the manual editor are the safety net.

**Personas:** **P1** Planner Pia (2nd year, eligibility planning), **P2** Freshman Franco (1st year, motivated by progress), **P3** Senior Sam (4th year, remaining units + sharing).

**Priority mapping from PRD:** P0 → Must, P1 → Should, P2 → Could, §3 "Out of scope" → Won't.

---

## Summary

### By priority

| Priority | Count |
|---|---|
| Must have | 38 |
| Should have | 5 |
| Could have | 1 |
| Won't have (this release) | 10 |
| **Total** | **54** |

### By category

| Category | IDs | Must | Should | Could | Won't | Total |
|---|---|---|---|---|---|---|
| A. Authentication & Account | F1–F4 | 4 | 0 | 0 | 0 | 4 |
| B. Upload & Parsing | F5–F15 | 10 | 1 | 0 | 0 | 11 |
| C. Review & Manual Edit | F16–F20 | 4 | 1 | 0 | 0 | 5 |
| D. Course States & Rules | F21–F29 | 8 | 1 | 0 | 0 | 9 |
| E. Grades & CGPA | F30–F32 | 3 | 0 | 0 | 0 | 3 |
| F. Elective Slots | F33 | 1 | 0 | 0 | 0 | 1 |
| G. Graph, Game & UI | F34–F40 | 6 | 0 | 1 | 0 | 7 |
| H. Sharing, Privacy & Security | F41–F44 | 2 | 2 | 0 | 0 | 4 |
| I. Out of scope | F45–F54 | 0 | 0 | 0 | 10 | 10 |
| **Total** | | **38** | **5** | **1** | **10** | **54** |

### By complexity (in-scope features F1–F44 only)

| Complexity | Count | IDs |
|---|---|---|
| High | 4 | F7, F8, F29, F34 |
| Medium | 23 | F1, F4, F9, F12, F13, F15, F16, F17, F18, F20, F21, F22, F23, F26, F27, F28, F30, F32, F33, F35, F39, F41, F44 |
| Low | 17 | F2, F3, F5, F6, F10, F11, F14, F19, F24, F25, F31, F36, F37, F38, F40, F42, F43 |

### Features that need third-party integration or special expertise

- **Google OAuth / OIDC** — F1, F2 (Google Cloud OAuth client, Spring Security)
- **Apache PDFBox positional text extraction** — F7, F8, F9 (the hardest technical work in the project)
- **React Flow + dagre/ELK auto layout** — F34, F35
- **Free-tier hosting constraints (Render cold starts, Neon limits)** — F40, F44
- **Pixel art (self-drawn)** — F35, F36

### Highest-risk features

1. **F7 Column-aware checklist extraction.** Two side-by-side term blocks per page interleave under naive extraction (FR-2.6).
2. **F13 Parse-confidence check + F15 multi-college fixtures.** These are the only real protection for the one-parser bet. Tune the thresholds against real fixtures, not guesses.
3. **F29 Shared rules engine.** The same rules must give identical results on the backend (authoritative) and the frontend (instant feedback).

---

## Recommended build order (dependency view)

```
F1 → F2 → F3/F4
F5 → F7 → F8 → F9/F10/F11 → F12/F13 → F15
F8 + F9 → F16 → F17 → F18 → F19 → (F20)
F18 → F21 → F29 → F22/F23/F24/F25/F26 → F27/F28
F21 → F30 → F31 → F32
F21 + F17 → F33
F16 → F34 → F35/F36/F37/F39 → F38
F34 → F41 → F42
F44 applies to every endpoint from F1 onward
```

This matches PRD §9: foundations (wk 1–2), parser (3–5), review/edit (6–7), graph/rules (8–9), fixtures + game layer (10–11), sharing/privacy/a11y (12).

---

## A. Authentication & Account

### F1 — Google sign-in
**Priority:** Must · **Complexity:** Medium · **Personas:** All · **PRD:** FR-1.1, §6.1

The user signs in with Google OAuth 2.0 / OpenID Connect.

**Acceptance criteria**
- A "Sign in with Google" button starts the OIDC flow and, on success, creates or loads the `User` record.
- A session or stateless token strategy is chosen, with CSRF handled to match (NFR Security).
- A first login creates `User(email, displayName)`. `unitCap` starts empty and `theme` starts as system.

**Technical notes:** Spring Security OAuth2 client/resource server. The frontend (Vercel/Cloudflare) and backend (Render) are on different origins, so CORS and cookie `SameSite` settings must be decided early.

### F2 — DLSU domain restriction (backend-enforced)
**Priority:** Must · **Complexity:** Low · **Personas:** All · **PRD:** FR-1.2, J1.1

**Acceptance criteria**
- The backend verifies the ID token and requires `email_verified`, an `email` ending in `@dlsu.edu.ph`, and `hd == "dlsu.edu.ph"`.
- A non-DLSU account gets a clear rejection message and no `User` row is created.
- A UI-only check is never enough: a forged request to the API is rejected.

**Edge cases:** subdomains or look-alike domains (for example `dlsu.edu.ph.evil.com`). Use an exact suffix match on `@dlsu.edu.ph` plus the `hd` claim.

### F3 — Sign-out
**Priority:** Must · **Complexity:** Low · **Personas:** All · **PRD:** FR-1.3

**Acceptance criteria:** Signing out invalidates the session/token and returns to the landing page. Protected API calls then return 401.

### F4 — Account deletion (hard delete)
**Priority:** Must · **Complexity:** Medium · **Personas:** All · **PRD:** FR-1.3, NFR Privacy

**Acceptance criteria**
- Settings has a "Delete account" action with a confirmation step.
- Deleting removes the `User` and every `Curriculum`, `Course`, `Prerequisite` and `Attempt` row. It is a real delete, not a soft delete.
- Any active share token stops working immediately.

**Technical notes:** use cascading deletes in the schema, and add a test that checks no orphan rows remain.

---

## B. Upload & Parsing

### F5 — PDF upload with validation
**Priority:** Must · **Complexity:** Low · **Personas:** All · **PRD:** FR-2.2, NFR Security

**Acceptance criteria**
- Accepts `application/pdf` files up to 10 MB. Anything larger or of another type is rejected before parsing.
- A PDF with no extractable text shows: "This looks like a scanned PDF, which isn't supported yet."
- Parsing runs in memory with a timeout. When the timeout hits, the user sees an error and the server stays healthy.

**Edge cases:** an encrypted or password-protected PDF, a PDF with a text layer but no checklist (→ F11), and a corrupt file.

### F6 — Optional program label
**Priority:** Should · **Complexity:** Low · **Personas:** All · **PRD:** FR-2.1, §6.2 `Curriculum.programLabel`

**Acceptance criteria:** Before upload, the user can type a program name or pick a known DLSU program. It is stored as `programLabel` and shown only as display text. It **never** affects which parser runs.

### F7 — Column-aware checklist-table extraction
**Priority:** Must · **Complexity:** High · **Personas:** All · **PRD:** FR-2.3, FR-2.5, FR-2.6

A single universal parser finds the Program Checklist table and reads its rows correctly, even though each page has two side-by-side term blocks.

**Acceptance criteria**
- Text is grouped by x-position band first, then read top to bottom within each band (PDFBox `PDFTextStripperByArea` or a custom x-then-y sort).
- Rows from the two term blocks never interleave. The CCS BSCS-NIS fixture parses in the correct order.
- One implementation serves every college. There is no per-college selection logic.
- A typical checklist (≤ 80 courses) parses in under 5 s server-side (NFR Performance).

**Technical notes:** column boundaries should be detected, not hard-coded to the CCS sample's coordinates. Otherwise the "universal" parser is really a CCS parser.

### F8 — Course field extraction
**Priority:** Must · **Complexity:** High · **Personas:** All · **PRD:** FR-2.8, §6.2 `Course`, `Curriculum`

**Acceptance criteria**
- For each course the parser extracts code, title, units, parenthetical `extraUnits` (for example `NSTP-01 (3)`), year, term and `slotType` (`NONE | MANDATORY_ELECTIVE`).
- It extracts the curriculum's `totalUnits` and `totalExtraUnits` from the printed total (for example `176 (9)`).
- Mandatory elective slots (for example `NSELEC1-3`, `GE Elective`) come out as `MANDATORY_ELECTIVE`.
- Year/term assignment assumes a trimester system (3 terms per year) (§10 Assumptions).

**Edge cases:** multi-line course titles, units printed as `0 (3)`, and a term with no courses.

### F9 — Prerequisite marker parsing (H/S/C/E)
**Priority:** Must · **Complexity:** Medium · **Personas:** All · **PRD:** FR-2.7

**Acceptance criteria**
- `(H)`, `(S)` and `(C)` codes become `Prerequisite` rows of type `HARD`, `SOFT` and `COREQ`.
- `(E) CODE` is stored as `Course.exemptionNote`. It creates **no** edge and does not have to match a course.
- A cell listing several prerequisites of mixed types is split correctly.

### F10 — Skip Minor Program placeholders
**Priority:** Must · **Complexity:** Low · **Personas:** All · **PRD:** FR-2.8, FR-6.2, §3

**Acceptance criteria:** Codes like `MINOR01`–`MINOR04` produce no `Course` rows, never count toward required units, and never show up as dangling-prerequisite warnings.

### F11 — Reject PDFs with no checklist table
**Priority:** Must · **Complexity:** Low · **Personas:** All · **PRD:** FR-2.4

**Acceptance criteria:** If no checklist table is found, the upload is rejected with a clear message that points the user to building the graph by hand in the manual editor (F17). The parser does not try to read diagram arrows (out of scope → F50).

### F12 — Parse warnings
**Priority:** Must · **Complexity:** Medium · **Personas:** All · **PRD:** FR-2.9

**Acceptance criteria:** The review screen lists unreadable rows and H/S/C prerequisite codes that match no extracted course, each linked to the affected row. `(E)` exemptions never trigger this warning.

### F13 — Parse-confidence check
**Priority:** Must · **Complexity:** Medium · **Personas:** All · **PRD:** FR-2.11, §6.2 `Curriculum.parseConfidence`

**Acceptance criteria**
- The parse is `LOW_CONFIDENCE` if the extracted units per term don't roughly match the flowchart's printed per-term totals, **or** any H/S/C code (excluding `(E)`) fails to resolve.
- Otherwise it is `NORMAL`. The value is stored on `Curriculum.parseConfidence`.
- The tolerance is configurable, not hard-coded.

**Technical notes:** start with loose thresholds and tune them against real fixtures (PRD-REVIEW round 2, rec. 2). "Roughly match" still needs a number. Set it during weeks 10–11.

### F14 — Transient PDF storage
**Priority:** Must · **Complexity:** Low · **Personas:** All · **PRD:** FR-2.10, NFR Privacy

**Acceptance criteria:** The uploaded PDF is never written to durable storage or the database. It is discarded after parsing, whether the parse succeeds or fails. Only structured data is kept.

### F15 — Multi-college parser fixture suite
**Priority:** Must · **Complexity:** Medium · **Personas:** All · **PRD:** NFR Maintainability, G1, §9 wk 1–2 and 10–11

**Acceptance criteria**
- Saved sample PDFs from **every** supported college run in CI.
- Each college's sample reaches ≥ 90% correct courses and prerequisite links on its own (G1).
- There are dedicated fixtures for two-column extraction (F7) and for both outcomes of the confidence check (F13).

---

## C. Review & Manual Edit

### F16 — Review screen
**Priority:** Must · **Complexity:** Medium · **Personas:** All · **PRD:** FR-3.1, J1.3

**Acceptance criteria:** After parsing, and before anything is saved, the user sees an editable table and a graph preview of the parsed curriculum, with parse warnings (F12) shown inline.

### F17 — Course and prerequisite editing
**Priority:** Must · **Complexity:** Medium · **Personas:** All · **PRD:** FR-3.2, FR-2.4

**Acceptance criteria**
- The user can add, edit and delete courses: code, title, units, extra units, year, term and slot type.
- The user can add or remove prerequisite links and set their type (hard / soft / co-req), and can add, edit or clear an exemption note.
- The editor also works from an empty curriculum, for users whose PDF was rejected (F11).

### F18 — Save validation
**Priority:** Must · **Complexity:** Medium · **Personas:** All · **PRD:** FR-3.3

**Acceptance criteria**
- Saving is blocked on duplicate course codes, prerequisite cycles, or H/S/C links to non-existent courses. Each error points to the offending rows.
- Exemption notes are exempt from the reference check.
- The same validation runs on the backend.

**Edge case:** co-requisites are often mutual (A co-req B and B co-req A). A naive cycle check would reject that. See Open Question Q2.

### F19 — Low-confidence warning banner
**Priority:** Must · **Complexity:** Low · **Personas:** All · **PRD:** FR-3.5

**Acceptance criteria:** When `parseConfidence == LOW_CONFIDENCE`, a prominent banner reads: "This flowchart's layout looks different than expected — please double-check every row before saving." It looks clearly different from a normal successful parse.

### F20 — Reopen editor after saving
**Priority:** Should · **Complexity:** Medium · **Personas:** P1, P3 · **PRD:** FR-3.4

**Acceptance criteria:** The saved curriculum can be reopened in the editor, with validation (F18) run again on save.

**Edge cases:** deleting a course that has attempts or dependents in `BEING_TAKEN`. Warn before cascading. Editing prerequisites may make a course that is already `BEING_TAKEN` invalid. Warn rather than silently changing state, in line with F27.

---

## D. Course States & Rules

### F21 — Seven-state course lifecycle
**Priority:** Must · **Complexity:** Medium · **Personas:** All · **PRD:** FR-4 (state definitions), J3

**Acceptance criteria**
- States: `NOT_TAKEN`, `BEING_TAKEN`, `FINISHED`, `PASSED`, `FAILED`, `WITHDRAWN`, `INCOMPLETE`.
- Allowed transitions: `NOT_TAKEN → BEING_TAKEN`. `BEING_TAKEN → FINISHED | PASSED | FAILED | WITHDRAWN | INCOMPLETE`. `INCOMPLETE → FINISHED | PASSED | FAILED`. `FAILED | WITHDRAWN → BEING_TAKEN` (retake). Reverts covered by F27.
- Every other transition is rejected by the backend.
- `FINISHED` and `PASSED` satisfy prerequisites. `FAILED`, `WITHDRAWN` and `INCOMPLETE` do not.

### F22 — Hard prerequisite enforcement
**Priority:** Must · **Complexity:** Medium · **Personas:** P1 · **PRD:** FR-4 table, J2.3

**Acceptance criteria:** `→ BEING_TAKEN` is blocked unless every hard prerequisite is `FINISHED` or `PASSED`. The block message lists the unmet prerequisites (for example "Requires CCPROG2").

### F23 — Co-requisite enforcement
**Priority:** Must · **Complexity:** Medium · **Personas:** P1 · **PRD:** FR-4 table

**Acceptance criteria:** `→ BEING_TAKEN` is blocked unless each co-requisite is `FINISHED`/`PASSED` or already `BEING_TAKEN`. The unmet co-requisites are listed.

**Edge case:** with mutual co-requisites, neither course can be started first. The UI needs a way to start both at once (for example "Start together"), or the rule has to allow a paired start. See Q2.

### F24 — Soft prerequisite warning
**Priority:** Must · **Complexity:** Low · **Personas:** P1 · **PRD:** FR-4 table, J2.4

**Acceptance criteria:** If a soft prerequisite has no logged attempt (`FINISHED`, `PASSED`, `FAILED` or `WITHDRAWN`), a warning the user can dismiss appears and the user can go ahead. No warning appears once any such attempt exists.

### F25 — Exemption note badge
**Priority:** Must · **Complexity:** Low · **Personas:** P2 · **PRD:** FR-4 table, FR-2.7

**Acceptance criteria:** A course with an `exemptionNote` shows a badge on its detail panel. The note never blocks or warns.

### F26 — Unit cap (setting + enforcement)
**Priority:** Must · **Complexity:** Medium · **Personas:** P1 · **PRD:** FR-4 table, FR-7.1–7.3, J2.1/J2.5

**Acceptance criteria**
- The cap is set in settings. It defaults to empty (no cap).
- Load = sum of `units + extraUnits` across every `BEING_TAKEN` course. A load counter shows "X / Y units".
- A `→ BEING_TAKEN` action that would push load above the cap is blocked with the "X / Y units" message.
- Lowering the cap below the current load is allowed, but shows a warning and blocks new starts until the load fits.

### F27 — Downstream consistency warning
**Priority:** Should · **Complexity:** Medium · **Personas:** P1 · **PRD:** FR-4 table (marked P1)

**Acceptance criteria:** Reverting a `FINISHED`/`PASSED` course to `NOT_TAKEN`, `FAILED` or `WITHDRAWN` while dependents are `BEING_TAKEN` or later shows a warning listing the affected courses. Dependents are **never** changed automatically.

**Technical note:** F21's transition list has no path out of `FINISHED`/`PASSED`. This feature adds one (a revert/undo). See Q4.

### F28 — Node state & eligibility visuals
**Priority:** Must · **Complexity:** Medium · **Personas:** All · **PRD:** FR-4.1, NFR Accessibility

**Acceptance criteria:** Each node shows its state (7 states) and its eligibility (eligible / locked / warning) with **both** an icon or pattern and a color. Every combination still reads correctly in grayscale and passes WCAG 2.1 AA contrast in both themes.

### F29 — Shared rules engine (backend-authoritative, frontend-mirrored)
**Priority:** Must · **Complexity:** High · **Personas:** All · **PRD:** FR-4.2, G2, NFR Performance

**Acceptance criteria**
- The backend enforces every rule in F21–F26 on every state-change request, and an invalid state is impossible through the API (G2).
- The frontend evaluates the same rules for instant feedback. A state change shows locally in < 300 ms and the save is confirmed in < 1 s on a warm server.
- A shared set of rule test cases passes on both sides, so a frontend/backend mismatch fails CI.

**Technical notes:** the backend is Kotlin and the frontend is TypeScript, so the logic can't be shared as code. Share test vectors (JSON cases) instead.

---

## E. Grades & CGPA

### F30 — Grade and professor logging
**Priority:** Must · **Complexity:** Medium · **Personas:** P1, P3 · **PRD:** FR-5.1, FR-5.2, J3.1–J3.4

**Acceptance criteria**
- **Finish:** grade is required, from 4.0, 3.5, 3.0, 2.5, 2.0, 1.5, 1.0, 0.0. Professor is optional.
- **Mark passed** and **Withdraw:** professor only. No grade field is shown.
- **Fail:** grade defaults to 0.0, and professor is optional.
- Choosing grade 0.0 sets the state to `FAILED` automatically, and choosing `FAILED` implies 0.0.
- Every one of these actions creates an `Attempt` record.

### F31 — Attempt history
**Priority:** Must · **Complexity:** Low · **Personas:** P1 · **PRD:** FR-5.3

**Acceptance criteria:** Each attempt is stored separately with result, nullable grade, professor and `createdAt`. The course detail panel lists all attempts, including retakes, in order.

### F32 — CGPA calculation and header display
**Priority:** Must · **Complexity:** Medium · **Personas:** P1, P3 · **PRD:** FR-5.4, FR-5.5

**Acceptance criteria**
- CGPA = Σ(grade × units) ÷ Σ(units), using the **latest graded attempt (`FINISHED` or `FAILED`)** of each course.
- `PASSED`, `WITHDRAWN` and `INCOMPLETE` attempts are excluded.
- It is shown in the header next to the progress bar and updates right away on a state change.
- The worked example passes as a test. CCPROG1 (3u, 3.0) plus NSSECU1 failed (3u, 0.0) gives CGPA 1.5. After NSSECU1 is retaken with 2.5, CGPA is 2.75, and the 0.0 stays in the history.
- With no graded attempts, CGPA shows as "—", not 0.00.
- There is no per-term GPA (F46).

**Edge cases:** failed → retake → withdrawn. The latest *graded* attempt is still the 0.0, so CGPA stays pulled down, which matches "until retaken and passed". Also decide whether the unit weight is `units` only or `units + extraUnits`. See Q3.

---

## F. Elective Slots

### F33 — Mandatory elective slot fill-in
**Priority:** Must · **Complexity:** Medium · **Personas:** P2, P3 · **PRD:** FR-6.1, J4

**Acceptance criteria**
- `MANDATORY_ELECTIVE` nodes appear as slot nodes.
- Before or while starting the slot, the user must enter the real course code, title and units. The slot keeps its category label (for example "GE Elective").
- The entered units feed into the unit cap (F26), progress (F36) and CGPA (F32).

**Edge case:** the entered code duplicates an existing course code. Reject it using the F18 duplicate rule.

---

## G. Graph, Game & UI

### F34 — Left-to-right course graph
**Priority:** Must · **Complexity:** High · **Personas:** All · **PRD:** FR-8.1, §6.1

**Acceptance criteria**
- Columns are terms, grouped by year. Edges run from each prerequisite to its course, and the three edge types look different from each other.
- Pan, zoom and fit-to-screen all work, and the graph is usable with touch pan/zoom on phones.
- It stays smooth with ~80 nodes.

**Technical notes:** React Flow with dagre or ELK, with column (rank) placement fixed to term order rather than chosen freely by the layout engine.

### F35 — Year biome backgrounds
**Priority:** Must · **Complexity:** Medium · **Personas:** P2 · **PRD:** FR-8.2

**Acceptance criteria**
- Year 1 grassland + castle, Year 2 icy plains, Year 3 desert, a cloudy/sky biome for each year after Year 3 except the last, and the final year always lava + evil castle.
- 4-year program: grass → ice → desert → lava. 5-year program: grass → ice → desert → cloudy → lava.

**Edge case:** programs shorter than 4 years aren't defined. See Q5.

### F36 — Progress bar with static hero
**Priority:** Must · **Complexity:** Low · **Personas:** P2, P3 · **PRD:** FR-8.3

**Acceptance criteria:** Fill = Σ(`units + extraUnits`) of `FINISHED` + `PASSED` courses ÷ (`totalUnits + totalExtraUnits`). The static pixel-art hero sits at the fill point, and the bar updates right away when a course is finished or passed.

### F37 — Light and dark mode
**Priority:** Must · **Complexity:** Low · **Personas:** All · **PRD:** FR-8.4, §6.2 `User.theme`

**Acceptance criteria:** Follows the system theme by default. A manual toggle overrides it and is saved to `User.theme`. Both themes meet WCAG 2.1 AA contrast.

### F38 — Course-finished celebration
**Priority:** Could · **Complexity:** Low · **Personas:** P2 · **PRD:** FR-8.5

**Acceptance criteria:** A short animation plays when a course becomes `FINISHED`. It respects `prefers-reduced-motion`.

### F39 — List/table view (accessibility + mobile)
**Priority:** Must · **Complexity:** Medium · **Personas:** All · **PRD:** NFR Accessibility, NFR Responsiveness

**Acceptance criteria:** A list/table view next to the graph offers every course action (start, finish, pass, fail, withdraw, retake, log grade). It is fully keyboard-operable and screen-reader-labelled, and is the main usable view on phones.

### F40 — Cold-start loading state
**Priority:** Must · **Complexity:** Low · **Personas:** All · **PRD:** NFR Cold starts

**Acceptance criteria:** While a sleeping backend wakes (up to ~60 s), the app shows a friendly loading message instead of an error or blank screen, and retries until the backend responds.

---

## H. Sharing, Privacy & Security

### F41 — Share link generate/revoke
**Priority:** Should · **Complexity:** Medium · **Personas:** P3 · **PRD:** FR-9.1, J5, §6.2 `User.shareToken`

**Acceptance criteria:** The user can create a random, unguessable (≥ 128-bit) read-only link and copy it. Revoking it makes the old link return "not found" right away, and generating again makes a new token.

### F42 — Shared read-only view
**Priority:** Should · **Complexity:** Low · **Personas:** P3 · **PRD:** FR-9.2

**Acceptance criteria:** The shared view shows the graph, course states and progress bar. Grades, CGPA, professors and attempt details are **left out of the API response** itself, not just hidden in the UI.

### F43 — Privacy note and non-affiliation disclaimer
**Priority:** Must · **Complexity:** Low · **Personas:** All · **PRD:** NFR Privacy, §10 decision 8

**Acceptance criteria:** A short privacy note, following the principles of RA 10173, explains what is stored, that PDFs are not kept, and how to delete an account. A "not affiliated with DLSU" disclaimer is shown and no official DLSU marks are used. There are no analytics that capture grades or CGPA.

### F44 — Per-user ownership checks & API hardening
**Priority:** Must · **Complexity:** Medium · **Personas:** All · **PRD:** NFR Security

**Acceptance criteria**
- Every endpoint checks that the requested curriculum/course/attempt belongs to the signed-in user. Another user's ID returns 404/403.
- HTTPS only. JPA parameterized queries only. Upload size and MIME limits (F5). A parse timeout (F5).
- Integration tests cover cross-user access attempts for each resource.

---

## I. Out of Scope (Won't have — MVP)

| ID | Feature | PRD reference | Note |
|---|---|---|---|
| F45 | Hero customization | §3 | Hero stays static (F36). |
| F46 | Term history and per-term GPA | §3, FR-5.5 | App tracks current state only; CGPA stays (F32). |
| F47 | Minor Program slots | §3, FR-6.2, §10 decision 11 | Skipped at parse time (F10). |
| F48 | Per-college parser variants | §3, FR-2.5, §10 decision 9 | Could come back if weeks 10–11 find a college that doesn't fit (§10 still-open 1). |
| F49 | Scanned/image PDFs (OCR) and Excel input | §3, FR-2.2 | |
| F50 | Diagram-only (arrow-geometry) parsing | FR-2.4 | No-checklist PDFs are rejected (F11). |
| F51 | Aggregated/anonymized professor and grade statistics | §3 | |
| F52 | AnimoSys / MLS / official DLSU integration | §3 | |
| F53 | Native mobile apps | §3 | Responsive web only (F39). |
| F54 | Multiple flowcharts per user (shifting) | §3, §10 decision 5 | One active curriculum per account. |

---

## Open questions and PRD gaps found during extraction

These don't block implementation planning, but each one affects at least one feature's acceptance criteria. Resolve them before writing the RFCs.

| # | Question | Affects |
|---|---|---|
| Q1 | **`Attempt.result` enum has no `FINISHED`.** §6.2 and FR-5.3 list `PASSED \| FAILED \| WITHDRAWN \| INCOMPLETE`, but FR-5.4 computes CGPA from "the latest `FINISHED` attempt". The enum probably needs `FINISHED` added. | F30, F31, F32 |
| Q2 | **Mutual co-requisites vs. cycle validation.** If A co-req B and B co-req A, FR-3.3's cycle check would reject the pair, and FR-4's co-req rule means neither course can be started first. Suggested: exclude `COREQ` edges from cycle detection, and allow starting co-requisites together. | F18, F23 |
| Q3 | **CGPA unit weight.** Do parenthetical `extraUnits` count in the CGPA weight? They count toward the cap and progress bar, but courses with extra units, like NSTP, are usually pass/fail. | F32 |
| Q4 | **Failed pass/fail courses and CGPA.** FR-4 says `FAILED` covers "a failing pass/fail result" and "counts toward CGPA as 0.0". Taken literally, failing NSTP would pull CGPA down, which DLSU likely doesn't do. The PRD also never defines a revert transition out of `FINISHED`/`PASSED`, though FR-4's downstream-consistency rule assumes one exists. | F21, F27, F32 |
| Q5 | **Biomes for programs shorter than 4 years.** FR-8.2 only defines 4+ years. | F35 |
| Q6 | **Parser-bet fallback** (PRD §10 still-open 1): if a college doesn't fit the universal parser, special-case it or launch without it? | F7, F15, F48 |

---

## Self-check

I ran these checks on the finished document:

- **Counts recounted from the feature sections, not carried over.** Must 38 (A 4, B 10, C 4, D 8, E 3, F 1, G 6, H 2), Should 5 (F6, F20, F27, F41, F42), Could 1 (F38), Won't 10 (F45–F54), total 54. The priority table and the category table agree row by row and in their totals.
- **Complexity table:** 4 High + 23 Medium + 17 Low = 44 = in-scope features F1–F44. Every ID's listed complexity matches its section header.
- **Cross-references:** every F-ID mentioned inside another feature (for example F11 → F17, F27 → F21, F50 → F11) points to the feature it describes. Every PRD reference (FR-x.y, §, J-steps, decisions 5/8/9/11) exists in PRD v3 and says what's claimed.
- **Priority mapping:** every PRD P1 item (FR-3.4, FR-4 downstream consistency, FR-9) is Should, and the only P2 item (FR-8.5) is Could.
- **What it turned up:** one real inconsistency *inside the PRD*: the `Attempt.result` enum doesn't match the CGPA formula (Q1). While writing F21 I also found that FR-4's transition list has no revert path, even though the downstream-consistency rule relies on one (folded into Q4). Neither is an error in this file, but both should be fixed in the PRD.
