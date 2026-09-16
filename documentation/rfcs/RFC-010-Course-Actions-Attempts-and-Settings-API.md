# RFC-010 — Course Actions, Attempt Log, Map Endpoint and Settings API

| | |
|---|---|
| **Status** | Draft |
| **Complexity** | High |
| **Phase** | 4 — Graph & rules |
| **Features** | F21, F23, F26 (setting + API), F27 (API acknowledgement), F30 (backend), F31 (backend), F32 (served), F33 (backend), F37 (theme persistence), F44 |
| **PRD** | FR-4, FR-4.2, FR-5.1–FR-5.4, FR-6.1, FR-7.1–FR-7.3, FR-8.4, §6.2, NFR Performance |
| **Predecessors:** | RFC-006, RFC-007, RFC-009 |
| **Successors:** | RFC-011, RFC-016 |

Sections omitted: *UI* (RFC-011).

## 1. Summary

Wires the pure rules engine and calculators to persistence. Adds the `attempt` table, one read endpoint that returns everything the map needs in a fixed number of queries, one action endpoint that evaluates every course action server-side (backend-authoritative, R-2.7) and persists state changes, attempts (with professor) and elective fills atomically, a standalone elective-fill endpoint, and the settings endpoint for unit cap and theme.

## 2. Data model — `V4__create_attempt.sql`

```sql
CREATE TABLE attempt (
  id           UUID PRIMARY KEY,
  course_id    UUID NOT NULL REFERENCES course(id) ON DELETE CASCADE,
  sequence_no  INTEGER NOT NULL,
  result       TEXT NOT NULL CHECK (result IN ('FINISHED','PASSED','FAILED','WITHDRAWN','INCOMPLETE')),  -- Q1
  grade        NUMERIC(2,1) NULL,
  professor    TEXT NULL CHECK (professor IS NULL OR length(professor) <= 100),
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (course_id, sequence_no),
  CHECK (
       (result = 'FINISHED' AND grade IN (4.0, 3.5, 3.0, 2.5, 2.0, 1.5, 1.0))
    OR (result = 'FAILED'   AND (grade = 0.0 OR grade IS NULL))                                        -- D1
    OR (result IN ('PASSED','WITHDRAWN','INCOMPLETE') AND grade IS NULL))
);
ALTER TABLE course ADD COLUMN slot_filled BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE course ADD CONSTRAINT slot_filled_only_for_slots CHECK (slot_filled = false OR slot_type = 'MANDATORY_ELECTIVE');
```
`sequence_no` gives a total order per course (no reliance on timestamp ties). R-2.10's grade CHECK is refined: FINISHED can't carry 0.0 because 0.0 always means FAILED (FR-5.2).
**PRD §6.2 additions, flagged:** `Attempt.sequenceNo`, `Attempt.result` gains `FINISHED` (Q1), `Course.slotFilled`.

## 3. Interfaces

### 3.1 `GET /api/curricula/current/map` (session)
```ts
MapDto = {
  curriculum: CurriculumSummaryDto,
  unitCap: number | null,
  courses: {
    id, code, title, units, extraUnits, year, term, slotType, slotLabel, slotFilled, exemptionNote,
    state: CourseState,
    attempts: { id, sequenceNo, result, grade: Grade | null, professor: string | null, createdAt }[]   // oldest → newest
  }[],
  prerequisites: { courseId, requiresCourseId, type }[],
  capStatus: { load: number, unitCap: number | null, overCap: boolean },
  cgpa: { hundredths: number | null, display: string },      // "2.75" | "—"
  progress: { fillBasisPoints: number, earnedUnits: number, requiredUnits: number }
}
```
404 `common/not-found` when the user has no curriculum.

### 3.2 `POST /api/curricula/{curriculumId}/course-actions` (session + CSRF)
```ts
CourseActionRequest = {
  type: 'START'|'CANCEL_START'|'FINISH'|'PASS'|'FAIL'|'WITHDRAW'|'MARK_INCOMPLETE'|'RESOLVE_INCOMPLETE'|'UNDO_RESULT',
  courseIds: string[],                        // START / CANCEL_START: 1–20 distinct; others: exactly 1
  grade?: Grade,                              // FINISH; RESOLVE_INCOMPLETE with resolveResult FINISHED
  failKind?: 'GRADED' | 'PASS_FAIL',          // FAIL; RESOLVE_INCOMPLETE with resolveResult FAILED
  resolveResult?: 'FINISHED' | 'PASSED' | 'FAILED',
  professor?: string | null,                  // ≤ 100; accepted on FINISH, PASS, FAIL, WITHDRAW, MARK_INCOMPLETE, RESOLVE_INCOMPLETE
  electiveFills?: Record<courseId, { code, title, units }>,
  acknowledged: ('SOFT_PREREQ_UNMET' | 'DOWNSTREAM_AFFECTED')[]
}
```
| Engine outcome | HTTP | Body |
|---|---|---|
| ALLOWED | 200 | `MapDto` (fresh, post-commit) |
| BLOCKED | 422 | Problem, `type` = first violation's slug (e.g. `rule/hard-prereq-unmet`), `detail` = its message, extensions `violations[]` and `warnings[]`; ids in `details.unmet` are accompanied by `details.unmetCodes` |
| NEEDS_ACKNOWLEDGEMENT | 409 | Problem `rule/acknowledgement-required`, extension `warnings[]` (`kind`, `courseId`, `relatedCourseIds`, `relatedCodes`, `message`) |
Malformed combinations (grade on PASS, missing failKind, wrong courseIds count, unknown course id in this curriculum) → 400 `common/validation`. A curriculum id not owned by the user → 404 (R-2.19). Professor is trimmed; empty → null. On `RESOLVE_INCOMPLETE` an omitted professor keeps the existing one.

### 3.3 `PUT /api/curricula/{curriculumId}/courses/{courseId}/elective-fill` (session + CSRF)
Body `{ code, title, units }`. Allowed only for `MANDATORY_ELECTIVE` courses in `NOT_TAKEN`; same validation as the engine's fill check (RFC-006 §4.2). 200 `MapDto`; 422 `rule/elective-fill-invalid` or `rule/invalid-transition`.

### 3.4 `PATCH /api/me/settings` (session + CSRF)
Body (merge semantics — an absent key is unchanged): `{ unitCap?: number | null, theme?: 'SYSTEM'|'LIGHT'|'DARK' }`. `unitCap` integer 1–60 or `null` (no cap, FR-7.1). 200 `{ me: MeDto, capStatus: CapStatus | null }` (`capStatus` null when the user has no curriculum). Lowering below the current load succeeds (FR-7.3) and returns `overCap: true`.

New slugs: `rule/invalid-transition`, `rule/hard-prereq-unmet`, `rule/coreq-unmet`, `rule/unit-cap-exceeded`, `rule/elective-unfilled`, `rule/elective-fill-invalid`, `rule/grade-invalid`, `rule/acknowledgement-required`.

## 4. Technical approach

```
CourseActionController → CourseActionService.execute(userId, curriculumId, req)   @Transactional
  1. curriculum = curriculumRepository.findByIdAndUserIdForUpdate(curriculumId, userId)   // SELECT … FOR UPDATE
       → 404 if absent (serializes concurrent actions per curriculum; prevents two tabs beating the cap)
  2. snapshot = SnapshotMapper.from(curriculum, courses, prerequisites, attempts, user.unitCap)
  3. action   = ActionMapper.from(req)   (400 on malformed)
  4. decision = RulesEngine.evaluate(snapshot, action)
  5. BLOCKED → throw RuleViolation(decision) ; NEEDS_ACK → throw AcknowledgementRequired(decision)
  6. apply: for (id, state) in stateChanges → course.state
            fills → course.code/title/units, slotFilled = true   (unique violation → rule/elective-fill-invalid)
            Create(courseId, result, grade) → new Attempt(sequenceNo = max+1, professor = req.professor)
            UpdateLatest → latest attempt.result/grade (+ professor if provided)          // D2
            DeleteLatest → delete latest attempt                                          // D3
  7. flush; return MapQueryService.load(userId)
```
- `SnapshotMapper` maps `curriculum.CourseState` ↔ `rules.CourseState` with exhaustive `when` (RFC-005 §6).
- `MapQueryService.load` issues exactly 4 SQL statements: curriculum+user, courses, prerequisites (by curriculum), attempts (by curriculum via join) — no lazy loading (R-2.31); CGPA/progress/capStatus are computed with RFC-007/006 functions.
- **Invariant check (defence in depth):** after applying, `StateInvariants.verify(courses, attempts)` asserts each course's state matches its latest attempt (none → NOT_TAKEN or BEING_TAKEN; FINISHED ↔ FINISHED attempt; etc.). A mismatch throws and rolls back (500) — it indicates an engine bug.
- Logging: `INFO course.action userId type outcome courses=n`; `WARN rule.blocked userId type=<slug>`. Grades and professor names are never logged (R-2.26).

## 5. Implementation details

### File structure
```
backend/src/main/resources/db/migration/V4__create_attempt.sql
backend/src/main/kotlin/ph/anevaino/grades/Attempt.kt
backend/src/main/kotlin/ph/anevaino/grades/AttemptRepository.kt
backend/src/main/kotlin/ph/anevaino/course/CourseActionController.kt
backend/src/main/kotlin/ph/anevaino/course/CourseActionService.kt
backend/src/main/kotlin/ph/anevaino/course/CourseActionRequest.kt
backend/src/main/kotlin/ph/anevaino/course/ActionMapper.kt
backend/src/main/kotlin/ph/anevaino/course/SnapshotMapper.kt
backend/src/main/kotlin/ph/anevaino/course/StateInvariants.kt
backend/src/main/kotlin/ph/anevaino/course/ElectiveFillController.kt
backend/src/main/kotlin/ph/anevaino/course/MapQueryService.kt
backend/src/main/kotlin/ph/anevaino/course/MapDto.kt
backend/src/main/kotlin/ph/anevaino/common/error/RuleViolationProblems.kt
backend/src/main/kotlin/ph/anevaino/user/SettingsController.kt
backend/src/main/kotlin/ph/anevaino/user/SettingsRequest.kt
backend/src/test/kotlin/ph/anevaino/course/CourseActionIntegrationTest.kt
backend/src/test/kotlin/ph/anevaino/course/MapQueryCountTest.kt
backend/src/test/kotlin/ph/anevaino/course/ConcurrencyTest.kt
backend/src/test/kotlin/ph/anevaino/course/ActionMapperTest.kt
backend/src/test/kotlin/ph/anevaino/course/StateInvariantsTest.kt
backend/src/test/kotlin/ph/anevaino/grades/AttemptSchemaTest.kt
backend/src/test/kotlin/ph/anevaino/user/SettingsControllerIntegrationTest.kt
frontend/src/api/schemas/map.ts
frontend/src/api/courseActions.ts
frontend/src/api/settings.ts
```
The `course/` package is added to RULES §2.2's layout (it orchestrates `rules/`, `grades/` and `curriculum/`); flag for RULES update.

### Testing (Testcontainers)
- `CourseActionIntegrationTest` — J3 end-to-end at API level: start CCPROG1 and NSSECU1 → finish CCPROG1 3.0 with professor → finish NSSECU1 0.0 ⇒ state FAILED, attempt grade 0.0, `cgpa.display "1.50"` → START (retake) → FINISH 2.5 ⇒ `"2.75"` and two NSSECU1 attempts; PASS NSTP (no grade, progress rises, CGPA unchanged); FAIL PASS_FAIL ⇒ grade null; WITHDRAW; MARK_INCOMPLETE then RESOLVE_INCOMPLETE FINISHED 3.5 ⇒ still one attempt (D2); UNDO_RESULT ⇒ attempt deleted (D3); CANCEL_START on a retake ⇒ back to FAILED (Q7); hard prereq unmet ⇒ 422 `rule/hard-prereq-unmet` with `unmetCodes ["CCPROG2"]`; soft ⇒ 409 then 200 with acknowledgement; cap 18 with 21 ⇒ 422 detail `21 / 18 units`; mutual co-reqs in one START ⇒ 200; elective START without fill ⇒ 422, with fill ⇒ 200 and `slotFilled` true; malformed bodies ⇒ 400; grade on PASS ⇒ 400; professor never appears in captured logs.
- `AttemptSchemaTest`: DB rejects FINISHED with 0.0, PASSED with a grade, FAILED with 2.0, duplicate sequence numbers; course delete cascades attempts.
- `MapQueryCountTest`: Hibernate statistics show ≤ 4 prepared statements for `GET map` on the 80-course fixture with attempts.
- `ConcurrencyTest`: two parallel STARTs that each fit the cap but not together ⇒ exactly one 200, one 422.
- `SettingsControllerIntegrationTest`: set cap 18, clear with null, reject 0 and 61, lower below load ⇒ 200 `overCap true`; theme values persist; absent keys unchanged.
- Cross-user (F44): user B posting actions to user A's curriculum id ⇒ 404; user B using a course id belonging to A inside B's own curriculum path ⇒ 400 (unknown course in this curriculum), never touching A's data.
- Performance: warm `course-actions` p95 < 1 s on the 80-course fixture in the integration environment (F29 save budget; informational assertion at 1 s).

## 6. Considerations

- **Rules:** R-2.4, R-2.7, R-2.9, R-2.10, R-2.11, R-2.13, R-2.19, R-2.26, R-2.29, R-2.31, R-4.3, R-4.6, R-4.8, R-6.3.
- **Why one action endpoint:** group starts (Q2) and acknowledgement round-trips share one shape; per-action endpoints would duplicate the evaluate/apply pipeline.
- **Memory (R-1.4):** snapshots are per request; nothing cached across users.
- **Edge:** settings `unitCap` change does not re-evaluate existing BEING_TAKEN courses (FR-7.3: allowed, warn only).

## 7. Acceptance criteria

- **AC-010.1 [F31, Q1]** `V4__create_attempt.sql` creates `attempt` and `course.slot_filled` exactly as §2; `AttemptSchemaTest` proves the result/grade CHECK, sequence uniqueness and cascade.
- **AC-010.2 [F21, F44]** `POST …/course-actions` evaluates every request with `RulesEngine` inside a transaction holding a row lock on the curriculum, and persists only `ALLOWED` decisions; BLOCKED → 422 with `violations`, NEEDS_ACKNOWLEDGEMENT → 409 `rule/acknowledgement-required` with `warnings`, malformed → 400, foreign curriculum → 404.
- **AC-010.3 [F30]** FINISH requires a grade (4.0–0.0; 0.0 stores FAILED/0.0), PASS and WITHDRAW reject a grade, FAIL requires `failKind` (GRADED → 0.0, PASS_FAIL → null), and each creates one attempt with the optional professor (trimmed, ≤ 100 chars).
- **AC-010.4 [F31, D2, D3]** Attempts are ordered by `sequence_no`; RESOLVE_INCOMPLETE updates the latest attempt; UNDO_RESULT deletes it; `StateInvariants` rolls back any state/attempt mismatch.
- **AC-010.5 [F22, F23, F24, F26]** Hard-prerequisite, co-requisite (including group start), soft-warning acknowledgement and unit-cap outcomes from the API match the engine, including the `unmetCodes` extension and the `X / Y units` detail.
- **AC-010.6 [F27]** Actions producing `DOWNSTREAM_AFFECTED` require acknowledgement and never modify the listed dependents.
- **AC-010.7 [F33]** Elective slots can be filled via `PUT …/elective-fill` (NOT_TAKEN only) or inside START's `electiveFills`; filling replaces code/title/units, keeps `slot_label`, sets `slot_filled`; duplicate codes → 422 `rule/elective-fill-invalid`.
- **AC-010.8 [F32, F36]** `GET /api/curricula/current/map` returns the §3.1 `MapDto` with CGPA and progress computed by RFC-007's calculators; the J3 test shows `"1.50"` then `"2.75"`.
- **AC-010.9 [F26]** `PATCH /api/me/settings` sets `unitCap` (1–60 or null) and returns `capStatus`; lowering below current load succeeds with `overCap: true` and later STARTs are blocked by the engine.
- **AC-010.10 [F37]** `PATCH /api/me/settings` persists `theme` (SYSTEM/LIGHT/DARK) and `GET /api/me` returns it.
- **AC-010.11** The map endpoint uses ≤ 4 SQL statements on the 80-course fixture (`MapQueryCountTest`); two concurrent over-cap STARTs yield exactly one success (`ConcurrencyTest`).
- **AC-010.12** Grades and professor names never appear in logs (asserted with a captured log appender in `CourseActionIntegrationTest`).
- **AC-010.13** All new `rule/*` slugs are in `shared/problem-types.json` and both registries; frontend `api/schemas/map.ts`, `api/courseActions.ts` and `api/settings.ts` validate these responses with Zod.
- **AC-010.14** Every file listed in §5 "File structure" exists, including `AttemptRepository.kt`, `CourseActionRequest.kt`, `ActionMapper.kt`, `SnapshotMapper.kt`, `ElectiveFillController.kt`, `MapDto.kt`, `RuleViolationProblems.kt`, `SettingsRequest.kt`, `ActionMapperTest.kt` and `StateInvariantsTest.kt`.
