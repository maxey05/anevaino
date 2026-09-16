# RFC-009 — Upload Page, Review & Manual Editor, and Save Validation

| | |
|---|---|
| **Status** | Draft |
| **Complexity** | High |
| **Phase** | 3 — Review & edit (wk 6–7) |
| **Features** | F6, F11 (UI path), F12 (display), F13 (persisted), F16, F17, F18, F19, F44 (save endpoint) |
| **PRD** | FR-2.1, FR-2.4, FR-2.9, FR-3.1, FR-3.2, FR-3.3, FR-3.5, J1.2–J1.4 |
| **Predecessors:** | RFC-005, RFC-008 |
| **Successors:** | RFC-010, RFC-016 |

Sections omitted: none.

## 1. Summary

The first user-facing flow after sign-in: pick a PDF (optionally label the programme), see a review screen with an editable table, the graph preview and parse warnings, fix things, and save through validation that runs instantly in the browser and authoritatively on the server. A rejected PDF (no checklist) leads into the same editor with an empty curriculum. Low-confidence parses get the exact FR-3.5 banner.

## 2. Decisions and gaps handled here

| Ref | Handling |
|---|---|
| Q2 | Cycle detection uses HARD and SOFT edges only; COREQ edges are excluded (`OPEN-QUESTION(Q2)`) |
| Q8 *(new)* | A user who already has a curriculum may upload again; saving then **replaces** it (deleting its courses and attempt history) after an explicit confirmation. PRD says "one active flowchart" but never says how to change it. Provisional. |
| Q9 *(new)* | `totalUnits`/`totalExtraUnits` are editable on the review screen (pre-filled from the parse, or empty for a manual build, with a "Use sum of courses" button). F17 doesn't list them, but F36 can't work for manual builds without them. Provisional. |
| G1 | Elective range rows (`NSELEC1-3`) arrive as one row with a warning; the editor lets the student split them by adding rows. |

## 3. Interfaces

| Method | Path | Auth | Request | Success | Errors |
|---|---|---|---|---|---|
| POST | `/api/curricula` | session + CSRF | `SaveCurriculumRequest`, query `replace=true|false` (default false) | 201 `CurriculumSummaryDto` | 400 `curriculum/invalid`; 409 `curriculum/already-exists` |
| GET | `/api/curricula/current` | session | – | 200 `CurriculumSummaryDto` | 404 `common/not-found` |

```ts
SaveCurriculumRequest = {
  programLabel: string | null,                  // ≤ 100 chars, display only (F6)
  totalUnits: number, totalExtraUnits: number,  // integers ≥ 0 (Q9)
  parseConfidence: 'NORMAL' | 'LOW_CONFIDENCE', // 'NORMAL' for manual builds
  courses: { code, title, units, extraUnits, year, term, slotType, exemptionNote }[],   // ≤ 300
  prerequisites: { courseCode, requiresCode, type: 'HARD'|'SOFT'|'COREQ' }[]           // ≤ 1000
}
CurriculumSummaryDto = { id, programLabel, totalUnits, totalExtraUnits, parseConfidence, courseCount, createdAt, updatedAt }
```
`curriculum/invalid` extension: `errors: [{ code, message, courseCode?, requiresCode?, field?, cycle? }]` where `code` ∈
`EMPTY_CURRICULUM` ("Add at least one course."), `MISSING_FIELD` ("{field} is required for {courseCode}."), `OUT_OF_RANGE` ("{field} for {courseCode} must be {range}."), `DUPLICATE_CODE` ("{courseCode} appears more than once."), `DANGLING_PREREQUISITE` ("{courseCode} requires {requiresCode}, which isn't in this curriculum."), `SELF_PREREQUISITE`, `DUPLICATE_PREREQUISITE` ("{courseCode} lists {requiresCode} more than once."), `PREREQUISITE_CYCLE` ("These courses require each other in a loop: A → B → A."), `TOO_LARGE`.
Ranges: code 1–32 chars; title 1–200; units/extraUnits 0–30; year 1–10; term 1–3; totals 0–1000.

## 4. Technical approach

### 4.1 Validation (shared semantics, two implementations)
`backend/.../curriculum/validation/CurriculumValidator.kt` (pure) and `frontend/src/features/review/validateDraft.ts` implement:
```
errors = []
if courses empty → EMPTY_CURRICULUM
per course: required fields non-null/non-blank → MISSING_FIELD; ranges → OUT_OF_RANGE
codes compared case-insensitively after trim → DUPLICATE_CODE (one error listing the code)
per edge: courseCode or requiresCode not a course → DANGLING_PREREQUISITE  (exemption notes are a course field, never checked — FR-2.7)
          courseCode == requiresCode → SELF_PREREQUISITE
          same (courseCode, requiresCode) twice → DUPLICATE_PREREQUISITE
cycle: DFS with colours over HARD+SOFT edges (Q2); first back-edge found → PREREQUISITE_CYCLE with the path
```
Errors are ordered: curriculum-level, then by course table order, then edges. Both implementations share `shared/validation-vectors/*.json` (same runner pattern as RFC-006, smaller set: one vector per error code + `coreq-mutual-allowed` + `exemption-note-not-checked` + `valid-ccs-shaped`). This goes beyond R-2.8's minimum (which covers F21–F26) because F18 requires "the same validation runs on the backend".

### 4.2 Save
`CurriculumService.save(userId, request, replace)` in one transaction:
1. Validate → `ValidationFailure(curriculum/invalid, errors)`.
2. Existing curriculum for user: if `!replace` → 409; else delete it (cascade) — `OPEN-QUESTION(Q8)`.
3. Insert curriculum; insert courses (`state = NOT_TAKEN`; `slot_label = code` when `slotType == MANDATORY_ELECTIVE`); insert prerequisites resolved by code → id.
4. Log `INFO curriculum.saved userId courses edges confidence replaced`.

### 4.3 Frontend flow
```
/upload  UploadPage
   ├─ program label input (F6) with <datalist> from knownPrograms.ts
   ├─ file input (accept="application/pdf") + drag-and-drop; client-side 10 MB pre-check
   ├─ if me.hasCurriculum: notice "Uploading a new flowchart replaces your current map and deletes its course history."
   ├─ POST /api/uploads → success: ReviewDraft.fromParsed(dto, label) → navigate /review
   └─ problem: show detail text; for parse/no-checklist and parse/timeout also a button
        "Build my map by hand" → ReviewDraft.empty(label) → /review                      (F11 → F17)
/review  ReviewPage  (redirects to /upload if no draft)
   ├─ LowConfidenceBanner if draft.parseConfidence == LOW_CONFIDENCE                     (F19)
   ├─ WarningsPanel: parse warnings (F12) + live validation errors, each links to its row
   ├─ TotalsEditor (Q9)
   ├─ CourseTable (editable, F17)   |   GraphPreview = CourseGraph interactive=false (F16)
   └─ Save button (disabled while validateDraft has errors)
        → POST /api/curricula?replace=me.hasCurriculum   (confirm dialog first when replacing)
        → 201: clear draft, invalidate `me` and `curriculum` queries, navigate /map
        → 400: merge server errors into WarningsPanel (server wins, R-2.7)
```
`ReviewDraft` lives in a React context (`ReviewDraftProvider`) and is mirrored to `sessionStorage` key `anevaino.reviewDraft` (try/catch; contains no grades) so a refresh doesn't lose edits; cleared on save or on a new upload.

Draft course rows carry a client `rowId` (crypto.randomUUID) so edits to `code` don't break prerequisite links in the table; links are stored by `rowId` in the draft and converted to codes only in the save request. Parse-time edges whose `requiresCode` matched no course are kept as *dangling links* shown with an error chip until fixed or removed.

### 4.4 UI details
- **LowConfidenceBanner** (F19): exact text "This flowchart's layout looks different than expected — please double-check every row before saving." (R-6.1), `role="alert"`, warning-triangle icon, thick amber left border and amber background token in both themes — visually unlike the neutral success header shown for NORMAL parses ("Parsed N courses. Review them before saving.").
- **CourseTable**: semantic `<table>`; one row per course; inputs: code, title, units, extra units, year (number), term (select 1–3), slot type (select: "Regular course" / "Elective slot"), exemption note (text), prerequisites cell with chips `CODE · Hard|Soft|Co-req` (type select + remove button) and an "Add prerequisite" combobox listing other codes. Row actions: delete (removes the row and all links to/from it, then announces "Removed CCPROG1 and 3 prerequisite links" in a polite live region). "Add course" appends an empty row and focuses its code input. Rows with errors get an error icon + `aria-invalid` on the offending inputs and `id="course-row-{rowId}"` for warning links.
- **WarningsPanel**: grouped "Needs fixing before save" (validation) and "Check these" (parse warnings); each item is a button that scrolls to and focuses its row.
- **Keyboard:** everything reachable by Tab; the graph preview is supplementary.
- **Mobile (< md):** preview collapses behind a "Show graph preview" toggle; table scrolls horizontally inside its container only (R-4.16).

## 5. Implementation details

### File structure
```
backend/src/main/kotlin/ph/anevaino/curriculum/validation/CurriculumValidator.kt
backend/src/main/kotlin/ph/anevaino/curriculum/validation/ValidationError.kt
backend/src/main/kotlin/ph/anevaino/curriculum/CurriculumController.kt
backend/src/main/kotlin/ph/anevaino/curriculum/CurriculumService.kt
backend/src/main/kotlin/ph/anevaino/curriculum/dto/SaveCurriculumRequest.kt
backend/src/main/kotlin/ph/anevaino/curriculum/dto/CurriculumSummaryDto.kt
backend/src/test/kotlin/ph/anevaino/curriculum/validation/CurriculumValidatorTest.kt
backend/src/test/kotlin/ph/anevaino/curriculum/validation/ValidationVectorTest.kt
backend/src/test/kotlin/ph/anevaino/curriculum/CurriculumControllerIntegrationTest.kt
shared/validation-vectors/*.json
shared/validation-vectors/schema.json
frontend/src/api/schemas/parsedCurriculum.ts
frontend/src/api/schemas/curriculum.ts
frontend/src/api/uploads.ts
frontend/src/api/curricula.ts
frontend/src/features/upload/UploadPage.tsx
frontend/src/features/upload/knownPrograms.ts
frontend/src/features/upload/UploadPage.test.tsx
frontend/src/features/review/ReviewDraft.ts
frontend/src/features/review/ReviewDraftProvider.tsx
frontend/src/features/review/validateDraft.ts
frontend/src/features/review/validateDraft.vectors.test.ts
frontend/src/features/review/ReviewPage.tsx
frontend/src/features/review/LowConfidenceBanner.tsx
frontend/src/features/review/WarningsPanel.tsx
frontend/src/features/review/TotalsEditor.tsx
frontend/src/features/review/CourseTable.tsx
frontend/src/features/review/PrerequisiteChips.tsx
frontend/src/features/review/GraphPreview.tsx
frontend/src/features/review/ReviewPage.test.tsx
frontend/e2e/j1-first-time-setup.spec.ts
```
`knownPrograms.ts` lists only programmes that have a fixture in `fixtures/flowcharts/` (initially the CCS BSCS-NIS programme); RFC-013 extends it. No invented programme names (R-6.3).

### Testing
- `CurriculumValidatorTest` + vectors: every error code; mutual COREQ allowed; HARD cycle A→B→A rejected with path; SOFT cycle rejected; exemption note `BASMATH` not checked; case-insensitive duplicates.
- `CurriculumControllerIntegrationTest`: valid save → 201 and rows with `state NOT_TAKEN`, `slot_label` set for elective slots; invalid → 400 with ordered errors; second save without replace → 409; with `replace=true` → old rows gone, new present; unauthenticated 401; another user's `GET current` never returns someone else's (each user sees only their own).
- `UploadPage.test.tsx` (MSW): size pre-check; scanned message shown verbatim; no-checklist shows "Build my map by hand" and opens an empty review; replacement notice when `hasCurriculum`.
- `ReviewPage.test.tsx`: LOW banner text + `role=alert` present only for LOW; warning link focuses row; adding/deleting rows and links; code rename keeps links; save disabled with errors; server 400 errors displayed; sessionStorage restore after remount.
- `j1-first-time-setup.spec.ts`: sign-in (mocked) → upload fixture (API mocked with CCS parsed JSON) → fix a unit value and a prerequisite → save → lands on `/map`. Axe scan on `/upload` and `/review` (no serious/critical).

## 6. Considerations

- **Rules:** R-2.1, R-2.7, R-2.10, R-2.12, R-2.13, R-2.19, R-2.25, R-4.1, R-4.7, R-4.9–R-4.12, R-4.14–R-4.16, R-6.1, R-6.3.
- **Security:** request limits (300 courses / 1000 edges, 1 MB JSON) prevent oversized drafts; ownership by `userId` from the session only.
- **Edge cases:** user renames an elective slot's code before saving — `slot_label` takes the code at save time; parse warnings are not persisted (reopening later via RFC-016 shows none).

## 7. Acceptance criteria

- **AC-009.1 [F6]** The upload page has an optional programme label (free text ≤ 100 chars with suggestions from `knownPrograms.ts`); it is sent only in the save request, stored as `program_label`, and never sent to `/api/uploads`.
- **AC-009.2 [F16]** After a successful upload the user lands on `/review` showing an editable course table and a read-only graph preview (`CourseGraph interactive=false`); nothing is persisted until Save.
- **AC-009.3 [F12]** All parse warnings from the upload response appear in the warnings panel, each linking to (scrolling to and focusing) its course row where `courseCode` is known.
- **AC-009.4 [F19]** For `LOW_CONFIDENCE` drafts a `role="alert"` banner shows exactly "This flowchart's layout looks different than expected — please double-check every row before saving." with a warning icon and styling distinct from the normal-parse header; it is absent for `NORMAL`.
- **AC-009.5 [F17]** Users can add, edit and delete courses (code, title, units, extra units, year, term, slot type, exemption note) and add/remove prerequisite links and change their type; renaming a code preserves its links; deleting a course removes its links and announces it.
- **AC-009.6 [F11, F17]** For `parse/no-checklist` and `parse/timeout`, the upload page offers "Build my map by hand", which opens the review screen with an empty curriculum that can be built and saved.
- **AC-009.7 [F18]** Save is blocked on every §3 error code — duplicates, missing/out-of-range fields, dangling and self prerequisites, duplicate links, HARD/SOFT cycles, empty curriculum — with each error pointing at its row; exemption notes are never reference-checked; mutual COREQ links are allowed (`OPEN-QUESTION(Q2)`).
- **AC-009.8 [F18]** `CurriculumValidator.kt` and `validateDraft.ts` pass the same `shared/validation-vectors/`, and the server returns 400 `curriculum/invalid` with the ordered `errors` list, which the UI displays.
- **AC-009.9 [F13]** The saved curriculum stores the draft's `parseConfidence` (`NORMAL` for manual builds).
- **AC-009.10 [Q9]** Totals are editable with a "Use sum of courses" button, required on save, and stored as `total_units`/`total_extra_units`.
- **AC-009.11 [Q8]** Without `replace=true` a second save returns 409 `curriculum/already-exists`; the UI shows the replacement notice on `/upload`, asks for confirmation before saving, and a replace deletes the old curriculum and its dependents in the same transaction (`OPEN-QUESTION(Q8)`).
- **AC-009.12 [F44]** `POST /api/curricula` and `GET /api/curricula/current` operate only on the session user's data (integration test with two users).
- **AC-009.13** Saved courses start `NOT_TAKEN`, elective slots get `slot_label` = their code at save, and prerequisites are stored by course id.
- **AC-009.14** The review draft survives a page refresh via `sessionStorage` (failures to access storage don't break the page) and is cleared after save.
- **AC-009.15** `j1-first-time-setup.spec.ts` passes (J1 steps 2–4) and axe reports no serious/critical violations on `/upload` and `/review`; the `/upload` and `/review` placeholders from RFC-001 are gone.
- **AC-009.16** Every file listed in §5 "File structure" exists, including `ValidationError.kt`, both DTO files, `ValidationVectorTest.kt`, `shared/validation-vectors/schema.json`, `api/uploads.ts`, `api/curricula.ts`, both schema files, `ReviewDraft.ts`, `ReviewDraftProvider.tsx`, `TotalsEditor.tsx`, `PrerequisiteChips.tsx`, `GraphPreview.tsx` and `validateDraft.vectors.test.ts`.
