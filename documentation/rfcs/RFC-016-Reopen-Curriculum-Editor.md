# RFC-016 — Reopen the Curriculum Editor After Saving

| | |
|---|---|
| **Status** | Draft |
| **Complexity** | Medium |
| **Phase** | 6 — Share, privacy, a11y (Should-have; R-5.1: start only after RFC-014 and RFC-015 are done) |
| **Features** | F20 |
| **PRD** | FR-3.4, FR-3.3, FR-4 downstream-consistency principle |
| **Predecessors:** | RFC-009, RFC-010 |
| **Successors:** | RFC-018 |

Sections omitted: none.

## 1. Summary

Lets a student reopen their saved curriculum in the RFC-009 editor, change courses, units, terms and prerequisites, and save again through the same validation — while keeping course ids, states and attempt history, and warning (never silently changing anything) when an edit deletes history, invalidates an already-started course, or pushes the load over the cap.

## 2. Interfaces

| Method | Path | Auth | Request | Success | Errors |
|---|---|---|---|---|---|
| GET | `/api/curricula/current/structure` | session | – | 200 `CurriculumStructureDto` | 404 |
| PUT | `/api/curricula/{curriculumId}/structure` | session + CSRF | `UpdateStructureRequest` | 200 `MapDto` | 400 `curriculum/invalid`; 409 `curriculum/structure-impact`; 404 |

```ts
CurriculumStructureDto = {
  id, programLabel, totalUnits, totalExtraUnits,
  courses: { id, code, title, units, extraUnits, year, term, slotType, slotLabel, slotFilled,
             exemptionNote, state, attemptCount }[],
  prerequisites: { courseCode, requiresCode, type }[]
}
UpdateStructureRequest = {
  programLabel, totalUnits, totalExtraUnits,
  courses: { id: string | null /* null = new */, code, title, units, extraUnits, year, term, slotType, exemptionNote }[],
  prerequisites: { courseCode, requiresCode, type }[],
  acknowledged: ('STRUCTURE_IMPACT')[]
}
```
`curriculum/structure-impact` extension `impacts: [{ kind, courseCodes, message }]`, kinds:
- `DELETES_HISTORY` — deleting courses whose state ≠ NOT_TAKEN or that have attempts: "Deleting NSSECU1 also deletes its 2 logged attempts."
- `INVALIDATES_STARTED` — new/changed HARD or COREQ links, or deleted prerequisites, leave a non-NOT_TAKEN course with requirements that were satisfied before and aren't now (RFC-006's `requirementsSatisfied`): "CCPROG3 is already in progress, but CCPROG2 is no longer finished-or-passed for it."
- `OVER_CAP` — unit changes to BEING_TAKEN courses make load exceed the cap: "Your current load would be 21 / 18 units."

## 3. Technical approach

```
CurriculumStructureService.update(userId, curriculumId, req)   @Transactional, curriculum row locked FOR UPDATE
  1. validate with CurriculumValidator (RFC-009)           → 400
  2. every non-null course id must belong to this curriculum → else 400 common/validation
  3. impacts = StructureImpactAnalyzer.analyze(before, after, unitCap)
  4. impacts ≠ ∅ and STRUCTURE_IMPACT ∉ acknowledged      → 409 with impacts (no changes)
  5. apply: delete removed courses (cascade attempts/edges); update existing by id (code, title, units,
            extraUnits, year, term, exemptionNote; slotType change: NONE→MANDATORY_ELECTIVE sets slot_label = code,
            slot_filled = false; MANDATORY_ELECTIVE→NONE clears both); insert new courses NOT_TAKEN;
            replace all prerequisite rows; update label/totals; updated_at = now()
  6. StateInvariants.verify (RFC-010) → return MapQueryService.load
```
States and attempts of kept courses are never modified (F20 edge case, F27 principle). `StructureImpactAnalyzer` reuses `rules/RulesEngine.requirementsSatisfied` and `capStatus` — the same logic RFC-006 already vectors, so no new rule semantics are introduced. `requirementsSatisfied` is made public in both `RulesEngine.kt` and `rules.ts` (currently internal) with a vector `downstream.requirements-satisfied-helper` added.

Frontend: route `/edit` (lazy, `RequireAuth`). `EditCurriculumPage` loads the structure, builds a `ReviewDraft` via `ReviewDraft.fromSaved` (rows keep `id`), and renders RFC-009's `TotalsEditor`, `CourseTable`, `GraphPreview` and `WarningsPanel` (validation only — no parse warnings or confidence banner, since there's no parse). Course rows with state ≠ NOT_TAKEN show their state icon and, on delete, an inline note "Has logged history". Save → PUT; 409 opens `StructureImpactDialog` listing impacts with "Save anyway" (resend with acknowledgement) / "Keep editing". Success invalidates `['map']` and returns to `/map`. An "Edit curriculum" link is added to `/settings` and the map header menu.

## 4. Implementation details

### File structure
```
backend/src/main/kotlin/ph/anevaino/curriculum/CurriculumStructureController.kt
backend/src/main/kotlin/ph/anevaino/curriculum/CurriculumStructureService.kt
backend/src/main/kotlin/ph/anevaino/curriculum/StructureImpactAnalyzer.kt
backend/src/main/kotlin/ph/anevaino/curriculum/dto/CurriculumStructureDto.kt
backend/src/main/kotlin/ph/anevaino/curriculum/dto/UpdateStructureRequest.kt
backend/src/main/kotlin/ph/anevaino/rules/RulesEngine.kt            (requirementsSatisfied public)
backend/src/test/kotlin/ph/anevaino/curriculum/StructureImpactAnalyzerTest.kt
backend/src/test/kotlin/ph/anevaino/curriculum/CurriculumStructureIntegrationTest.kt
shared/rule-vectors/downstream.requirements-satisfied-helper.json
frontend/src/domain/rules.ts                                         (requirementsSatisfied exported)
frontend/src/api/structure.ts
frontend/src/api/schemas/structure.ts
frontend/src/features/review/ReviewDraft.ts                          (fromSaved)
frontend/src/features/review/EditCurriculumPage.tsx
frontend/src/features/review/StructureImpactDialog.tsx
frontend/src/features/review/EditCurriculumPage.test.tsx
frontend/src/app/routes.tsx                                          (/edit)
frontend/e2e/edit-curriculum.spec.ts
```

### Testing
- `StructureImpactAnalyzerTest`: delete NOT_TAKEN course without attempts → no impact; delete course with attempts → DELETES_HISTORY; add HARD link to an unfinished course from an in-progress course → INVALIDATES_STARTED; remove a link → none; increase units of a BEING_TAKEN course past cap → OVER_CAP; NOT_TAKEN dependents never reported.
- `CurriculumStructureIntegrationTest`: edits keep ids, states and attempts; new courses NOT_TAKEN; 409 then 200 with acknowledgement; validation errors 400 (duplicate codes, HARD cycle); foreign curriculum 404; a course id from another curriculum → 400; slot-type change sets/clears `slot_label`/`slot_filled`.
- `EditCurriculumPage.test.tsx`: loads rows with states; history note on delete; impact dialog flow.
- `edit-curriculum.spec.ts`: map → settings → edit → change a unit → save → back on map with updated units; axe clean on `/edit`.

## 5. Considerations

- **Rules:** R-2.7, R-2.19, R-4.1, R-5.1, R-6.1.
- **Why one acknowledgement kind:** the dialog shows every impact at once; per-kind acknowledgements add clicks without adding information.
- **Edge:** renaming a code of a course that is a prerequisite elsewhere — links in the request are by code after the rename, and the editor keeps them attached by row id, so nothing dangles.

## 6. Acceptance criteria

- **AC-016.1 [F20]** From `/settings` or the map header, "Edit curriculum" opens `/edit` with the saved curriculum in the RFC-009 editor (table, totals, graph preview), without parse warnings or the confidence banner.
- **AC-016.2 [F20]** Saving re-runs RFC-009 validation on both sides; invalid structures return 400 `curriculum/invalid` and are shown as in the review screen.
- **AC-016.3 [F20]** Kept courses retain their ids, states and attempt history; new courses start NOT_TAKEN; removed courses are deleted with their attempts and links.
- **AC-016.4 [F20]** Deleting a course with history, invalidating a non-NOT_TAKEN course's HARD/COREQ requirements, or pushing load over the cap returns 409 `curriculum/structure-impact` listing each impact, and nothing changes until the user confirms "Save anyway".
- **AC-016.5 [F20]** No course state is ever changed by an edit (asserted in `CurriculumStructureIntegrationTest`), and `StateInvariants` still holds after every update.
- **AC-016.6** `requirementsSatisfied` is exported from both rules engines and covered by the new shared vector.
- **AC-016.7 [F44]** The structure endpoints only read and write the session user's curriculum; foreign ids yield 404/400.
- **AC-016.8** `edit-curriculum.spec.ts` passes and axe reports no serious/critical violations on `/edit`.
- **AC-016.9** Every file listed in §4 "File structure" exists or is updated as noted, including `CurriculumStructureController.kt`, both DTOs, `api/structure.ts`, `api/schemas/structure.ts`, `StructureImpactDialog.tsx`, `EditCurriculumPage.test.tsx` and the `/edit` route.
