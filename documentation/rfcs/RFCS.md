# RFCS — Anevaino Implementation Roadmap

| | |
|---|---|
| **Sources** | `PRD.md` Draft v3, `FEATURES.md` (F1–F54), `RULES.md` (all 16 Sep 2026) |
| **Generated** | 2026-09-16 |
| **RFCs** | 18 (RFC-001 – RFC-018) |
| **Location** | `documentation/rfcs/` (RULES §1.2) — see C1 |

## How to use this

- Implement RFCs in numeric order if you're working alone. Every RFC is implementable once its **declared predecessors** are done, so independent branches can run in parallel.
- Implement each RFC by running the RFC-implementation step (`implement-rfc`) with that RFC's id, e.g. `implement-rfc RFC-003`. There are deliberately no per-RFC prompt files.
- Acceptance criteria are the spec. Anything in an RFC's prose that isn't in its criteria is commentary.
- Before any code is written, do the **cold-read check** at the end of this file.

## Product type and scoped checks

Classified as a **web app** (SPA + REST backend), same as PRD/RULES.
**Applied:** auth/session, ownership and API security, privacy (RA 10173 principles), hosting/cost limits, performance budgets, accessibility (WCAG 2.1 AA), responsive design, state management, data model/migrations, testing.
**Skipped (not applicable):** mobile-store/ASO, library concerns (semver, public API surface, bundle size for consumers, peer deps), CLI ergonomics, regulatory certification, monetization, horizontal scaling/multi-region, caching layers (single instance; caching is explicitly limited by R-1.4). RFCs that have no schema, API or UI say so in a "Sections omitted" line instead of emitting empty sections.

---

## 1. Roadmap

| # | RFC | Phase (RULES §5.2) | Complexity | Features | Predecessors | Successors |
|---|---|---|---|---|---|---|
| 001 | [Repository, CI and Deployment Foundations](RFC-001-Foundations.md) | 1 | Medium | F40 (health API), F44 (baseline) | — | 002, 003, 006, 008 |
| 002 | [Google Sign-in, DLSU Domain Restriction and Session](RFC-002-Authentication-and-Session.md) | 1 | Medium | F1, F2, F3, F44 | 001 | 005 |
| 003 | [Parser I: PDF Loading and Column-Aware Extraction](RFC-003-Parser-Column-Aware-Extraction.md) | 2 | High | F5, F7 | 001 | 004 |
| 004 | [Parser II: Fields, Markers, Warnings, Confidence](RFC-004-Parser-Semantics-and-Confidence.md) | 2 | High | F8–F13, F15 (harness) | 003 | 005, 013 |
| 005 | [Curriculum Schema and Upload/Parse Endpoint](RFC-005-Curriculum-Schema-and-Upload-API.md) | 2 | Medium | F5, F11, F13, F14, F44 | 002, 004 | 009 |
| 006 | [Rules Engine and Shared Rule Vectors](RFC-006-Rules-Engine-and-Rule-Vectors.md) | 4 | High | F21–F24, F26, F27, F29, F33 | 001 | 007, 010 |
| 007 | [CGPA and Progress Calculators](RFC-007-CGPA-and-Progress-Calculators.md) | 4 | Low | F32, F36 | 006 | 010 |
| 008 | [Course Graph Component](RFC-008-Course-Graph-Component.md) | 3–4 (C7) | High | F34 | 001 | 009, 011 |
| 009 | [Upload Page, Review & Manual Editor, Save Validation](RFC-009-Upload-Review-and-Save.md) | 3 | High | F6, F11–F13, F16–F19, F44 | 005, 008 | 010, 016 |
| 010 | [Course Actions, Attempts, Map and Settings API](RFC-010-Course-Actions-Attempts-and-Settings-API.md) | 4 | High | F21, F23, F26, F27, F30–F33, F37, F44 | 006, 007, 009 | 011, 016 |
| 011 | [Quest Map and Course Interaction UI](RFC-011-Quest-Map-and-Course-Interaction-UI.md) | 4 | High | F22–F33 (UI) | 008, 010 | 012, 014, 015 |
| 012 | [Biomes, Progress Bar, Theme, Cold-Start Screen](RFC-012-Game-Layer-Theme-and-Cold-Start.md) | 5 | Medium | F35, F36, F37, F40 | 011 | 014, 017 |
| 013 | [Multi-College Fixture Suite and Confidence Tuning](RFC-013-Multi-College-Fixture-Suite.md) | 5 | Medium | F13, F15 | 004 | 018 |
| 014 | [List View and Accessibility/Responsive Pass](RFC-014-List-View-and-Accessibility-Pass.md) | 6 | Medium | F28, F34, F39 | 011, 012 | 018 |
| 015 | [Account Deletion, Privacy Note, Disclaimer](RFC-015-Account-Deletion-and-Privacy.md) | 6 | Medium | F4, F43 | 011 | 017, 018 |
| 016 | [Reopen Curriculum Editor](RFC-016-Reopen-Curriculum-Editor.md) *(Should)* | 6 | Medium | F20 | 009, 010 | 018 |
| 017 | [Read-Only Share Link](RFC-017-Share-Link.md) *(Should)* | 6 | Medium | F4 (token), F41, F42, F44 | 012, 015 | 018 |
| 018 | [Launch Readiness Gate and Celebration](RFC-018-Launch-Readiness-and-Celebration.md) | 7 | Medium | F29, F38, F44 (verification) | 013, 014, 015, 016, 017 | — |

Complexity count: High 7 · Medium 10 · Low 1 (recounted in §5).

### 1.1 Dependency graph (textual)

```
001 ─┬─ 002 ───────────────┐
     ├─ 003 ── 004 ─┬──── 005 ── 009 ─┬─ 010 ── 011 ─┬─ 012 ─┬─ 014 ──────────┐
     │              │                 │   ▲          │       └─ 017 ──┐       │
     │              │                 │   │          └─ 015 ───────┘  │       │
     ├─ 008 ────────┼─────────────────┘   │           (015 → 017)     │       │
     │   └──────────┼─────────────────────┼──────────► 011            │       │
     └─ 006 ── 007 ─┼─────────────────────┘                           │       │
                    └──── 013 ────────────────────────────────────────┴──► 018 ◄┘
                                             009, 010 ── 016 ───────────────► 018
```
Edges (predecessor → successor): 001→002, 001→003, 001→006, 001→008, 002→005, 003→004, 004→005, 004→013, 005→009, 006→007, 006→010, 007→010, 008→009, 008→011, 009→010, 009→016, 010→011, 010→016, 011→012, 011→014, 011→015, 012→014, 012→017, 013→018, 014→018, 015→017, 015→018, 016→018, 017→018.

### 1.2 Critical path and parallel tracks

**Critical path** (longest chain, blocks launch): 001 → 003 → 004 → 005 → 009 → 010 → 011 → 012 → 014 → 018. The parser (003/004) sits on it: a slip there delays everything user-facing.

Tracks that can run in parallel after 001:
- **Auth:** 002 (merges into 005).
- **Parser:** 003 → 004 (merges into 005; 004 also feeds 013, which can start collecting fixtures in week 1).
- **Rules:** 006 → 007 (pure code, no UI or DB; merges into 010).
- **Graph:** 008 (merges into 009 and 011).
- After 011: 012, 015 and later 016 are independent of each other.

Solo implementer: follow the numbers. R-5.1 still applies — Should-haves 016 and 017 start only after the phase-6 Musts (014, 015) are done.

---

## 2. Feature coverage (F1–F44 → RFCs)

Primary owner in **bold**; others implement a named part (each RFC's header says which).

| F | RFCs | F | RFCs | F | RFCs |
|---|---|---|---|---|---|
| F1 | **002** | F16 | **009** | F31 | **010**, 011 |
| F2 | **002** | F17 | **009** | F32 | **007**, 010, 011 |
| F3 | **002** | F18 | **009** | F33 | 006, **010**, 011 |
| F4 | **015**, 017 | F19 | **009** | F34 | **008**, 014 |
| F5 | **003**, 005 | F20 | **016** | F35 | **012** |
| F6 | **009** | F21 | **006**, 010 | F36 | 007, **012** |
| F7 | **003** | F22 | **006**, 011 | F37 | 010, **012** |
| F8 | **004** | F23 | **006**, 010, 011 | F38 | **018** |
| F9 | **004** | F24 | **006**, 011 | F39 | **014** |
| F10 | **004** | F25 | **011** | F40 | 001, **012** |
| F11 | **004**, 005, 009 | F26 | 006, 010, **011** | F41 | **017** |
| F12 | **004**, 009 | F27 | **006**, 010, 011 | F42 | **017** |
| F13 | **004**, 005, 009, 013 | F28 | **011**, 014 | F43 | **015** |
| F14 | **005** | F29 | **006**, 011, 018 | F44 | 001, 002, 005, 009, 010, 017, **018** |
| F15 | 004, **013** | F30 | **010**, 011 | | |

F45–F54 (Won't) appear in no RFC (R-0.3).

---

## 3. Decisions, conflicts and open questions

### 3.1 Decisions made by Matthew during RFC generation (16 Sep 2026)

| # | Decision | Where |
|---|---|---|
| D1 | Failing a course has a kind: **graded** (0.0, counts in CGPA) or **pass/fail** (no grade, excluded). Follows RULES Q4's default over PRD FR-4/FR-5.1/FR-5.2 wording. | 006, 007, 010, 011 |
| D2 | Resolving INCOMPLETE **updates the same attempt**. | 006, 010 |
| D3 | "Undo result" **deletes** the undone attempt. | 006, 010, 011 |

**Docs to update (R-6.5):** PRD FR-4 (FAILED definition), FR-5.1/FR-5.2 ("FAILED implies 0.0" → graded failures only), FR-5.3 (Attempt enum + D2/D3), §6.2 data model (see 3.3); RULES §7 mark Q1 and Q4 resolved.

### 3.2 Conflicts between artifacts

Authority: PRD > FEATURES > RULES > RFCs. None resolved silently.

| # | Conflict | Followed | Why / flag |
|---|---|---|---|
| C1 | Skill template says output to `RFCs/`; RULES §1.2 says `documentation/rfcs/` | RULES | Project-specific convention wins over generic guidance |
| C2 | PRD FR-4/FR-5.2: FAILED always 0.0 in CGPA; RULES Q4 default: pass/fail failures excluded | RULES default, **by Matthew's explicit decision (D1)** | PRD must be corrected |
| C3 | PRD §6.2/FR-5.3 `Attempt.result` lacks FINISHED; FR-5.4 needs it | RULES Q1 default (add FINISHED) | Internal PRD inconsistency; fix PRD |
| C4 | R-2.10 names the table `user`, a PostgreSQL reserved word | `app_user` (002) | Flag RULES wording |
| C5 | FEATURES F21 lists no transition out of FINISHED/PASSED, yet F27 assumes one; also none out of BEING_TAKEN except results | RULES Q4 "Undo result" + new Q7 "Cancel start" | FEATURES to be updated once Q7 is decided |
| C6 | PRD §6.2 `User.shareToken`; R-2.21 store only a hash | RULES (017) | Consequence: the link can't be shown again after creation |
| C7 | RULES phases put F16 (review with graph preview) in phase 3 but F34 (graph) in phase 4 | Graph built in RFC-008 before 009 | Internal RULES ordering issue; phase table should list F34 in phase 3 |
| C8 | PRD FR-2.4 calls the diagram page a "fallback… if the confidence check fires", but also says diagram parsing is out of scope | Out of scope: LOW confidence shows a banner only; no-checklist PDFs are rejected (F11, F50) | Reword FR-2.4 |

### 3.3 Additions to the PRD §6.2 data model introduced by the RFCs

`Course.slotLabel` (005 — FR-6.1 "keeps its category label"), `Course.slotFilled` (010), `Attempt.sequenceNo` (010), `Attempt.result += FINISHED` (C3), `Curriculum.updatedAt` (005), `User.shareTokenHash` + `shareTokenCreatedAt` (017, C6), table name `app_user` (C4), and a `course/` backend package not in RULES §2.2 (010).

### 3.4 Open questions — provisional defaults used (mark code with `// OPEN-QUESTION(Q<n>)`, R-6.3)

| # | Question | Provisional default in the RFCs | Status | RFCs |
|---|---|---|---|---|
| Q1 | Attempt enum lacks FINISHED | Add it | Effectively settled; update PRD | 006, 010 |
| Q2 | Mutual co-requisites vs cycle check | COREQ excluded from cycles; start together in one action | Open | 006, 009, 011 |
| Q3 | Do extra units weight CGPA? | No — `units` only | Open | 007 |
| Q4 | Failed pass/fail in CGPA; revert path | Resolved by D1 + D3; "Undo result" transition kept as the revert | CGPA part **resolved**; undo transition still marked | 006, 010, 011 |
| Q5 | Biomes for < 4-year programmes | Grass first, lava last, ice → desert in between | Open | 012 |
| Q6 | College that doesn't fit the universal parser | Don't special-case; mark unsupported, escalate | Open (PRD §10 still-open 1) | 013 |
| Q7 *(new)* | How does a student undo an accidental "Start"? | "Cancel start": BEING_TAKEN → state implied by the latest attempt (none → NOT_TAKEN), no attempt created | **New — needs Matthew** | 006, 010, 011 |
| Q8 *(new)* | How does a student replace their one curriculum (re-upload)? | Allowed, with confirmation; replaces the old curriculum and deletes its history | **New — needs Matthew** | 009 |
| Q9 *(new)* | Where do totals come from for manual builds? | Totals editable on review screen, "Use sum of courses" button | **New — needs Matthew** | 009 |
| Q10 *(new)* | Does F27's downstream warning cover only reverts? | Any action that makes an already-started dependent's HARD (or active COREQ) requirement unmet warns and needs acknowledgement | **New — needs Matthew** | 006, 010, 011 |
| Q11 *(new)* | Biome for a 1-year curriculum (Year 1 grass vs final year lava) | Lava ("final year" wins) | **New — minor** | 012 |

### 3.5 Gaps noted, not specced

| # | Gap | Handling |
|---|---|---|
| G1 | Elective range rows like `NSELEC1-3` — one slot or three? | Parser keeps one row + warning; student splits in the editor (004, 009). Check the CCS PDF and decide. |
| G2 | PRD §8 measures "Report parsing issue" feedback, but no FR/feature defines that button | Not built (R-6.1); add an F-ID if wanted (018) |
| G3 | G2 ("0 invalid being-taken states") vs recording real outcomes (withdrawing from a co-req partner) | Engine guarantees validity on *entering* BEING_TAKEN; later outcomes warn (Q10) (006) |
| G4 | Pixel art assets (5 biome tiles, 2 castles, hero, sparkle) must be drawn by Matthew | Blocking input for 012 and 018 |
| G5 | CCS fixture `expected.json` must be hand-written from the PDF before 003 can pass | Blocking input for 003 |

---

## 4. Implementation constraints (apply to every RFC)

- **Stack & versions:** exactly RULES §1.1 (JDK 25, Kotlin 2.4.20, Spring Boot 4.1.1, PDFBox 3.0.8, React 19.3.0, TypeScript 6.0.3, Vite 8.3.0, React Flow 12.11.6, dagre 3.1.1, Tailwind 4.3.3 …). Check the registry before any addition (R-1.2).
- **Coding standards:** RULES §2 naming/organization; pure `parser/`, `rules/`, `grades/`, `domain/` (R-2.3, R-2.6); Problem Details errors (R-2.13); no TODOs/placeholders in merged code except `OPEN-QUESTION` markers (R-6.3); PRs < 400 lines where possible (R-6.7).
- **Performance budgets:** parse ≤ 80 courses < 5 s; local state change < 300 ms; save < 1 s warm; smooth graph at ~80 nodes; cold start ≤ ~60 s behind the wake screen (R-2.29, R-2.32).
- **Compatibility:** current evergreen Chrome, Edge, Firefox, Safari; desktop-first, usable at 360 px (R-4.14).
- **Hosting limits:** Render 512 MB (R-1.4), Neon free tier, $0/month (R-1.3).
- **Compliance:** not a regulated system of record; privacy note follows RA 10173 principles (015). No DLSU marks.
- **Testing:** RULES §4.1 coverage floors; Testcontainers Postgres, never H2 (R-4.3); each acceptance criterion maps to a named test (R-4.1).

---

## 5. Self-check

I ran these checks on the finished RFC set, mostly with a script over the files rather than from memory:

- **Dependency symmetry:** every RFC's declared predecessors list it as a successor and vice versa; every predecessor number is lower than the RFC's own (valid topological order). No mismatches found. The edge list in §1.1 was regenerated from the RFC headers.
- **Feature coverage:** every in-scope feature F1–F44 appears in at least one RFC header; no Won't-have feature (F45–F54) appears in any. The §2 table was built from the script's output, not typed from memory.
- **File-structure ↔ acceptance criteria:** every RFC has a final criterion requiring every file in its "File structure" section to exist, and most files are also named explicitly. Files covered only by that catch-all criterion are ones whose behaviour is tested by other criteria, such as `RulesEngine.kt` and the vector runners.
- **AC ids:** every criterion id matches its RFC number.
- **Cross-references fixed during the check:**
  - RFC-014's test section pointed at "§2.3" for per-state actions, but in RFC-014 that section is the app-wide pass. The table is in RFC-011 §2.3, and the reference now says so.
  - RFC-007's rounding example had wrong arithmetic (22.5/7). It now uses 22/7 → 3.14, and a separate exact-half case (25/8 = 3.125 → 3.13 under half-up) proves the rounding rule.
  - RFC-005 at one point said `CourseState` would live in `rules/` and be mapped as a String. It now consistently lives in `curriculum/`, with an exhaustive mapping to the `rules/` enum in RFC-010.
  - An RFC-005 criterion had been tagged with the Won't-have F54; the tag was removed.
- **Complexity count (recounted):** High = 003, 004, 006, 008, 009, 010, 011 → **7**; Medium = 001, 002, 005, 012, 013, 014, 015, 016, 017, 018 → **10**; Low = 007 → **1**; total 18. The first draft of the summary line under the roadmap said "High 6"; that line was corrected.
- **Tables vs each other:** the roadmap table, §1.1 edges, §2 coverage and each RFC header agree. The phase column follows RULES except for C7 (graph moved earlier) and F40's split (health API in 001, UI in 012 per RULES phase 5).

## 6. Cold-read check (do this before writing code)

Give each RFC to a **fresh session, ideally a different model**, with only `PRD.md`, `FEATURES.md`, `RULES.md` and that one RFC. Ask one question: **"What would you have to guess to implement this?"** Fix everything on that list before implementation starts. Don't answer the list from memory of what the RFC meant, because that memory is exactly what hides the gaps. Start with RFC-003, RFC-006 and RFC-010, which carry the most risk.
