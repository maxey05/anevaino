# PRD — Anevaino: DLSU Flowchart Quest Map

| | |
|---|---|
| **Author** | Matthew (with Claude) |
| **Status** | Draft v3 — parser architecture, CGPA formula and minor-program scope finalized |
| **Date** | 2026-09-16 |
| **Product type** | Web app (account-based, single-page frontend + REST backend) |

> **Checklist scoping.** I classified this as a **web app**. I applied these checks: auth, data privacy, hosting and cost, responsive design, accessibility, performance and security. I skipped these: mobile-store/ASO, library concerns (semver, bundle-size API surface, peer dependencies), CLI ergonomics, regulatory certification (the app is not a university system of record), and business model/monetization (it is free, with no revenue plans). This pass resolves three decisions Matthew made on top of the v2 review — see §10 for the full, dated decision log.

---

## 1. Overview

Anevaino turns a DLSU student's curriculum flowchart PDF into an interactive, game-styled progress map. Each course is a node on a left-to-right graph, connected to its prerequisites. Students track each course through a full state lifecycle (taken, in progress, finished, passed on a pass/fail basis, failed, withdrawn, or incomplete), log grades and professors, watch their CGPA update, and watch a pixel-art hero travel across biomes, one per year, toward the evil castle at graduation.

**Value proposition:** DLSU flowcharts are static PDFs. Checking "can I take this next term?" means tracing prerequisite codes by hand. Anevaino enforces the prerequisite rules and unit limits automatically, keeps a private academic log with a running CGPA, and makes progress feel rewarding.

## 2. Goals and Objectives

| # | Goal | Measure |
|---|------|---------|
| G1 | Parse official DLSU flowchart PDFs from **every college** accurately, using one universal parser (§10 round 2, decision 1) | ≥ 90% of courses and prerequisite links correct before manual edits, tested against a sample PDF from each college individually — one parser, but every college's sample still has to pass this bar on its own |
| G2 | Make prerequisite checks effortless | 0 invalid "being taken" states possible (hard prereqs, co-reqs, unit cap enforced) |
| G3 | Upload to usable map fast | Median time from login to saved graph < 5 minutes |
| G4 | Public launch at DLSU within ~1 term | Live, publicly accessible by end of target term (see §9) |
| G5 | Zero running cost | $0/month on free tiers at launch scale |

## 3. Scope

### In scope (MVP)
- Google sign-in, restricted to `@dlsu.edu.ph` accounts
- PDF upload, parsed by a single **universal checklist-table parser** (see FR-2) — no per-college parser selection
- Review and manual edit screen for parsed results (courses, units, prerequisite links), with a prominent warning banner when the parser's own confidence check is low (FR-2.11, FR-3.5)
- Left-to-right course graph grouped by year/term, with biome backgrounds per year (see FR-8.2)
- Course states: *not taken*, *being taken*, *finished* (graded pass), *passed* (pass/fail or credit-bearing non-graded requirement), *failed*, *withdrawn*, *incomplete*
- Hard prerequisite, co-requisite, soft prerequisite, and exemption-note handling (see FR-4)
- User-defined unit cap for "being taken" courses, counting parenthetical/special units
- Grade and professor logging on finished, passed, failed and withdrawn courses, with attempt history
- **Cumulative GPA (CGPA)**, weighted by units, computed from graded *finished* **and** *failed* attempts (a failing grade pulls the CGPA down until the course is retaken and passed — see FR-5.4)
- Elective placeholder slots the user fills in with a real course code and units
- Progress bar (including parenthetical units in its total) with a static pixel-art hero
- Light and dark mode
- Private data plus an optional read-only share link that hides grades, professor and CGPA

### Out of scope (MVP)
- Hero customization
- Term/trimester history and **per-term** GPA (the app tracks **current state only**; CGPA is cumulative and does not need term history, so it stays in scope — see FR-5)
- **Minor Program placeholder slots** (the sample flowchart's dashed "MINOR01-04" boxes) — confirmed out of scope entirely for MVP, not just hidden-but-tracked: the parser recognizes and skips them rather than creating course rows (FR-2.8, FR-6.2)
- **Per-college parser variants** — confirmed out of scope: one universal parser serves every college (see §10 round 2, decision 1, and its residual risk)
- Scanned or image PDFs (OCR), Excel inputs
- Aggregated or anonymized professor and grade statistics
- Integration with AnimoSys, MLS or other official DLSU systems
- Native mobile apps
- Multiple flowcharts per user (for example, shifting programs). One active flowchart per account.

## 4. Target Audience / Personas

**P1 — "Planner Pia", 2nd-year student.** She has failed or dropped a course before and is unsure what she can take next term. She wants a reliable "what am I eligible for?" view before enlistment.

**P2 — "Freshman Franco", 1st-year student.** He finds the flowchart PDF confusing and is motivated by visible progress. The game theme is what keeps him coming back.

**P3 — "Senior Sam", 4th-year student.** Sam is close to graduating. Sam wants to confirm remaining units and prerequisites, and shares a progress link with a thesis adviser or parents without exposing grades or CGPA.

**Shared traits:** They have a `@dlsu.edu.ph` Google account and use the app mostly on a laptop during enlistment season, with occasional phone checks. They care about privacy, especially for grades.

## 5. Functional Requirements

Priority key: **P0** = required for launch, **P1** = should ship at launch, **P2** = nice to have.

### FR-2 Upload and Parsing (P0)

> **Grounded against a real sample, then simplified by decision.** The CCS BSCS-NIS flowchart PDF has three pages: (1) a visual diagram with arrows only, no embedded prerequisite text; (2)–(3) a text-based **"Program Checklist"** table — Course Code | Course Title | Units | Pre/Co-requisites, with the relationship already spelled out as `(H) CODE`, `(S) CODE`, `(C) CODE`, or `(E) CODE`. Matthew has decided every DLSU college's official PDF follows this same checklist-table structure, so Anevaino ships **one universal parser** rather than a parser-per-college strategy. This is accepted as Matthew's call, but it is a bet made on a **single confirmed sample** — FR-2.11 below is the safety net if a college turns out to differ.

- FR-2.1 Before upload, the user may optionally label the curriculum with a program name (free text or a dropdown of known DLSU programs) for their own reference. This label is display-only — it does **not** select a parser.
- FR-2.2 Accept PDF files with a text layer only, up to 10 MB. If no extractable text is found, show "This looks like a scanned PDF, which isn't supported yet."
- FR-2.3 **Primary parse source: the Program Checklist table.** Parse each row into course code, title, units, and prerequisite list with type. This is the MVP's default and only required path.
- FR-2.4 **Fallback: the diagram page.** Used only as a safety net if the universal parser can't find a checklist table at all, or its confidence check (FR-2.11) fires. Diagram-only (arrow-geometry) parsing stays **out of scope for MVP** — if a PDF has no checklist table, the upload is rejected with a clear message, and the student falls back to building the graph by hand in the manual editor (FR-3).
- FR-2.5 **One universal parser, not a strategy per college.** A single checklist-table implementation handles every college's PDF (§10 round 2, decision 1). This replaces v2's per-college parser-strategy design. If Week 10–11 testing (§9) finds a college whose checklist table genuinely doesn't fit the shared parser, the fallback is FR-2.4, not a silent guess.
- FR-2.6 **Column-layout risk:** the checklist table is arranged as two side-by-side term blocks per page. Naive top-to-bottom text extraction will interleave rows from both blocks out of order. The parser must group extracted text by x-position band before reading rows top-to-bottom within each band (PDFBox `PDFTextStripperByArea`, or a custom sort-by-x-then-y pass).
- FR-2.7 Recognize four prerequisite markers from the checklist's own legend: **(H)** hard, **(S)** soft, **(C)** co-requisite, **(E)** exemption. An `(E)` entry (for example `MTH101A ... (E) BASMATH`) references a placement/exemption code that is **not** a course in the flowchart; store it as an informational note on the course (`exemptionNote`), not as a graph edge, and do not require it to resolve to an existing course.
- FR-2.8 Extract, per course: course code, title, units (including a parenthetical "extra units" value when present, for example `NSTP-01 (3)`), year and term placement, prerequisites with type, and whether it is a mandatory elective slot. **Recognize and skip "Minor Program" placeholder boxes** (dashed styling, codes like `MINOR01`–`MINOR04`) entirely — do not create course rows for them (confirmed out of scope, §3, §10 round 2 decision 3).
- FR-2.9 Show parse warnings, such as unreadable rows or prerequisite codes that don't match any course (excluding `(E)` exemptions, which are expected not to match), on the review screen.
- FR-2.10 Store the original PDF only transiently. Delete it after parsing and keep only structured data.
- FR-2.11 **Parse-confidence check.** Because one parser now has to work for every college without college-specific tuning, the parser computes a confidence signal per upload: row counts per term roughly match the flowchart's own printed per-term unit totals, and every hard/soft/co-requisite code (excluding `(E)` exemptions) resolves to an extracted course. If the check fails, mark the parse `LOW_CONFIDENCE` and surface a prominent warning on the review screen (FR-3.5) rather than presenting it with the same trust as a clean parse.

### FR-1 Authentication (P0)
- FR-1.1 Sign in with Google OAuth 2.0 / OpenID Connect.
- FR-1.2 Reject any account whose verified email does not end in `@dlsu.edu.ph`. Check this on the **backend** using the ID token's `email` and `hd` (hosted domain) claims, not only in the UI.
- FR-1.3 Sign-out, plus account deletion that removes all user data.

### FR-3 Review and Manual Edit (P0)
- FR-3.1 After parsing, show a review screen (table plus graph preview) before the graph is saved.
- FR-3.2 The user can add, edit and delete courses, change units (including the parenthetical extra-units value), year and term, and add or remove prerequisite links and their type (hard / soft / co-req / exemption note).
- FR-3.3 Validate before saving: no duplicate codes, no prerequisite cycles, and every hard/soft/co-requisite must reference an existing course. Exemption notes are exempt from this check (FR-2.7).
- FR-3.4 The user can reopen the editor later (P1).
- FR-3.5 When a parse is marked `LOW_CONFIDENCE` (FR-2.11), show a distinct warning banner on the review screen ("This flowchart's layout looks different than expected — please double-check every row before saving") rather than treating it like a normal successful parse.

### FR-4 Course States and Rules (P0)

States: `NOT_TAKEN → BEING_TAKEN → FINISHED | PASSED | FAILED | WITHDRAWN | INCOMPLETE`, and `INCOMPLETE → FINISHED | PASSED | FAILED` once resolved. `FAILED` and `WITHDRAWN` can be moved back to `BEING_TAKEN` (retake).

- **FINISHED** — completed with a numeric grade on the 0.0–4.0 scale (counts toward CGPA).
- **PASSED** — completed on a pass/fail or credit/no-credit basis (for example NSTP, SAS, LASARE, Lasallian Studies milestones); satisfies prerequisites exactly like FINISHED, but carries no grade and is excluded from CGPA.
- **FAILED** — a numeric grade of 0.0, or a failing pass/fail result. **Counts toward CGPA as 0.0** until the course is retaken (FR-5.4).
- **WITHDRAWN** — dropped after the add/drop period ("W"). Behaves like FAILED for prerequisite purposes (does not satisfy a hard/co-requisite) but is logged and displayed distinctly, and — unlike FAILED — is excluded from CGPA, since DLSU's own "W" mark isn't a grade point.
- **INCOMPLETE** — grade pending ("INC"); does not yet satisfy any prerequisite and does not count toward the unit cap once out of BEING_TAKEN.

| Rule | Behavior |
|------|----------|
| **Hard prerequisite** | A course can be set to *being taken* only if **every** hard prerequisite is in state `FINISHED` or `PASSED`. Otherwise the action is blocked and the unmet prerequisites are listed. |
| **Co-requisite** | Each co-requisite must be `FINISHED`/`PASSED`, or `BEING_TAKEN` at the same time. Otherwise the action is blocked. |
| **Soft prerequisite** | Satisfied (no warning) once the course has **any** logged attempt (`FINISHED`, `PASSED`, `FAILED`, or `WITHDRAWN`). If it has never been attempted, show a **warning** the user can dismiss and proceed past. |
| **Exemption note** | Informational only (FR-2.7). Never blocks or warns; shown as a badge on the course detail panel. |
| **Unit cap** | The sum of units (including parenthetical extra units, per Matthew's confirmation) of all `BEING_TAKEN` courses must be ≤ the user's cap. An action that would exceed it is blocked and shows "X / Y units". |
| **Downstream consistency** | If a `FINISHED`/`PASSED` course is reverted to `NOT_TAKEN`, `FAILED`, or `WITHDRAWN` while dependents are `BEING_TAKEN` or later, warn and list the affected courses. Do **not** silently change them (P1). |

- FR-4.1 Nodes visually show their state and eligibility (eligible / locked / warning) using both icon and color, never color alone (see Accessibility, §6).
- FR-4.2 All rules are enforced **on the backend** and mirrored on the frontend for instant feedback.

### FR-5 Grade Log and CGPA (P0)
- FR-5.1 When a course is marked `FINISHED`, the user logs a grade (DLSU scale: 4.0, 3.5, 3.0, 2.5, 2.0, 1.5, 1.0, 0.0) and, optionally, a professor name. `PASSED` and `WITHDRAWN` log a professor only (no numeric grade); `FAILED` defaults to 0.0 but may log a professor.
- FR-5.2 Selecting a grade of 0.0 sets the state to `FAILED` automatically; selecting `FAILED` implies 0.0.
- FR-5.3 Each attempt is kept as a separate record (`PASSED | FAILED | WITHDRAWN | INCOMPLETE`, plus a nullable grade), so the course detail panel shows its full attempt history, including retakes.
- FR-5.4 **Cumulative GPA (CGPA)**, shown in the header alongside the progress bar: units-weighted average of the grade from the **latest `FINISHED` or `FAILED` attempt per course** (confirmed: a failing grade pulls the CGPA down, exactly like DLSU's own computation, and stays down until the course is retaken and passed), across all courses with at least one such attempt. `PASSED` and `WITHDRAWN` carry no numeric grade and are excluded; `INCOMPLETE` is excluded until it resolves.
  *Worked example:* a student takes CCPROG1 (3 units, grade 3.0) and fails NSSECU1 once (3 units, 0.0). Until they retake NSSECU1, its 0.0 counts in the CGPA average. Once they retake and pass with a 2.5, only the 2.5 (the latest attempt) counts — the earlier 0.0 drops out of the calculation, but the attempt itself stays in the history log (FR-5.3).
- FR-5.5 **Per-term GPA is out of scope for MVP** (§3) — it would require tracking term boundaries, which the app does not model. This is a deliberate scope line, not an oversight; revisit if term history is added later.

### FR-6 Elective Slots (P0)
- FR-6.1 **Mandatory elective slots** (for example "NSELEC1-3", "GE Elective") are required for graduation and appear as normal slot nodes. Before or when setting a slot to *being taken*, the user enters the real course code, title and units; the slot keeps its original category label.
- FR-6.2 **Minor Program slots are out of scope for MVP, confirmed.** The parser skips them entirely at ingest (FR-2.8); they never appear as nodes, are never part of the required-unit total, and there is no separate "optional slot" interaction to build. Revisit post-MVP only if there's real demand.

### FR-7 Unit Cap (P0)
- FR-7.1 The user sets the cap in settings. The default is empty, meaning no cap, until the user sets one.
- FR-7.2 The cap counts a course's full load including any parenthetical extra units (FR-2.8), matching how the source flowchart's own per-term unit totals are computed.
- FR-7.3 Lowering the cap below the current load is allowed, but shows a warning and blocks new *being taken* actions until the load is within the cap.

### FR-8 Graph and Game Presentation (P0 unless noted)
- FR-8.1 Left-to-right layout: columns are terms, grouped into years. Edges run from prerequisite to course. Pan, zoom, and fit-to-screen.
- FR-8.2 **Biome sequence**, one per year, always ending in the lava biome on the final year: Year 1 grassland + castle, Year 2 icy plains, Year 3 desert, then a **cloudy/sky biome repeated for each additional year beyond Year 3 except the last**, and the **final year is always lava land + evil castle**. For the common 4-year program this is grass → ice → desert → lava; for a 5-year program (confirmed applicable to at least some DLSU programs) it is grass → ice → desert → cloudy → lava.
- FR-8.3 A top progress bar fills based on **(finished + passed course units, including parenthetical extras) ÷ total required units (including the flowchart's own parenthetical total, for example "176 (9)")**, with a static pixel-art hero on the bar at the fill point. Minor Program slots are never parsed (FR-6.2), so they never enter this calculation at all.
- FR-8.4 Light and dark mode. Follow the system setting by default, with a manual toggle.
- FR-8.5 Small celebratory animation when a course is finished (P2).

### FR-9 Share Link (P1)
- FR-9.1 The user can generate or revoke a random, unguessable read-only link.
- FR-9.2 The shared view shows the graph, course states and progress bar. It **never** shows grades, CGPA, or professors.

## 6. Non-Functional Requirements

| Area | Requirement |
|------|-------------|
| **Cost** | $0/month. Every component must run on a free tier (see §6.1). |
| **Performance** | Parse a typical flowchart checklist (≤ 80 courses) in < 5 s server-side. Graph interactions (state change) reflect in < 300 ms locally, with saves confirmed in < 1 s on a warm server. |
| **Cold starts** | Free hosting may sleep. Show a friendly loading state, and accept a first-request delay of up to about 60 s. |
| **Security** | OAuth via Spring Security. Backend-validated domain restriction. HTTPS only. Per-user row ownership checks on every endpoint. Upload size and MIME limits. PDF parsing in memory with a timeout. Parameterized queries via JPA. CSRF protection or a stateless token strategy, whichever fits the auth design. |
| **Privacy** | Grades, professors and CGPA are visible only to the owner. No analytics that capture grades or CGPA. Account deletion is a hard delete. Include a short privacy note that follows the principles of the Philippine Data Privacy Act (RA 10173) and explains what is stored. |
| **Scalability** | Designed for ~1,000–5,000 accounts at launch. A single backend instance and a single Postgres database are sufficient. |
| **Accessibility** | WCAG 2.1 AA contrast in both themes. Node states are distinguished by icon or pattern, not color alone — this now matters more with 7 states instead of 4. Keyboard-accessible course actions via a list or table view alongside the graph. |
| **Responsiveness** | Desktop-first. On phones, the graph is usable with pan and zoom, and a list view is available. |
| **Maintainability** | The universal parser is unit-tested against saved sample PDFs from **every** college it needs to support (not just CCS), including a fixture that exercises the two-column checklist extraction (FR-2.6) and the confidence check (FR-2.11). Since there's now one shared parser instead of isolated per-college strategies, a regression can affect every college at once — broad fixture coverage matters more here than it would have under the v2 design. |

### 6.1 Proposed stack (all free tier — verified 16 Sep 2026)

| Layer | Choice | Notes |
|-------|--------|-------|
| Frontend | **React + TypeScript + Vite**, **React Flow** (graph), **dagre or ELK** (auto left-to-right layout), Tailwind CSS | React Flow handles nodes, edges, pan and zoom. Biomes are background layers per year group. |
| Frontend hosting | Vercel or Cloudflare Pages | Static hosting, free |
| Backend | **Kotlin + Spring Boot**, Spring Security (OAuth2 client/resource server), Spring Data JPA | REST/JSON API |
| PDF parsing | **Apache PDFBox** | Text extraction with x/y positions — needed for the two-column checklist table (FR-2.6), not for diagram-arrow geometry, which stays out of scope (FR-2.4) |
| Database | PostgreSQL on **Neon** free tier | **Confirmed 16 Sep 2026:** permanent free plan, 0.5 GB storage/project, 100 compute-hours/project/month, up to 2 CU (8 GB RAM) autoscaling, 10 branches/project, 5 GB/month network transfer. Comfortably covers the ~1,000–5,000-account target. Supabase remains an untested fallback if Neon's limits change. |
| Backend hosting | Render free tier | **Confirmed 16 Sep 2026:** free web services get 512 MB RAM and spin down on inactivity (matches the "cold starts" NFR above); Render itself doesn't publish an exact monthly-hours cap on its pricing page, so re-check Render's own free-tier docs (render.com/docs/free) right before deploying, since usage limits are provider-set and have changed before. Koyeb remains an untested fallback. |
| Auth | Google Cloud OAuth client | Free |

### 6.2 Core data model (sketch)
- `User(id, email, displayName, unitCap, theme, shareToken?)`
- `Curriculum(id, userId, programLabel?, totalUnits, totalExtraUnits, parseConfidence: NORMAL|LOW_CONFIDENCE, createdAt)` — `programLabel` is free-text display metadata only (FR-2.1); it no longer selects a parser implementation
- `Course(id, curriculumId, code, title, units, extraUnits, year, term, slotType: NONE|MANDATORY_ELECTIVE, exemptionNote?, state)` — Minor Program slots are never rows here at all (FR-2.8, FR-6.2)
- `Prerequisite(courseId, requiresCourseId, type: HARD|SOFT|COREQ)` — exemption notes are **not** rows here; they live on `Course.exemptionNote` (FR-2.7)
- `Attempt(id, courseId, grade?, professor?, result: PASSED|FAILED|WITHDRAWN|INCOMPLETE, createdAt)`

## 7. User Journeys

**J1 — First-time setup**
1. Visit site → "Sign in with Google" → a non-DLSU account is rejected with a clear message.
2. Upload flowchart PDF (optionally label it with a program name).
3. Review screen: fix two misread prerequisites and one unit value → Save. If the parser flagged low confidence (FR-2.11), a warning banner asks Matthew to double-check every row first.
4. Land on the quest map with the hero at 0%.

**J2 — Planning enlistment**
1. Set unit cap to 18.
2. Click an eligible course → "Start course" → it becomes *being taken* and the load counter shows 3/18.
3. Try a course with an unfinished hard prerequisite → blocked, with "Requires CCPROG2."
4. Try a course with an unmet soft prerequisite → warning → proceed.
5. Try adding a course that pushes the load to 21/18 → blocked.

**J3 — End of term**
1. Open a *being taken* course → "Finish" → enter grade 3.5 and the professor's name → the node turns finished, the CGPA updates, and the progress bar and hero advance.
2. Another course → grade 0.0 → state *failed*, attempt logged, **CGPA drops to include the 0.0** → later "Retake" sets it back to *being taken*; once that retake is finished, the new grade replaces the 0.0 in the CGPA.
3. A pass/fail milestone like NSTP-01 → "Mark passed" (no grade field shown) → node turns passed, progress bar advances, CGPA unaffected.
4. A course the student dropped mid-term → "Withdraw" → node turns withdrawn, logged distinctly from failed, CGPA unaffected, hard-prerequisite dependents stay locked.

**J4 — Elective**
1. Click "GE Elective" slot → enter the course code, title and 3 units → start the course.

**J5 — Sharing**
1. Settings → "Create share link" → copy → the viewer sees the map and progress without grades or CGPA → the owner revokes it later.

## 8. Success Metrics

| Metric | Target (first term after launch) |
|--------|---------------------------|
| Registered DLSU accounts | 300+ |
| Upload → saved graph completion rate | ≥ 70% |
| Parser accuracy (courses + links correct before edits) | ≥ 90% per college's sample, using the single universal parser |
| Avg manual edits per upload | ≤ 5 |
| Weekly active users during enlistment weeks | ≥ 40% of registered |
| "Report parsing issue" or feedback volume per college | Trending down after fixes |
| Hosting cost | $0 |

## 9. Timeline (~1 term, about 14 weeks, part-time alongside classes)

| Weeks | Milestone |
|-------|-----------|
| 1–2 | **Research and foundations:** collect sample flowchart PDFs from every college and confirm each includes a Program Checklist table (this is where the "one parser fits all" bet starts getting tested against reality). Set up the repo, Spring Boot skeleton, React app, Postgres, and Google login with domain restriction. |
| 3–5 | **Parser v1:** PDFBox checklist-table extraction (column-aware, FR-2.6), the single universal parser (FR-2.5) built against the CCS sample first, `(H)/(S)/(C)/(E)` marker parsing, the parse-confidence check (FR-2.11), test fixtures. |
| 6–7 | **Review and edit screen**, data model (7-state course lifecycle), save flow, validation (cycles, dangling prerequisites, exemption-note exceptions), the low-confidence warning banner (FR-3.5). |
| 8–9 | **Graph and rules:** React Flow map with auto layout, full state transitions incl. incomplete/withdrawn/passed, hard/co/soft/exemption handling, unit cap (with extra units), grade/attempt logging, CGPA calculation (incl. the failed-grade rule, FR-5.4). |
| 10–11 | **Broaden test-fixture coverage:** run the universal parser against sample PDFs from several other colleges and fix any real layout divergences found — the first real test of the single-parser bet. Game layer (biomes incl. cloudy/sky tier, progress bar, hero), light/dark mode. |
| 12 | Share link, privacy note, account deletion, accessibility pass, mobile list view. |
| 13 | Closed beta with blockmates. Fix parser issues. |
| 14 | **Public launch** (DLSU student groups). |

*Main risk, revised:* Matthew has bet on one universal parser working for every college, based on a single confirmed CCS sample. The parse-confidence check (FR-2.11) and manual editor (FR-3) are the safety net if that bet doesn't hold for some college; Weeks 10–11 are the first real test of it. If a college's checklist table turns out to diverge in a way the universal parser can't handle, decide then whether to special-case that one college's extraction or launch without it (§10, "still open").

## 10. Open Questions and Assumptions

### Decision log

**Round 1 — resolved 16 Sep 2026, grounded against the CCS BSCS-NIS sample PDF:**
1. Parse the checklist table (not the diagram); confirmed real PDFs contain both.
2. Parenthetical/extra units count toward the progress bar and unit cap.
3. Incomplete and Pass/Fail each got their own course state; Withdrawn behaves like Failed for prerequisites but is logged distinctly.
4. A cloudy/sky biome is inserted before the final (always-lava) year for programs longer than 4 years.
5. Shifting/curriculum-replacement stays out of scope.
6. Render's and Neon's current free-tier terms were confirmed (§6.1).
7. Pixel art is self-drawn — no licensing risk.
8. A "not affiliated with DLSU" disclaimer is planned, no official marks used.

**Round 2 — resolved 16 Sep 2026, Matthew's decisions on parser scope, CGPA, and minor programs:**
9. **One universal parser, not one per college** (FR-2.5). Matthew's call, based on his own read of DLSU's flowcharts — accepted, but it's a bet made on a single confirmed sample (CCS). Mitigated with a parse-confidence check (FR-2.11) and a low-confidence warning banner (FR-3.5) rather than assuming every college will parse cleanly just because the architecture no longer has per-college isolation.
10. **A failing grade pulls the CGPA down** until the course is retaken and passed (FR-5.4) — matches DLSU's own computation. Withdrawn stays excluded from CGPA (a "W" isn't a grade point), which is a related but separate rule worth keeping distinct.
11. **Minor Program slots are out of scope for MVP, full stop** — not displayed, not tracked, skipped entirely at parse time (FR-2.8, FR-6.2). This is simpler to build than v2's "display but don't interact" middle ground.

### Still open
1. **If Weeks 10–11 testing (§9) finds a college whose checklist table doesn't fit the universal parser**, does Matthew want to special-case that college's extraction, or launch without it? Not a blocker now, but worth deciding before it's a Week-11 surprise.
2. Supabase (Neon alternative) and Koyeb (Render alternative) were not independently re-verified — only Neon and Render were checked (§6.1).

### Assumptions
- DLSU student emails are Google Workspace accounts on `dlsu.edu.ph` (stated by the user).
- Official flowcharts are distributed as PDFs with selectable text, and — per the CCS sample and Matthew's confirmation — include a text-based checklist table across every college.
- Users can edit their own data freely. The app is a planning aid, not an official record, so it doesn't verify grades.
- One active curriculum per user in the MVP.
- "Current state only": no term history, so the unit cap applies to what is `BEING_TAKEN` right now, and GPA is cumulative-only (no per-term breakdown).
- DLSU runs a trimester system (3 terms/academic year), matching the sample PDF; not independently verified beyond that one sample.
