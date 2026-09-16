# RFC-006 — Rules Engine (Kotlin + TypeScript) and Shared Rule Vectors

| | |
|---|---|
| **Status** | Draft |
| **Complexity** | High |
| **Phase** | 4 — Graph & rules (numbered early because it depends only on RFC-001; can run in parallel with the parser branch) |
| **Features** | F21, F22, F23, F24, F26 (enforcement logic), F27 (detection logic), F29 (parity), F33 (slot-fill rule) |
| **PRD** | FR-4 (states + rule table), FR-4.2, FR-6.1, FR-7.2, FR-7.3, G2 |
| **Predecessors:** | RFC-001 |
| **Successors:** | RFC-007, RFC-010 |

Sections omitted: *API*, *schema*, *UI* (RFC-010/011), *auth* (pure functions).

## 1. Summary

The authoritative decision function for every course action, implemented twice — pure Kotlin in `backend/.../rules/` and pure TypeScript in `frontend/src/domain/rules.ts` — and held identical by one set of JSON vectors in `shared/rule-vectors/` that both test suites execute (R-2.7, R-2.8). Given a curriculum snapshot and an action, it returns *allowed / blocked / needs acknowledgement*, the violations and warnings with exact messages, the resulting state changes and the attempt operations the caller must persist. It also computes node eligibility and unit load.

## 2. Decisions this RFC encodes

| Ref | Decision | Source |
|---|---|---|
| Q1 | `Attempt.result` includes `FINISHED` | RULES §7 default |
| Q2 | Co-requisites may be started together in one action; COREQ edges are excluded from cycle checks (RFC-009) | RULES §7 default |
| Q4 / **D1** | Fail has a kind: `GRADED` (grade 0.0, counts in CGPA) or `PASS_FAIL` (grade null, excluded). Explicit **Undo result** transition returns to `BEING_TAKEN` | RULES §7 default, **confirmed by Matthew 16 Sep 2026** over PRD FR-4/FR-5.1/5.2 wording — PRD to be corrected (RFCS.md C2) |
| **D2** | Resolving `INCOMPLETE` updates that same attempt; no new attempt | Matthew, 16 Sep 2026 |
| **D3** | Undo result **deletes** the latest attempt | Matthew, 16 Sep 2026 |
| Q7 | **Cancel start**: `BEING_TAKEN` → the state implied by the latest attempt (none → `NOT_TAKEN`) — PRD/FEATURES have no way to undo an accidental start | New gap, provisional (RFCS.md) |
| Q10 | The downstream warning (F27) fires for **any** action that makes an already-started dependent's requirement unmet, not only reverts from FINISHED/PASSED | New gap, provisional (RFCS.md) |

Every code site implementing Q2/Q4/Q7/Q10 carries `// OPEN-QUESTION(Q<n>): …` (R-6.3). D1–D3 are decided and need no marker.

## 3. Model

```
CurriculumSnapshot { unitCap: Int?, courses: [CourseSnap], prerequisites: [Edge] }
CourseSnap  { id, code, units, extraUnits, state: CourseState, slotType: NONE|MANDATORY_ELECTIVE,
              slotFilled: Boolean, attempts: [AttemptSnap] }          // attempts oldest → newest
AttemptSnap { result: FINISHED|PASSED|FAILED|WITHDRAWN|INCOMPLETE, grade: Grade? }
Edge        { courseId, requiresCourseId, type: HARD|SOFT|COREQ }     // courseId requires requiresCourseId
Grade       = "4.0"|"3.5"|"3.0"|"2.5"|"2.0"|"1.5"|"1.0"|"0.0"         // strings in JSON; BigDecimal / hundredths in code (R-2.11)

Action =
  Start           { courseIds: [id] (≥1, distinct), electiveFills: {id: {code, title, units}}, acknowledged: [WarningKind] }
  CancelStart     { courseIds: [id], acknowledged }                    // Q7
  Finish          { courseId, grade, acknowledged }                    // grade 0.0 ⇒ FAILED (FR-5.2)
  Pass            { courseId, acknowledged }
  Fail            { courseId, failKind: GRADED|PASS_FAIL, acknowledged }
  Withdraw        { courseId, acknowledged }
  MarkIncomplete  { courseId, acknowledged }
  ResolveIncomplete { courseId, result: FINISHED|PASSED|FAILED, grade?, failKind?, acknowledged }
  UndoResult      { courseId, acknowledged }

Decision {
  outcome: ALLOWED | BLOCKED | NEEDS_ACKNOWLEDGEMENT,
  violations: [Violation { type, courseId?, message, details }],
  warnings:   [Warning   { kind: SOFT_PREREQ_UNMET | DOWNSTREAM_AFFECTED, courseId, relatedCourseIds, message }],
  stateChanges: { id: CourseState },            // empty unless ALLOWED
  attemptOps: [ Create{courseId, result, grade?} | UpdateLatest{courseId, result, grade?} | DeleteLatest{courseId} ],
  electiveFills: { id: {code, title, units} }
}
```
Professor names are not rule inputs; RFC-010 attaches them to attempt ops.

## 4. Algorithms

### 4.1 Transition table (F21 + Q4/Q7, D1–D3)

| Action | Allowed from | To | Attempt op |
|---|---|---|---|
| Start | NOT_TAKEN, FAILED, WITHDRAWN (retake) | BEING_TAKEN | – |
| CancelStart | BEING_TAKEN | latest attempt's result if FAILED/WITHDRAWN, else NOT_TAKEN | – |
| Finish(grade ≠ 0.0) | BEING_TAKEN | FINISHED | Create(FINISHED, grade) |
| Finish(grade = 0.0) | BEING_TAKEN | FAILED | Create(FAILED, 0.0) |
| Pass | BEING_TAKEN | PASSED | Create(PASSED, null) |
| Fail(GRADED) | BEING_TAKEN | FAILED | Create(FAILED, 0.0) |
| Fail(PASS_FAIL) | BEING_TAKEN | FAILED | Create(FAILED, null) |
| Withdraw | BEING_TAKEN | WITHDRAWN | Create(WITHDRAWN, null) |
| MarkIncomplete | BEING_TAKEN | INCOMPLETE | Create(INCOMPLETE, null) |
| ResolveIncomplete | INCOMPLETE | FINISHED / FAILED (0.0 or kind) / PASSED | UpdateLatest(result, grade) |
| UndoResult | FINISHED, PASSED, FAILED, WITHDRAWN, INCOMPLETE | BEING_TAKEN | DeleteLatest |

Anything else → `rule/invalid-transition`. `Finish` requires a grade; `ResolveIncomplete(FINISHED)` requires a grade; a grade on Pass/Withdraw is rejected as `rule/grade-invalid`.

### 4.2 Evaluate
```
evaluate(snap, action):
  S = action's course ids; after = apply transitions to a copy of snap (incl. attempt ops, elective fills)
  violations = []
  transition check for each c in S                        → rule/invalid-transition
  if any c becomes BEING_TAKEN (Start, UndoResult):
     for c in entering:
       if c.slotType == MANDATORY_ELECTIVE and !c.slotFilled and c ∉ fills → rule/elective-unfilled
       validate fill: code 1–32 chars non-blank, not equal (case-insensitive) to any other course's code
                      or another fill in this action; title 1–200; units integer 0–30 → rule/elective-fill-invalid
       hardUnmet  = { r | Edge(c, r, HARD),  after.state(r) ∉ {FINISHED, PASSED} }
       coreqUnmet = { r | Edge(c, r, COREQ), after.state(r) ∉ {FINISHED, PASSED, BEING_TAKEN} }
       (using `after` means co-requisites started in the same action count — Q2)
       hardUnmet ≠ ∅  → rule/hard-prereq-unmet  details.unmet = hardUnmet
       coreqUnmet ≠ ∅ → rule/coreq-unmet        details.unmet = coreqUnmet
       softUnmet = { r | Edge(c, r, SOFT), after.attempts(r) has no FINISHED/PASSED/FAILED/WITHDRAWN }
       softUnmet ≠ ∅  → warning SOFT_PREREQ_UNMET
     if snap.unitCap != null:
       resulting = Σ over after BEING_TAKEN courses (units + extraUnits)   // fills use filled units
       if resulting > unitCap → rule/unit-cap-exceeded details {resultingLoad, unitCap}
  downstream (Q10, F27):
     for d with after.state(d) ≠ NOT_TAKEN, d ∉ S, and Edge(d, c, HARD or COREQ) for some c in S:
        if requirementsSatisfied(snap, d) and !requirementsSatisfied(after, d) → warning DOWNSTREAM_AFFECTED(c, [d…])
     requirementsSatisfied(x, d) = all HARD r of d in {FINISHED,PASSED}
                                   and (x.state(d) ≠ BEING_TAKEN or all COREQ r of d in {FINISHED,PASSED,BEING_TAKEN})
  if violations ≠ ∅                             → BLOCKED (warnings still listed, no changes)
  elif warnings' kinds ⊄ action.acknowledged    → NEEDS_ACKNOWLEDGEMENT (no changes)
  else                                          → ALLOWED with stateChanges, attemptOps, electiveFills
```
Dependents are never changed automatically (F27). Only increases are capped: an action that doesn't add BEING_TAKEN load is never blocked by the cap, so lowering the cap below the current load (FR-7.3) blocks only new starts/undos.

### 4.3 Eligibility (for F28) and load (for F26)
```
eligibility(snap, c):
  if state(c) ∉ {NOT_TAKEN, FAILED, WITHDRAWN} → NONE
  if any HARD r of c not FINISHED/PASSED       → LOCKED   reasons: hard unmet codes
  if any COREQ r not FINISHED/PASSED/BEING_TAKEN or any SOFT r unattempted → WARNING reasons
  else ELIGIBLE
capStatus(snap) = { load: Σ BEING_TAKEN (units+extraUnits), unitCap, overCap: unitCap != null && load > unitCap }
```
The cap is not part of eligibility (it depends on what else the student picks); the load counter shows it.

### 4.4 Messages (identical strings on both sides; codes, not ids, joined with ", " in snapshot order)

| Type / kind | Message |
|---|---|
| `rule/hard-prereq-unmet` | `Requires CCPROG2` (J2.3 wording) |
| `rule/coreq-unmet` | `Take together with CCPROG2, or finish it first` |
| `rule/unit-cap-exceeded` | `21 / 18 units` (resulting / cap, FR-4 "X / Y units") |
| `rule/elective-unfilled` | `Enter the actual course for this elective slot first` |
| `rule/elective-fill-invalid` | `A course with code GEELEC1 already exists` / `Units must be a whole number from 0 to 30` / `Course code and title are required` |
| `rule/invalid-transition` | `This action isn't available for a course that is FAILED` (state rendered human-readable: "failed") |
| `rule/grade-invalid` | `Choose a grade from 4.0 to 0.0` |
| `SOFT_PREREQ_UNMET` | `Recommended first: CCPROG1. You can still continue.` |
| `DOWNSTREAM_AFFECTED` | `These courses depend on CCPROG1: CCPROG2, CCDSALG. They won't be changed.` |

## 5. Rule vectors

`shared/rule-vectors/<rule>.<case>.json`, validated by `shared/rule-vectors/schema.json`:
```json
{
  "rule": "hard-prereq",
  "case": "unmet-blocks",
  "description": "Start is blocked when a hard prerequisite is FAILED",
  "input": {
    "snapshot": { "unitCap": 18, "courses": [ … CourseSnap … ], "prerequisites": [ … ] },
    "action": { "type": "START", "courseIds": ["B"], "electiveFills": {}, "acknowledged": [] }
  },
  "expected": {
    "decision": { "outcome": "BLOCKED", "violations": [ { "type": "rule/hard-prereq-unmet", "courseId": "B",
                  "message": "Requires CCPROG1", "details": { "unmet": ["A"] } } ],
                  "warnings": [], "stateChanges": {}, "attemptOps": [], "electiveFills": {} }
  }
}
```
Other `expected` shapes: `{ "eligibility": { "B": "LOCKED" } }`, `{ "capStatus": {…} }`. Runners compare the whole expected object (deep equality; arrays in the documented order: violations by course then type slug order of §4.4, warnings likewise).

**Required vector set** (each a named file):
`transition.*` — every allowed row of §4.1 (11) + `finish-from-not-taken-invalid`, `start-from-finished-invalid`, `resolve-from-being-taken-invalid`, `pass-with-grade-invalid`;
`hard-prereq.` `met-finished`, `met-passed`, `unmet-blocks`, `failed-blocks`, `withdrawn-blocks`, `incomplete-blocks`, `multiple-unmet-lists-all`;
`coreq.` `met-being-taken`, `unmet-blocks`, `mutual-start-together-allowed`, `mutual-start-alone-blocked`;
`soft-prereq.` `never-attempted-needs-ack`, `acknowledged-allowed`, `failed-attempt-satisfies`, `withdrawn-attempt-satisfies`, `incomplete-only-warns`;
`unit-cap.` `null-cap-no-limit`, `exact-cap-allowed`, `over-cap-blocks-message`, `extra-units-count`, `group-start-sums`, `lowered-cap-blocks-new-start`, `lowered-cap-allows-finish`, `undo-counts-toward-cap`;
`elective.` `unfilled-blocks`, `fill-in-start-allowed`, `fill-duplicate-code-blocked`, `fill-units-used-for-cap`;
`downstream.` `undo-finished-with-started-dependent-needs-ack`, `undo-acknowledged-leaves-dependents`, `withdraw-coreq-partner-warns`, `cancel-start-coreq-partner-warns`, `not-taken-dependent-no-warning`;
`undo.` `deletes-latest-attempt`, `retake-undo-returns-to-being-taken-previous-attempt-kept`;
`cancel-start.` `fresh-to-not-taken`, `retake-to-failed`, `retake-to-withdrawn`;
`incomplete.` `resolve-updates-same-attempt`, `resolve-finish-zero-becomes-failed`;
`fail.` `graded-zero`, `pass-fail-null-grade`;
`eligibility.` `locked`, `warning-soft`, `warning-coreq`, `eligible`, `none-when-being-taken`;
`cap-status.` `over-cap-flag`.

## 6. Implementation details

### File structure
```
backend/src/main/kotlin/ph/anevaino/rules/CourseState.kt
backend/src/main/kotlin/ph/anevaino/rules/Grade.kt
backend/src/main/kotlin/ph/anevaino/rules/Snapshot.kt
backend/src/main/kotlin/ph/anevaino/rules/Action.kt
backend/src/main/kotlin/ph/anevaino/rules/Decision.kt
backend/src/main/kotlin/ph/anevaino/rules/Transitions.kt
backend/src/main/kotlin/ph/anevaino/rules/RulesEngine.kt
backend/src/main/kotlin/ph/anevaino/rules/Eligibility.kt
backend/src/main/kotlin/ph/anevaino/rules/Messages.kt
backend/src/test/kotlin/ph/anevaino/rules/RuleVectorLoader.kt
backend/src/test/kotlin/ph/anevaino/rules/RuleVectorTest.kt
backend/src/test/kotlin/ph/anevaino/rules/RulesEngineTest.kt
frontend/src/domain/types.ts
frontend/src/domain/grade.ts
frontend/src/domain/transitions.ts
frontend/src/domain/rules.ts
frontend/src/domain/eligibility.ts
frontend/src/domain/messages.ts
frontend/src/domain/ruleVectors.test.ts
frontend/src/domain/rules.test.ts
shared/rule-vectors/schema.json
shared/rule-vectors/*.json          (the §5 set)
```

### Testing
- `RuleVectorTest` (JUnit `@TestFactory`) and `ruleVectors.test.ts` each enumerate every `*.json` in `shared/rule-vectors/`, validate it against `schema.json`, dispatch on `rule` (`transition, hard-prereq, coreq, soft-prereq, unit-cap, elective, downstream, undo, cancel-start, incomplete, fail, eligibility, cap-status`) and **fail on an unknown `rule`** — so both sides always run the identical set (F29 parity; RFC-007 adds `cgpa`, `progress`).
- Hand-written unit tests cover what vectors can't express well: determinism of ordering, input validation (empty `courseIds`, duplicate ids → `rule/invalid-transition`), and the invariant "no ALLOWED decision leaves any course BEING_TAKEN with unmet HARD requirements that it didn't already have" (property-style test over randomly generated small snapshots, Kotlin side with a fixed seed).
- Coverage ≥ 90% line and branch on `rules/` and `domain/` (RULES §4.1).

## 7. Considerations

- **Rules:** R-2.1, R-2.2, R-2.3, R-2.6, R-2.7, R-2.8, R-2.11, R-4.1, R-4.4, R-6.2, R-6.3.
- **Why two implementations:** Kotlin and TS can't share code (F29 technical note); vectors are the contract.
- **Performance:** O(courses + edges) per action; 80 courses is trivially < 1 ms, well within F29's 300 ms local budget.
- **Invalid G2 states that remain possible, by decision:** a *recorded outcome* (withdraw, fail, cancel, undo) can leave a dependent's requirement unmet; the engine warns (Q10) rather than refusing to record reality. G2's "0 invalid being-taken states" is guaranteed for *entering* BEING_TAKEN. Flagged in RFCS.md for Matthew.

## 8. Acceptance criteria

- **AC-006.1 [F21]** Both engines implement exactly the §4.1 transition table; every other (state, action) pair returns `BLOCKED` with `rule/invalid-transition`.
- **AC-006.2 [F21, D1]** `Finish` with 0.0 yields `FAILED` + `Create(FAILED, 0.0)`; `Fail(GRADED)` yields grade 0.0; `Fail(PASS_FAIL)` yields grade null.
- **AC-006.3 [D2]** `ResolveIncomplete` produces `UpdateLatest`, never `Create`.
- **AC-006.4 [D3, Q4]** `UndoResult` from FINISHED/PASSED/FAILED/WITHDRAWN/INCOMPLETE returns to `BEING_TAKEN` with `DeleteLatest`, runs the same hard/co-req/cap/soft checks as Start, and carries `OPEN-QUESTION(Q4)` markers.
- **AC-006.5 [Q7]** `CancelStart` returns a course to NOT_TAKEN, FAILED or WITHDRAWN per its latest attempt, with no attempt op, and carries `OPEN-QUESTION(Q7)` markers.
- **AC-006.6 [F22]** Entering BEING_TAKEN is blocked unless every HARD prerequisite is FINISHED or PASSED; the violation lists every unmet prerequisite and the message is `Requires <codes>`.
- **AC-006.7 [F23, Q2]** Entering BEING_TAKEN is blocked unless each COREQ is FINISHED/PASSED/BEING_TAKEN after the action; mutual co-requisites started in one `Start` are allowed, started alone are blocked.
- **AC-006.8 [F24]** An unattempted SOFT prerequisite (no FINISHED/PASSED/FAILED/WITHDRAWN attempt) yields `NEEDS_ACKNOWLEDGEMENT` until `SOFT_PREREQ_UNMET` is acknowledged, then `ALLOWED`; any such attempt removes the warning.
- **AC-006.9 [F26]** With a non-null cap, an action whose resulting BEING_TAKEN load (units + extraUnits, fills included) exceeds the cap is blocked with message `X / Y units`; a null cap never blocks; actions that don't add load are never cap-blocked.
- **AC-006.10 [F27, Q10]** Any action that makes a non-NOT_TAKEN dependent's HARD (or, for a BEING_TAKEN dependent, COREQ) requirement go from satisfied to unsatisfied yields a `DOWNSTREAM_AFFECTED` warning listing those dependents, requires acknowledgement, and never changes the dependents.
- **AC-006.11 [F33]** Starting an unfilled `MANDATORY_ELECTIVE` without a fill is blocked (`rule/elective-unfilled`); a fill with a duplicate code, blank code/title, or units outside 0–30 is blocked (`rule/elective-fill-invalid`); valid fills are returned in `electiveFills` and their units count toward the cap.
- **AC-006.12 [F28 input]** `eligibility` returns LOCKED / WARNING / ELIGIBLE / NONE per §4.3; `capStatus` returns load, cap and `overCap`.
- **AC-006.13** Violation and warning messages match §4.4 character-for-character on both sides.
- **AC-006.14 [F29]** Every vector in the §5 required set exists, validates against `schema.json`, and passes in both `RuleVectorTest` and `ruleVectors.test.ts`; an unknown `rule` value fails both runners.
- **AC-006.15** `rules/` and `domain/` import no Spring, JPA, React or I/O; grades use `BigDecimal` (Kotlin) and integer hundredths (TS); coverage ≥ 90% line and branch on both.
- **AC-006.16** Every file listed in §6 "File structure" exists, including `Snapshot.kt`, `Action.kt`, `Decision.kt`, `Transitions.kt`, `Eligibility.kt`, `Messages.kt`, `RuleVectorLoader.kt`, `RulesEngineTest.kt`, `types.ts`, `grade.ts`, `transitions.ts`, `eligibility.ts`, `messages.ts`, `rules.test.ts`.
