# RFC-007 — CGPA and Progress Calculators (Kotlin + TypeScript)

| | |
|---|---|
| **Status** | Draft |
| **Complexity** | Low |
| **Phase** | 4 — Graph & rules |
| **Features** | F32 (calculation), F36 (fill calculation) |
| **PRD** | FR-5.4, FR-5.5, FR-8.3 |
| **Predecessors:** | RFC-006 |
| **Successors:** | RFC-010 |

Sections omitted: *API*, *schema*, *UI* (header/bar are RFC-011/012), *auth*.

## 1. Summary

Two pure functions, each implemented in `backend/.../grades/` and `frontend/src/domain/`, sharing vectors through RFC-006's runner: the units-weighted CGPA (latest graded attempt per course; D1 fail kinds; Q3 weight) and the progress-bar fill ratio.

## 2. Algorithms

### 2.1 CGPA (F32)
```
gradedAttempt(a) = a.result == FINISHED  or  (a.result == FAILED and a.grade != null)     // D1: PASS_FAIL fail has null grade
for each course c:
   g = last attempt in c.attempts (oldest → newest) where gradedAttempt(a)
   if g exists: num += gradeHundredths(g.grade) * c.units ; den += c.units                  // Q3: units only, not extraUnits
cgpa = if den == 0 then null else roundHalfUp(num / den) in hundredths
```
- Kotlin: `BigDecimal` with `RoundingMode.HALF_UP`, scale 2. TS: integers only — `Math.floor((2 * num + den) / (2 * den))` on non-negative integers (equivalent to half-up), result in hundredths (R-2.11).
- Courses with `units == 0` and a graded attempt contribute nothing; if every graded course has 0 units, `den == 0` → `null`.
- `null` is displayed as `—` (F32); formatting is `formatCgpa(hundredths | null)` → `"2.75"` / `"—"`, also in both languages for the share-free owner view.
- "Latest graded" means a later WITHDRAWN/INCOMPLETE/PASS_FAIL-FAILED attempt does not hide an earlier 0.0 (F32 edge case: failed → retake → withdrawn keeps 0.0).
- An undone attempt (D3) is deleted, so it simply isn't in the list.
- `OPEN-QUESTION(Q3)` marker at the weight line.

### 2.2 Progress (F36)
```
earned   = Σ over courses with state ∈ {FINISHED, PASSED} of (units + extraUnits)
required = totalUnits + totalExtraUnits
fill     = if required == 0 then 0 else min(earned, required) / required       // returned as integer basis points 0–10000
```
Uses **state**, not attempts (a course whose latest result was undone isn't counted). Clamped at 100% because edited or filled electives can push earned above the printed total. Minor Programs never exist as rows (F10), so they're never counted.

## 3. Vectors (added to `shared/rule-vectors/`, runner dispatch extended with `cgpa` and `progress`)

Expected shapes: `{ "cgpaHundredths": 275 | null, "display": "2.75" | "—" }` and `{ "fillBasisPoints": 5000 }`. Inputs use RFC-006's `CurriculumSnapshot` plus, for progress, `totalUnits`/`totalExtraUnits`.

Required set:
`cgpa.worked-example-before-retake` (CCPROG1 3u 3.0 + NSSECU1 3u FAILED 0.0 → 150, "1.50") ·
`cgpa.worked-example-after-retake` (NSSECU1 attempts [FAILED 0.0, FINISHED 2.5] → 275, "2.75") — the PRD FR-5.4 example, literal per R-4.4 ·
`cgpa.no-graded-attempts-dash` · `cgpa.passed-excluded` · `cgpa.withdrawn-excluded` · `cgpa.incomplete-excluded` ·
`cgpa.pass-fail-failure-excluded` (D1) · `cgpa.failed-retake-withdrawn-keeps-zero` · `cgpa.extra-units-not-weighted` (Q3) ·
`cgpa.rounding-half-up` (3u 3.5 + 3u 3.0 + 1u 2.5 → 22/7 = 3.1428… → 314; and 1u 4.0 + 7u 3.0 → 25/8 = 3.125 → **313**, where banker's rounding would give 312) ·
`cgpa.zero-unit-course-ignored` ·
`progress.empty-zero` · `progress.finished-and-passed-count-with-extra-units` · `progress.failed-withdrawn-incomplete-not-counted` · `progress.clamped-at-100` · `progress.zero-required`.

## 4. Implementation details

### File structure
```
backend/src/main/kotlin/ph/anevaino/grades/CgpaCalculator.kt
backend/src/main/kotlin/ph/anevaino/grades/ProgressCalculator.kt
backend/src/test/kotlin/ph/anevaino/grades/CgpaCalculatorTest.kt
backend/src/test/kotlin/ph/anevaino/grades/ProgressCalculatorTest.kt
backend/src/test/kotlin/ph/anevaino/rules/RuleVectorTest.kt      (dispatch: cgpa, progress)
frontend/src/domain/cgpa.ts
frontend/src/domain/progress.ts
frontend/src/domain/cgpa.test.ts
frontend/src/domain/progress.test.ts
frontend/src/domain/ruleVectors.test.ts                          (dispatch: cgpa, progress)
shared/rule-vectors/cgpa.*.json
shared/rule-vectors/progress.*.json
```
`grades/` is pure like `rules/` (it reuses `rules/Snapshot.kt` types).

### Testing
Vectors are the main tests; `CgpaCalculatorTest`/`cgpa.test.ts` add the worked example as a named test (R-4.4) and a randomized cross-check (Kotlin: compare against exact `BigDecimal` division rounded; TS: compare integer formula against a `BigInt` reference). Coverage ≥ 90%.

## 5. Considerations

- **Rules:** R-2.3, R-2.8, R-2.11, R-4.4, R-6.3.
- **Contradiction:** PRD FR-4/FR-5.2 say FAILED always implies 0.0; D1 (Matthew) overrides for pass/fail failures. PRD to be corrected (RFCS.md C2).
- **Per-term GPA:** not implemented (F46, R-0.3).

## 6. Acceptance criteria

- **AC-007.1 [F32]** CGPA = Σ(grade × units) ÷ Σ(units) over each course's latest attempt that is FINISHED or FAILED-with-grade, returned in hundredths rounded half-up, or `null` when the denominator is 0.
- **AC-007.2 [F32]** The worked example produces 1.50 before and 2.75 after the retake, as the vectors `cgpa.worked-example-before-retake` and `cgpa.worked-example-after-retake` and as named unit tests on both sides.
- **AC-007.3 [F32, D1]** PASSED, WITHDRAWN, INCOMPLETE and PASS_FAIL-kind FAILED (null grade) attempts are excluded; a later non-graded attempt doesn't hide an earlier 0.0.
- **AC-007.4 [F32, Q3]** The weight is `units` only; `extraUnits` never affect CGPA; the line carries `OPEN-QUESTION(Q3)`.
- **AC-007.5 [F32]** `formatCgpa` returns two decimals or `—` identically in Kotlin and TS.
- **AC-007.6 [F36]** Fill = min(Σ(units+extraUnits) of FINISHED and PASSED courses, required) ÷ (totalUnits + totalExtraUnits) in basis points; 0 when required is 0.
- **AC-007.7** Every vector in §3 exists and passes in both runners; the runners dispatch `cgpa` and `progress`.
- **AC-007.8** Kotlin uses `BigDecimal` and TS uses integer arithmetic only, verified by the randomized cross-checks; coverage ≥ 90% line and branch.
- **AC-007.9** Every file listed in §4 "File structure" exists, including `ProgressCalculator.kt`, `ProgressCalculatorTest.kt`, `progress.ts`, `progress.test.ts`.
