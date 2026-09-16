# RULES — Anevaino: DLSU Flowchart Quest Map

| | |
|---|---|
| **Sources** | `PRD.md` Draft v3 (2026-09-16), `FEATURES.md` (2026-09-16, F1–F54) |
| **Generated** | 2026-09-16 |
| **Product type** | Web app (SPA frontend + REST backend) |
| **Existing code** | None yet — the repo holds only `documentation/`. These rules set the conventions the first code must follow; once code exists, update this file to match it rather than letting the two drift. |
| **ID policy** | Rule IDs (`R-<section>.<n>`) are permanent. Retired rules are marked `[RETIRED]`, never renumbered. |

> **Checklist scoping.** Classified as a **web app**. Applied: stack and versions, auth, security, privacy, state management, API design, performance, accessibility, responsive design, hosting/cost limits, testing. **Skipped** (not applicable): mobile-store/ASO concerns, library concerns (semver policy, public API surface, bundle-size budgets for consumers, peer-dependency ranges, tree-shaking), CLI ergonomics, regulatory certification (the app is not a system of record), business model/monetization (free, no revenue), and horizontal scaling/multi-region (PRD NFR Scalability: one backend instance, one database).

---

## 0. How to use this file

- **R-0.1** Read `PRD.md`, `FEATURES.md` and this file before writing code. When they conflict: **PRD wins on product behaviour, this file wins on engineering conventions**, and `FEATURES.md` acceptance criteria are the definition of done for a feature.
- **R-0.2** Cite the feature ID (`F<n>`) and PRD requirement (`FR-x.y`) in every RFC, PR description and commit subject that implements it, e.g. `feat(parser): column-aware extraction [F7, FR-2.6]`.
- **R-0.3** Never implement anything from FEATURES §I (F45–F54, "Won't have"). If a task seems to need one, stop and ask.
- **R-0.4** The open questions in §7 are **not yours to settle silently**. If a task touches one, use the provisional default listed there, mark the code with a link to the question (see R-6.3), and flag it in the PR.

---

## 1. Technology stack

### 1.1 Versions

Every version below was checked against its registry on **16 Sep 2026** (npm registry via `npm view`; Maven Central metadata, GitHub releases, Gradle services and Adoptium for the JVM side). Pin exact versions in lockfiles; upgrades are deliberate PRs, not side effects.

**Backend**

| Technology | Version | Notes |
|---|---|---|
| JDK (Eclipse Temurin) | **25** (LTS) | Newest LTS. Build and run on the same major. |
| Kotlin | **2.4.20** | Kotlin Gradle plugin + `kotlin("plugin.spring")` + `kotlin("plugin.jpa")`. |
| Gradle (wrapper) | **9.7.1** | Kotlin DSL (`build.gradle.kts`) only. |
| Spring Boot | **4.1.1** | Latest GA. `4.2.0-M1` exists — **do not** use milestones. |
| Spring Security, Spring Data JPA, Spring Session JDBC, Jackson Kotlin module | managed by Spring Boot 4.1.1 BOM | Never override a BOM-managed version without a written reason. |
| PostgreSQL JDBC driver, Flyway, Testcontainers, JUnit Jupiter | managed by Spring Boot 4.1.1 BOM | Same rule. |
| Apache PDFBox | **3.0.8** | Positional text extraction (F7). |
| MockK | **1.14.11** | Kotlin mocking. |
| SpringMockK | **5.0.1** | `@MockkBean` in Spring slice tests. |
| ktlint-gradle plugin | **14.2.0** | Formatting/lint gate. |

**Frontend**

| Technology | Version | Notes |
|---|---|---|
| Node.js | **22.x LTS** (22.22.2 verified locally) | Pin in `.nvmrc` and `engines`. |
| npm | **10.x** | One package manager only: npm. Commit `package-lock.json`. |
| TypeScript | **6.0.3** | ⚠️ **Not** the registry `latest` (7.0.2): `typescript-eslint` 8.70.0 declares `typescript >=4.8.4 <6.1.0`. Stay on 6.0.x until typescript-eslint supports 7. |
| React / React DOM | **19.3.0** | `@types/react` / `@types/react-dom` 19.3.0. |
| Vite | **8.3.0** | `@vitejs/plugin-react` 6.1.1. |
| React Router | **8.4.0** | Library ("declarative") mode — this is an SPA, not a framework-mode app. |
| React Flow (`@xyflow/react`) | **12.11.6** | Graph rendering (F34). |
| `@dagrejs/dagre` | **3.1.1** | Default auto-layout (ships its own types). `elkjs` 0.12.0 is the fallback **only** if dagre can't hold term-column ranks (F34). Don't install both. |
| Tailwind CSS | **4.3.3** | Via `@tailwindcss/vite` 4.3.3. |
| TanStack Query | **5.102.8** | All server state (R-2.12). |
| Zod | **4.6.5** | Runtime validation of API responses and forms. |
| `@react-oauth/google` | **0.13.5** | Google Identity Services button (F1). |
| Vitest | **5.0.1** | + `@vitest/coverage-v8` 5.0.1, `jsdom` 30.0.1. |
| Testing Library (React) | **16.3.3** | |
| MSW | **2.15.0** | API mocking in component tests. |
| Playwright (`@playwright/test`) | **1.63.0** | E2E. |
| `@axe-core/playwright` | **4.13.0** | Automated a11y checks in E2E (R-4.10). |
| ESLint | **10.10.0** | + `typescript-eslint` 8.70.0, `eslint-plugin-react-hooks` 7.1.1. |
| Prettier | **3.9.6** | |

**Infrastructure (all free tier, per PRD §6.1)**

| Layer | Choice |
|---|---|
| Frontend hosting | Vercel (Cloudflare Pages is the fallback; pick one in the foundations RFC and don't split). |
| Backend hosting | Render free web service (512 MB RAM, sleeps on idle). |
| Database | Neon PostgreSQL free plan (0.5 GB storage). |
| Auth | Google Cloud OAuth client. |
| CI | GitHub Actions (free for public repos / free minutes for private). |

- **R-1.1** No new runtime dependency without a one-line justification in the PR ("what it does that the current stack can't"). Prefer the platform or an existing dependency.
- **R-1.2** Before adding or bumping any dependency, check the registry for the actual current version (`npm view <pkg> version`; Maven Central metadata for JVM). Never write a version from memory.
- **R-1.3** Nothing may introduce a recurring cost (PRD G5). Any service beyond the table above needs Matthew's approval.
- **R-1.4** Respect the 512 MB Render limit: JVM flags `-XX:MaxRAMPercentage=75`, a single Hikari pool of ≤ 5 connections (Neon free tier), and no in-memory caches of whole curricula across users.

### 1.2 Repository layout

```
anevaino/
├── documentation/          PRD.md, FEATURES.md, RULES.md, rfcs/
├── backend/                Kotlin + Spring Boot (Gradle)
├── frontend/               React + TypeScript + Vite (npm)
├── shared/
│   └── rule-vectors/       JSON test cases run by BOTH backend and frontend (F29)
├── fixtures/
│   └── flowcharts/         Sample PDFs + expected-parse JSON, one folder per college (F15)
├── CLAUDE.md               Points agents at documentation/RULES.md (§6)
└── .github/workflows/
```

- **R-1.5** Monorepo, one repo. Backend and frontend never import from each other; the only shared artefacts are `shared/rule-vectors/` and `fixtures/`, which are data, not code.

---

## 2. Technical preferences

### 2.1 Naming

| Thing | Convention | Example |
|---|---|---|
| Kotlin packages | lowercase, by feature | `ph.anevaino.parser`, `ph.anevaino.course` |
| Kotlin classes / files | `PascalCase`, one public top-level class per file, file = class name | `ChecklistTableExtractor.kt` |
| Kotlin functions / properties | `camelCase` | `computeCgpa()` |
| Kotlin constants | `SCREAMING_SNAKE_CASE` in `companion object` or top-level `const val` | `MAX_UPLOAD_BYTES` |
| React components | `PascalCase.tsx`, one component per file | `CourseNode.tsx` |
| Hooks | `useCamelCase.ts` | `useCourseActions.ts` |
| Other TS modules | `camelCase.ts` | `cgpa.ts`, `rules.ts` |
| TS types / interfaces | `PascalCase`, no `I` prefix | `CourseState` |
| Database tables / columns | `snake_case`, singular table names | `course`, `extra_units` |
| Flyway migrations | `V<n>__<snake_description>.sql` | `V3__add_parse_confidence.sql` |
| REST paths | `kebab-case`, plural nouns | `/api/curricula/{id}/courses` |
| JSON fields | `camelCase` | `exemptionNote` |
| Enum values | `SCREAMING_SNAKE_CASE`, **identical strings** in Kotlin, SQL and TS | `BEING_TAKEN` |
| Rule-vector files | `<rule>.<case>.json` | `hard-prereq.unmet-blocks.json` |
| Branches | `<type>/F<n>-short-desc` | `feat/F7-column-extraction` |
| Commits | Conventional Commits + feature ID (R-0.2) | `fix(cgpa): exclude INCOMPLETE [F32]` |

- **R-2.1** Domain vocabulary comes from the PRD and must not be renamed: *curriculum, course, prerequisite (HARD/SOFT/COREQ), exemption note, attempt, unit cap, extra units, slot type, parse confidence, CGPA*. No synonyms (`subject`, `class`, `gwa`, `prereq type` variants).
- **R-2.2** The seven states are exactly `NOT_TAKEN, BEING_TAKEN, FINISHED, PASSED, FAILED, WITHDRAWN, INCOMPLETE` (F21). No aliases, no extra states.

### 2.2 Code organization

**Backend** — package-by-feature, layered inside each feature:

```
ph.anevaino
├── auth/        Security config, ID-token verification, domain check (F1–F3)
├── user/        User entity, settings, account deletion (F4, F26 cap, F37 theme)
├── parser/      PDF → ParsedCurriculum; NO Spring or JPA imports (F5–F15)
├── curriculum/  Curriculum/Course/Prerequisite entities, review + save validation (F16–F20)
├── rules/       Pure rules engine: state transitions, prereqs, unit cap (F21–F29)
├── grades/      Attempt, CGPA (F30–F32)
├── share/       Share token + public read-only endpoint (F41–F42)
└── common/      Error model, ownership guard, config
```

- **R-2.3** `parser/` and `rules/` are **pure Kotlin**: input data in, result out, no Spring annotations, no database, no clock or randomness passed implicitly. This keeps the two riskiest pieces (F7, F29) unit-testable in milliseconds.
- **R-2.4** Controllers are thin: parse request → call service → map to DTO. Business logic lives in services / `rules/`. Entities never leave the service layer; the API returns DTOs.
- **R-2.5** Functions ≤ ~40 lines, files ≤ ~300 lines as a smell threshold; split when exceeded unless there's a stated reason.

**Frontend** — feature folders:

```
frontend/src/
├── app/          Router, providers, layout, theme
├── features/
│   ├── auth/  upload/  review/  map/  course/  settings/  share/
├── domain/       rules.ts, cgpa.ts, progress.ts — pure TS mirrors of backend/rules (F29)
├── api/          Typed fetch client + Zod schemas + TanStack Query hooks
├── components/   Shared presentational components
└── assets/       Pixel art, biome layers (self-drawn only — PRD §10 decision 7)
```

- **R-2.6** `domain/` has no React imports. Components call hooks; hooks call `api/` and `domain/`.

### 2.3 Architecture and data

- **R-2.7 Backend is authoritative (FR-4.2, G2).** Every state change is validated by `rules/` on the server. The frontend mirror exists for instant feedback only; if they disagree, the server response wins and the UI rolls back.
- **R-2.8 Rules parity via shared vectors (F29).** Every rule in F21–F26, plus CGPA (F32) and progress (F36), has JSON cases in `shared/rule-vectors/` with input state and expected output (allowed/blocked/warning + messages, or numeric result). Both the Kotlin and the TS test suites load the same files. Adding or changing a rule without a vector is an incomplete change.
- **R-2.9 Schema is migration-only.** Flyway owns the schema; `spring.jpa.hibernate.ddl-auto=validate`. Never edit a merged migration — add a new one.
- **R-2.10 Referential integrity in the database.** Foreign keys with `ON DELETE CASCADE` from `user` down to `attempt` (F4); unique `(curriculum_id, code)` (F18); check constraints on enums and on `grade IN (4.0,3.5,3.0,2.5,2.0,1.5,1.0,0.0)` (F30).
- **R-2.11 Numbers.** Units are integers. Grades and CGPA use `BigDecimal` (Kotlin) / integer hundredths (TS) — never binary floats for comparison or rounding. CGPA is displayed to 2 decimals, rounded half-up; "no graded attempts" displays `—` (F32).
- **R-2.12 Frontend state.** Server state lives only in TanStack Query. Local UI state (selected node, dialog open, zoom) lives in component state or a small context. No Redux/Zustand unless a written need appears. Course state changes use optimistic updates with rollback on error.
- **R-2.13 API contract.** REST/JSON under `/api`. Errors use RFC 9457 Problem Details (`application/problem+json`) with a stable `type` slug (e.g. `rule/hard-prereq-unmet`) and structured detail (e.g. `unmetPrerequisites: ["CCPROG2"]`), so the UI can render J2's messages without string parsing. Every response body is validated with Zod on the frontend.
- **R-2.14 Share view is a separate DTO (F42).** The public endpoint has its own response type that has **no fields** for grade, CGPA, professor or attempts. Don't reuse the owner DTO with fields nulled out.

### 2.4 Authentication and session

- **R-2.15 Same-origin API.** The frontend proxies `/api/*` to Render via a hosting rewrite, so the browser sees one origin. This avoids third-party-cookie blocking and removes CORS from the design (resolves the F1 technical note).
- **R-2.16 Login flow.** Frontend obtains a Google ID token → `POST /api/auth/google` → backend verifies signature, `aud`, `iss`, expiry, `email_verified == true`, `email` ends with exactly `@dlsu.edu.ph` **and** `hd == "dlsu.edu.ph"` (F2) → creates a server session stored with Spring Session JDBC (survives Render restarts) → `HttpOnly; Secure; SameSite=Lax` cookie.
- **R-2.17** CSRF protection stays **on** (cookie-to-header token). Sign-out invalidates the session server-side (F3).
- **R-2.18** A non-DLSU login creates **no** `User` row and returns a Problem Details error the UI turns into a clear message (J1.1).

### 2.5 Security

- **R-2.19 Ownership on every query (F44).** Owned resources are loaded through repository methods that take the current user ID (`findByIdAndUserId`). Returning another user's resource is a 404, not 403 (don't confirm existence). Each resource type has an integration test that tries cross-user access.
- **R-2.20 Uploads (F5, F14).** Enforce 10 MB in Spring multipart config **and** check the `%PDF-` magic bytes (don't trust the MIME header). Parse in memory on a bounded executor with a hard timeout (start at 10 s). Load PDFs with PDFBox's memory-limited settings. Reject encrypted PDFs with a clear message. The PDF bytes are never written to disk, the DB or logs.
- **R-2.21** Share tokens: 32 bytes from `SecureRandom`, base64url (≥ 128 bits per F41); store only a SHA-256 hash; revoke = delete.
- **R-2.22** Rate-limit `POST /api/auth/google`, upload, and the public share endpoint (simple in-memory token bucket is enough for one instance).
- **R-2.23** Security headers: HSTS, `Content-Security-Policy` (no `unsafe-inline` scripts), `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer` on share pages.
- **R-2.24** Secrets only in environment variables on Render/Vercel; `.env*` is git-ignored; commit an `.env.example` with names and no values.
- **R-2.25** No raw SQL string concatenation. JPA/Spring Data or parameterized native queries only.

### 2.6 Privacy (PRD NFR Privacy, RA 10173 principles)

- **R-2.26** Grades, professors and CGPA never appear in logs, error reports, analytics events, URLs or the share API.
- **R-2.27** No third-party analytics or trackers at launch. If added later, it must be cookieless and must not capture any course data.
- **R-2.28** Account deletion (F4) is a hard delete in a single transaction, including sessions and share tokens, with a test asserting zero rows remain for that user in every table.

### 2.7 Performance

- **R-2.29** Budgets are acceptance criteria, not aspirations: parse ≤ 80 courses in < 5 s server-side (F7); local state change visible < 300 ms and save confirmed < 1 s warm (F29); graph smooth with ~80 nodes (F34).
- **R-2.30** Graph: memoize custom node components, compute layout only when the curriculum structure changes (not on state changes), and keep biome art as CSS/background layers rather than per-node images.
- **R-2.31** Avoid N+1: load a curriculum with its courses, prerequisites and latest attempts in a fixed number of queries (fetch joins or entity graphs). Add a test that asserts query count for the main map endpoint.
- **R-2.32** Cold starts (F40): the frontend calls `GET /api/health` first, shows the friendly wake-up screen, and retries with backoff up to ~60 s before showing an error.
- **R-2.33** Route-level code splitting: the editor, map and settings are lazy-loaded; the landing/sign-in page doesn't download React Flow.

---

## 3. Parser rules (F5–F15)

The parser is the project's highest-risk component (FEATURES "Highest-risk features"), so it gets its own section.

- **R-3.1 One universal implementation (FR-2.5).** No `if (college == ...)` branches, no per-college classes, no college-specific coordinates. `programLabel` is never read by the parser (F6).
- **R-3.2 Detect, don't hard-code, geometry (F7).** Column bands, header rows and the checklist table's position are derived from the page's text positions. A constant that only makes sense for the CCS sample is a bug.
- **R-3.3 Pipeline shape.** `PdfTextLoader` (positions) → `ColumnBandDetector` → `RowAssembler` (handles multi-line titles) → `CourseRowParser` (code/title/units/extra units/slot type) → `PrerequisiteParser` (H/S/C/E) → `ConfidenceEvaluator` → `ParsedCurriculum` (with `warnings` and `parseConfidence`). Each stage is independently unit-tested.
- **R-3.4 Never guess silently.** Anything the parser can't read becomes a `ParseWarning` tied to a row (F12), never a dropped row or an invented value.
- **R-3.5 Markers (F9).** `(H)/(S)/(C)` → `Prerequisite` rows; `(E)` → `Course.exemptionNote`, no edge, no resolution check. Mixed-type cells are split before classification.
- **R-3.6 Minor programs (F10).** `MINOR\d+` placeholders are dropped at `CourseRowParser` and excluded from totals and warnings.
- **R-3.7 Confidence thresholds are configuration (F13).** Tolerances live in `application.yml` (`anevaino.parser.confidence.*`), start loose, and are tuned only against real fixtures (PRD-REVIEW round 2, rec. 2). Changing a threshold requires re-running the full fixture suite.
- **R-3.8 Fixtures are the spec (F15).** Each `fixtures/flowcharts/<college>/` folder holds the PDF and an `expected.json`. CI computes per-college accuracy and fails if any college drops below 90% (G1). A parser change that fixes one college and regresses another does not merge.
- **R-3.9** Adding a college's fixture that the universal parser can't handle is **not** fixed by special-casing it — record it and escalate (§7 Q6).

---

## 4. Development standards

### 4.1 Testing

| Layer | Tool | Required coverage |
|---|---|---|
| `parser/`, `rules/`, `grades/` (backend) | JUnit 5 + MockK | **≥ 90% line and branch** — these encode the product's correctness |
| `domain/` (frontend) | Vitest | **≥ 90% line and branch** |
| Other backend services/controllers | JUnit 5, `@WebMvcTest`/`@DataJpaTest`, Testcontainers Postgres | ≥ 75% line |
| React components | Vitest + Testing Library + MSW | ≥ 70% line for `features/` |
| End-to-end | Playwright + axe | Journeys J1–J5 each have one E2E happy path |

- **R-4.1** Every acceptance criterion in FEATURES.md maps to at least one named test. Test names state the behaviour: `` `blocks start when hard prerequisite is FAILED` ``.
- **R-4.2** Every bug fix starts with a failing test that reproduces it.
- **R-4.3** Persistence tests run against real PostgreSQL via Testcontainers, not H2 (constraint and cascade behaviour must match Neon).
- **R-4.4** The worked CGPA example in F32 (1.5 → 2.75) and the biome sequences in F35 are literal test cases.
- **R-4.5** CI (on every PR): ktlint, ESLint, Prettier check, `tsc --noEmit`, backend tests, frontend tests, rule-vector parity, fixture accuracy, Playwright smoke. A red CI never merges.

### 4.2 Error handling and logging

- **R-4.6** Backend: domain failures are typed exceptions (`RuleViolation`, `ParseFailure`, `NotFound`) mapped centrally by one `@RestControllerAdvice` to Problem Details (R-2.13). No `catch (e: Exception) {}`; never return stack traces to clients.
- **R-4.7** Frontend: every query/mutation has loading, error and empty states. A top-level error boundary shows a recoverable screen. User-facing messages are plain language and say what to do next.
- **R-4.8** Logging: SLF4J structured (JSON in production) with a request ID; `INFO` for lifecycle events (login, upload accepted, parse finished with confidence and duration), `WARN` for rule violations and low-confidence parses, `ERROR` only for unexpected faults. Log user IDs, never emails, grades, professors or PDF content (R-2.26).

### 4.3 Accessibility (WCAG 2.1 AA)

- **R-4.9** State and eligibility are shown by icon/pattern **and** colour, never colour alone; every combination must be distinguishable in grayscale (F28).
- **R-4.10** Contrast ≥ 4.5:1 text, ≥ 3:1 UI graphics in both themes (F37). Playwright runs axe on sign-in, review, map (list view) and settings; any serious/critical violation fails CI.
- **R-4.11** The list/table view (F39) is the keyboard and screen-reader path for every course action. The graph itself may be pointer-first, but nothing is possible only in the graph.
- **R-4.12** Semantic HTML first (`button`, `table`, `dialog`); ARIA only to fill gaps. Visible focus rings; focus is trapped in and returned from dialogs.
- **R-4.13** Animations (F38) and biome effects respect `prefers-reduced-motion`.

### 4.4 Responsive design

- **R-4.14** Desktop-first (PRD NFR Responsiveness). Tailwind breakpoints: design at `lg` (≥ 1024 px), must remain usable at `sm` (≥ 640 px) and at 360 px width.
- **R-4.15** Below `md`, the list view (F39) is the default view; the graph remains reachable with touch pan/zoom (F34).
- **R-4.16** Touch targets ≥ 44 × 44 px. No horizontal page scroll outside the graph canvas.

---

## 5. Implementation priorities

### 5.1 MoSCoW (from FEATURES.md)

| Priority | Count | Features |
|---|---|---|
| Must | 38 | F1–F5, F7–F19, F21–F26, F28–F37, F39, F40, F43, F44 |
| Should | 5 | F6, F20, F27, F41, F42 |
| Could | 1 | F38 |
| Won't (MVP) | 10 | F45–F54 |
| **Total** | **54** | |

- **R-5.1** No Should/Could work starts while a Must in the same phase is incomplete.

### 5.2 Phases (PRD §9)

| Phase | Weeks | Features | Exit gate |
|---|---|---|---|
| 1. Foundations | 1–2 | F1, F2, F3, F44 (baseline), repo/CI, fixture collection for F15 | DLSU login works end-to-end on deployed Vercel + Render + Neon; non-DLSU rejected by the API |
| 2. Parser v1 | 3–5 | F5, F7–F14 | CCS fixture ≥ 90%; confidence check has both NORMAL and LOW fixtures |
| 3. Review & edit | 6–7 | F16–F19, (F6) | Upload → review → save with validation, including empty-curriculum manual build |
| 4. Graph & rules | 8–9 | F21–F26, F28–F34, (F27) | All rule vectors pass on both sides; CGPA worked example passes |
| 5. Fixtures & game | 10–11 | F15 (all colleges), F35–F37, F40 | Every collected college ≥ 90% or escalated per R-3.9 |
| 6. Share, privacy, a11y | 12 | F4, F39, F43, (F20, F41, F42) | Axe clean; deletion leaves zero rows; share API has no grade fields |
| 7. Beta → launch | 13–14 | Fixes, (F38) | All Musts meet their acceptance criteria |

### 5.3 Quality thresholds for "done"

A feature is done only when **all** hold: every FEATURES.md acceptance criterion has a passing test (R-4.1); coverage floors in §4.1 are met; lint/type checks are clean; accessibility checks pass for any UI touched; no open `FIXME`/placeholder in the diff (R-6.3); the PRD/FEATURES docs are updated if behaviour changed.

---

## 6. General guidelines

- **R-6.1 Follow the requirements precisely.** Implement what the PRD and acceptance criteria say — no extra features, no "while I'm here" refactors outside the task, no changed wording in user-facing strings that the PRD quotes (e.g. F5, F19 messages).
- **R-6.2 Readable over clever.** Plain, explicit code; descriptive names; small functions; comments explain *why*, not *what*. KDoc/TSDoc on every public function in `parser/`, `rules/`, `grades/` and `domain/`.
- **R-6.3 Completeness.** Merged code has no `TODO`, stubbed functions, placeholder data or commented-out code. The single allowed marker is `// OPEN-QUESTION(Q<n>): <one line>` for a provisional default from §7, which must also be listed in the PR description. (Skeleton code with TODOs is fine in a learning/drafting conversation when Matthew asks for it, but never merged.)
- **R-6.4 Ambiguity.** If the PRD, FEATURES and this file don't answer a question: (1) check §7; (2) if it's there, use the provisional default; (3) if not, **stop and ask** with a concrete recommendation and its trade-off, rather than guessing. Record the answer in the PRD decision log.
- **R-6.5 Keep docs in sync.** A change in behaviour updates `PRD.md`/`FEATURES.md` in the same PR. A change in convention updates this file. Never renumber F-IDs or R-IDs.
- **R-6.6 Verify, don't assume.** Run the build and tests before claiming a task is complete, and report what was run.
- **R-6.7 Small PRs.** One feature (or one tightly related group) per PR, ideally < 400 changed lines excluding fixtures and lockfiles.

### 6.1 Agent configuration

- **R-6.8** Create `CLAUDE.md` at the repo root containing at minimum: "Read `documentation/RULES.md`, `documentation/PRD.md` and `documentation/FEATURES.md` before any change. Follow RULES.md for conventions." If another agent is used, add the same pointer in `AGENTS.md` (Codex and others) or `.cursor/rules/` (Cursor). Reference this file; don't copy it, so there's one source of truth.

---

## 7. Open questions carried from FEATURES.md (provisional defaults)

Agents use these defaults until Matthew decides; each use is marked per R-6.3.

| # | Question | Provisional default | Affects |
|---|---|---|---|
| Q1 | `Attempt.result` enum lacks `FINISHED`, but CGPA uses "latest FINISHED attempt" | Add `FINISHED` to `Attempt.result` | F30, F31, F32 |
| Q2 | Mutual co-requisites vs. cycle validation | Exclude `COREQ` edges from cycle detection; allow starting co-requisite groups together in one request | F18, F23 |
| Q3 | Do `extraUnits` weight CGPA? | No — CGPA weights by `units` only (extra units still count for cap and progress) | F32 |
| Q4 | Failed pass/fail courses in CGPA; no revert path out of FINISHED/PASSED | A failed pass/fail course (no numeric grade) is `FAILED` with `grade = null` and is excluded from CGPA; revert is an explicit "Undo result" transition back to `BEING_TAKEN` that triggers F27's warning | F21, F27, F32 |
| Q5 | Biomes for programs shorter than 4 years | Year 1 grass, final year lava, middle years in order ice → desert | F35 |
| Q6 | A college that doesn't fit the universal parser | Don't special-case; mark as unsupported for that college's fixture and escalate (R-3.9) | F7, F15, F48 |

---

## Self-check

Ran against this finished file:

- **MoSCoW table recounted from the ID lists, not copied:** Must list F1–F5 (5) + F7–F19 (13) + F21–F26 (6) + F28–F37 (10) + F39, F40, F43, F44 (4) = 38; Should 5; Could 1; Won't F45–F54 = 10; total 54. Matches FEATURES.md's summary, and every ID's priority matches its FEATURES.md header (F6, F20, F27, F41, F42 = Should; F38 = Could).
- **Phase table vs. MoSCoW table:** every F1–F44 appears in exactly one phase (F44 listed in phase 1 as baseline and applies throughout); parenthesised IDs are exactly the Should/Could ones. No disagreement between the two tables.
- **Cross-references checked:** F7 column extraction, F13 confidence, F15 fixtures, F21 states, F29 shared rules, F32 CGPA worked example (1.5 → 2.75), F35 biomes, F39 list view, F41/F42 share, F44 ownership, FR-2.5/2.6/4.2, G1/G5, PRD §6.1/§9/§10 decision 7 — each points at what the text claims. R-ID references (R-2.13, R-2.26, R-3.9, R-4.1, R-6.3) point at the right rules. The check caught that §6's rules had been drafted as R-5.x, colliding with §5's numbering; renumbered to R-6.1–R-6.8 before first publication and every reference updated.
- **Versions:** all from registries on 16 Sep 2026. **What it turned up:** npm `latest` for TypeScript is 7.0.2, but typescript-eslint 8.70.0 only supports `<6.1.0`, so the file pins 6.0.3 — writing "latest" would have broken linting on install. Spring Boot's newest artifact is a milestone (4.2.0-M1); pinned the GA 4.1.1 instead.
