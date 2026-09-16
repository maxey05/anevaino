# RFC-011 — Quest Map Page and Course Interaction UI

| | |
|---|---|
| **Status** | Draft |
| **Complexity** | High |
| **Phase** | 4 — Graph & rules |
| **Features** | F22, F23, F24, F25, F26 (counter + settings UI), F27 (dialog), F28, F29 (optimistic UX + latency), F30 (UI), F31 (UI), F32 (header display), F33 (UI) |
| **PRD** | FR-4, FR-4.1, FR-5.1–5.4, FR-6.1, FR-7, J2, J3, J4 |
| **Predecessors:** | RFC-008, RFC-010 |
| **Successors:** | RFC-012, RFC-014, RFC-015 |

Sections omitted: *backend*, *schema* (none new).

## 1. Summary

The `/map` screen: the course graph with state- and eligibility-aware nodes, a course detail panel with exemption badge and attempt history, every course action with its dialog (grade, professor, fail kind, elective fill, soft/downstream acknowledgements, start-together for co-requisites), a header with CGPA and the unit-load counter, the unit-cap setting, and optimistic updates driven by the TypeScript rules mirror with server reconciliation.

## 2. Technical approach

### 2.1 Data flow
```
useMap()                 TanStack Query ['map'] → GET /api/curricula/current/map  (Zod: MapDto)
toSnapshot(map)          → domain CurriculumSnapshot (RFC-006 types)
eligibility / capStatus  → domain functions, memoized on map
useCourseActions().run(action):
   local = domain.evaluate(snapshot, action)
   BLOCKED               → show BlockedAlert(local.violations) — no request
   NEEDS_ACKNOWLEDGEMENT → open AcknowledgeDialog(local.warnings) → on confirm run(action + acknowledged)
   ALLOWED               → optimistic: queryClient.setQueryData(['map'], applyDecision(map, local, action))
                           POST course-actions
                             200 → setQueryData(['map'], response)        // server truth
                             409 → rollback; open AcknowledgeDialog(server warnings)
                             422 → rollback; BlockedAlert(server violations)
                             other → rollback; toast "Couldn't save. Your map was restored." + retry
```
`applyDecision` (in `features/map/applyDecision.ts`) applies state changes, fills and attempt ops (temporary ids for created attempts) and recomputes `cgpa`, `progress`, `capStatus` with `domain/cgpa.ts`, `progress.ts`, `rules.ts`, so the header updates instantly (F32 "updates right away"). Mutations are serialized through one TanStack mutation scope (`scope: { id: 'course-actions' }`) so optimistic states don't race.

### 2.2 Node visuals (F28, R-4.9)
`CourseNode` shows: code (bold), truncated title, units (`3u`, or `0 (3)u` for extra units), a **state icon + text label** and, for startable states, an **eligibility badge**. Colours come from theme tokens; every state also has a distinct icon and border pattern so it reads in grayscale.

| State | Icon (inline SVG) | Border | Label |
|---|---|---|---|
| NOT_TAKEN | hollow circle | thin solid | Not taken |
| BEING_TAKEN | hourglass | thick solid | In progress |
| FINISHED | check in star | double | Finished |
| PASSED | check in circle + "P" | double | Passed |
| FAILED | cross | thick dashed | Failed |
| WITHDRAWN | "W" in square | dotted | Withdrawn |
| INCOMPLETE | ellipsis in circle | dash-dot (`8 3 2 3`) | Incomplete |

| Eligibility | Badge |
|---|---|
| ELIGIBLE | small "go" arrow, label "Can start" |
| LOCKED | padlock, label "Locked", node content at reduced emphasis (not below 4.5:1 contrast) |
| WARNING | triangle "!", label "Check first" |

Elective slots show their `slotLabel` as a chip ("GE Elective") and, when unfilled, "Choose course" instead of a code. Node `aria-label`: "CCPROG2, Object-Oriented Programming, 3 units, Not taken, Locked: requires CCPROG1".

### 2.3 Course detail panel
Opens on node click (side panel ≥ md, bottom sheet < md, implemented as a `<dialog>` with focus trap/return, R-4.12):
- Code, title, units (incl. extra), year/term, slot label.
- **Exemption badge (F25)** when `exemptionNote`: "Exemption: BASMATH" with an info icon and tooltip "Placement or exemption code — informational only." Never blocks or warns.
- Prerequisites list with type and each prerequisite's state.
- Eligibility reasons (e.g. "Requires CCPROG1").
- **Attempt history (F31):** ordered list "Attempt 1 · Failed · 0.0 · Prof. Santos · 12 Jan 2027" (grade "—" when null, "Pass/fail" note for PASS_FAIL failures).
- Action buttons for the current state:

| State | Buttons (in order) |
|---|---|
| NOT_TAKEN | Start course |
| BEING_TAKEN | Finish · Mark passed · Fail · Withdraw · Mark incomplete · Cancel start |
| FINISHED, PASSED | Undo result |
| FAILED, WITHDRAWN | Retake · Undo result |
| INCOMPLETE | Resolve incomplete · Undo result |

### 2.4 Dialogs
- **FinishDialog:** grade `<select>` 4.0 … 0.0 (required); professor (optional). Helper text under 0.0: "A 0.0 marks this course as failed." (FR-5.2).
- **FailDialog (D1):** radio "Graded failure (0.0, counts in CGPA)" (default) / "Pass/fail course — failed (no grade, not in CGPA)"; professor.
- **PassDialog / WithdrawDialog / IncompleteDialog:** professor only; no grade field (J3.3, J3.4).
- **ResolveIncompleteDialog:** result radio Finished / Passed / Failed, then the grade or fail-kind field as above; professor pre-filled.
- **ElectiveFillDialog (F33):** code, title, units; used by "Start course" on an unfilled slot (fill + start in one request) and by "Choose course" on a NOT_TAKEN slot (PUT elective-fill). Shows the category label read-only.
- **StartTogetherDialog (Q2):** when a local START is blocked only by `rule/coreq-unmet` and every unmet co-requisite is itself startable, offer "Start CCPROG2 and CCPROG2L together" → START with both ids.
- **AcknowledgeDialog:** lists warning messages (§4.4 of RFC-006) with "Continue anyway" / "Cancel".
- **BlockedAlert:** `role="alert"` inside the panel listing violation messages ("Requires CCPROG2", "21 / 18 units").
- **UndoResult confirmation:** "Undo the logged result for NSSECU1? The attempt will be removed from its history." (D3).

### 2.5 Header and settings
- `AppHeader` (shared layout): "CGPA 2.75" / "CGPA —" with `aria-label="Cumulative GPA 2.75"`, and `LoadCounter`: "12 / 18 units" or "12 units · no cap" linking to settings; when `capStatus.overCap` it shows a warning icon and `CapWarningBanner` on the map: "Your current load (21 units) is above your cap (18). You can't start more courses until it fits." (FR-7.3). A slot `progressSlot` is reserved for RFC-012's bar.
- `/settings` page: `UnitCapSetting` — number input 1–60 + "No cap" checkbox, saves via `PATCH /api/me/settings`, invalidates `['map']` and `['me']`. Other settings sections are added by RFC-012/015/017.

## 3. Implementation details

### File structure
```
frontend/src/features/map/MapPage.tsx
frontend/src/features/map/useMap.ts
frontend/src/features/map/toSnapshot.ts
frontend/src/features/map/applyDecision.ts
frontend/src/features/map/useCourseActions.ts
frontend/src/features/map/CourseNode.tsx
frontend/src/features/map/stateVisuals.tsx
frontend/src/features/map/CapWarningBanner.tsx
frontend/src/features/course/CourseDetailPanel.tsx
frontend/src/features/course/ExemptionBadge.tsx
frontend/src/features/course/AttemptHistory.tsx
frontend/src/features/course/CourseActionButtons.tsx
frontend/src/features/course/dialogs/FinishDialog.tsx
frontend/src/features/course/dialogs/FailDialog.tsx
frontend/src/features/course/dialogs/ProfessorOnlyDialog.tsx
frontend/src/features/course/dialogs/ResolveIncompleteDialog.tsx
frontend/src/features/course/dialogs/ElectiveFillDialog.tsx
frontend/src/features/course/dialogs/StartTogetherDialog.tsx
frontend/src/features/course/dialogs/AcknowledgeDialog.tsx
frontend/src/features/course/dialogs/UndoResultDialog.tsx
frontend/src/features/course/BlockedAlert.tsx
frontend/src/components/AppHeader.tsx
frontend/src/components/LoadCounter.tsx
frontend/src/components/CgpaDisplay.tsx
frontend/src/features/settings/SettingsPage.tsx
frontend/src/features/settings/UnitCapSetting.tsx
frontend/src/features/map/applyDecision.test.ts
frontend/src/features/map/useCourseActions.test.tsx
frontend/src/features/map/CourseNode.test.tsx
frontend/src/features/course/CourseDetailPanel.test.tsx
frontend/src/features/settings/UnitCapSetting.test.tsx
frontend/e2e/j2-planning-enlistment.spec.ts
frontend/e2e/j3-end-of-term.spec.ts
frontend/e2e/j4-elective.spec.ts
```
`ProfessorOnlyDialog` backs Mark passed, Withdraw and Mark incomplete (title/confirm label differ).

### Testing
- `applyDecision.test.ts`: each attempt op and fill; recomputed CGPA matches the worked example; capStatus updates.
- `useCourseActions.test.tsx` (MSW): local BLOCKED sends no request; ALLOWED updates cache before the response arrives; 422 rolls back and shows server violations; 409 opens acknowledgement then resends with `acknowledged`; network error rolls back with toast; two rapid actions are serialized.
- `CourseNode.test.tsx`: each of 7 states renders its icon + label; each eligibility renders its badge; aria-label composition; unfilled slot shows "Choose course".
- `CourseDetailPanel.test.tsx`: button sets per state (§2.3 table); exemption badge only with a note and no warning/block; attempt history order and null-grade rendering; dialogs show/hide grade fields per action; 0.0 helper text; Fail kind radio.
- `UnitCapSetting.test.tsx`: set, clear, bounds, over-cap banner after lowering.
- E2E (API mocked with a stateful MSW-style handler that uses the domain engine to respond, so flows are realistic): **J2** set cap 18 → start eligible course shows "3 / 18 units" → locked course shows "Requires CCPROG2" → soft-warning course → "Continue anyway" → push to 21 → blocked "21 / 18 units". **J3** finish 3.5 with professor → CGPA updates; 0.0 → failed, CGPA drops, retake → finish → CGPA replaces 0.0; NSTP "Mark passed" has no grade field, CGPA unchanged; Withdraw → dependents stay locked. **J4** GE Elective → enter code/title/3 units → start.
- Latency (F29): in `useCourseActions.test.tsx`, the optimistic cache update occurs synchronously within the same tick as `run()` (well under 300 ms); RFC-018 measures in a real browser.

## 4. Considerations

- **Rules:** R-2.6, R-2.7, R-2.12, R-2.13, R-2.26 (no grades in URLs or analytics), R-2.29, R-2.30, R-4.7, R-4.9, R-4.12, R-4.13, R-4.16, R-6.1.
- **A11y scope:** icons + labels here; the full keyboard/screen-reader path is the list view (RFC-014), which reuses `CourseActionButtons` and the dialogs from this RFC.
- **Edge cases:** map query 404 (no curriculum) → redirect to `/upload`; server returns a state the local engine didn't predict → server wins silently (cache replaced), and a dev-only console warning records the mismatch (vector gap).
- **Privacy:** grade/professor values are never put in URLs, query keys beyond `['map']`, or `localStorage`.

## 5. Acceptance criteria

- **AC-011.1 [F28]** Every node shows its state by icon, border pattern and text label (§2.2 table) and, for NOT_TAKEN/FAILED/WITHDRAWN, an eligibility badge (Can start / Locked / Check first) computed by `domain/eligibility`; colour is never the only cue.
- **AC-011.2 [F22]** Starting a course with unmet hard prerequisites is refused locally and by the server with the message "Requires <codes>" shown in a `role="alert"` region.
- **AC-011.3 [F23, Q2]** A start blocked only by startable co-requisites offers "Start … together", which sends one START with all ids; otherwise the co-requisite message is shown.
- **AC-011.4 [F24]** A soft-prerequisite warning opens a dialog with the warning text and "Continue anyway"; continuing resends with `acknowledged: ["SOFT_PREREQ_UNMET"]` and succeeds.
- **AC-011.5 [F25]** A course with an exemption note shows the exemption badge in its detail panel and is never blocked or warned because of it.
- **AC-011.6 [F26]** The header load counter shows "X / Y units" (or "X units · no cap"); `/settings` lets the user set a cap of 1–60 or no cap; a start exceeding the cap shows "X / Y units"; lowering the cap below the load shows the §2.5 banner.
- **AC-011.7 [F27]** Actions returning `DOWNSTREAM_AFFECTED` show the affected courses and require "Continue anyway"; dependents' states are unchanged afterwards.
- **AC-011.8 [F30, D1]** Finish requires a grade (0.0 helper text shown), Mark passed/Withdraw/Mark incomplete show professor only, Fail offers graded vs pass/fail kind, Resolve incomplete offers result + grade/kind; each sends the matching action with the optional professor.
- **AC-011.9 [F31]** The detail panel lists all attempts oldest → newest with result, grade (or "—"), professor and date.
- **AC-011.10 [F32]** The header shows "CGPA 2.75" or "CGPA —" and updates immediately (optimistically) after an action; J3's E2E shows 0.0 lowering it and the retake replacing it.
- **AC-011.11 [F33]** An unfilled elective slot shows its category label and "Choose course"; starting it or choosing a course opens the fill dialog; the filled course keeps the category chip.
- **AC-011.12 [F29]** Allowed actions update the map cache synchronously before the server responds, roll back on any error, adopt the server response on success, and are serialized; locally blocked actions send no request.
- **AC-011.13 [Q4, Q7]** "Undo result" (with the D3 confirmation text) and "Cancel start" are available per the §2.3 table.
- **AC-011.14** J2, J3 and J4 E2E specs pass; axe reports no serious/critical violations on `/map` and `/settings`; the `/map` and `/settings` placeholders from RFC-001 are gone.
- **AC-011.15** Every file listed in §3 "File structure" exists, including `toSnapshot.ts`, `stateVisuals.tsx`, `CapWarningBanner.tsx`, `ExemptionBadge.tsx`, `AttemptHistory.tsx`, `CourseActionButtons.tsx`, all eight dialog files, `BlockedAlert.tsx`, `AppHeader.tsx`, `LoadCounter.tsx`, `CgpaDisplay.tsx` and `SettingsPage.tsx`.
