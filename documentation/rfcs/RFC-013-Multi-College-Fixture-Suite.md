# RFC-013 — Multi-College Parser Fixture Suite and Confidence Tuning

| | |
|---|---|
| **Status** | Draft |
| **Complexity** | Medium (engineering) · High (uncertainty — this is the first real test of the one-parser bet) |
| **Phase** | 5 — Fixtures & game (wk 10–11); fixture *collection* starts in phase 1 |
| **Features** | F15, F13 (threshold tuning) |
| **PRD** | G1, FR-2.5, FR-2.11, NFR Maintainability, §9 wk 1–2 and 10–11, §10 still-open 1 |
| **Predecessors:** | RFC-004 |
| **Successors:** | RFC-018 |

Sections omitted: *API*, *schema*, *UI*, *auth* — the deliverables are fixtures, parser fixes and CI gates. (The one UI touch is data in `knownPrograms.ts`.)

## 1. Summary

Extends RFC-004's accuracy harness from one CCS sample to a sample flowchart from every DLSU college Matthew collects, fixes the **universal** parser wherever a college diverges (never by special-casing, R-3.1/R-3.9), escalates any college that can't be supported, and replaces the loose starting confidence thresholds with values tuned against real fixtures and measured by mutation testing.

## 2. Process

1. **Collect** (starts week 1–2): for each college, one current official flowchart PDF with a text layer → `fixtures/flowcharts/<college-slug>/<program-slug>.pdf`. Record source and date in `fixtures/flowcharts/README.md`. The list of colleges is whatever Matthew collects; this RFC does not assume a count.
2. **Hand-write** `expected.json` from the PDF (RFC-004 §3 schema) — never from parser output. A second read-through checks it (a wrong expectation silently lowers the bar).
3. **Run** `FixtureAccuracyTest`; read `parser-accuracy.md`.
4. **Fix** failures in the shared pipeline (patterns in `ChecklistHeaderPatterns`, heading/total regexes, word-join geometry ratios). Every fix re-runs all fixtures; a change that regresses any supported fixture below 0.90 does not merge (R-3.8).
5. **Escalate** a college that can't reach 0.90 without college-specific code: set `"supported": false` and `"unsupportedReason"` in its `expected.json`, open an issue, and record it under PRD §10 "Still open 1" for Matthew's decision (Q6 default: don't special-case; F48 stays Won't).
6. **Tune** confidence (§3).
7. **Extend** `frontend/src/features/upload/knownPrograms.ts` with each supported fixture's programme name.

## 3. Confidence tuning (F13, R-3.7)

`ConfidenceMutationTest` generates degraded variants of every supported fixture's extracted lines (before `CourseRowParser`):
- `drop-row`: remove one course row (one variant per row with units ≥ 1);
- `corrupt-prereq`: change one H/S/C code to a non-existent code;
- `merge-bands`: interleave the two bands of one page (simulates the FR-2.6 trap);
- `shift-term`: move one course row under the next term heading.

For a candidate `(termUnitTolerance, maxMismatchedTerms)` it computes:
- **false-LOW rate** = clean supported fixtures classified LOW (must be **0**);
- **detection rate** = mutations classified LOW (target **≥ 90%** overall, and 100% for `corrupt-prereq`, which is deterministic).

`ConfidenceTuningReport` sweeps tolerance 0–4 and max mismatched terms 0–2, writes `backend/build/reports/confidence-tuning.md`, and the chosen pair is the one with zero false-LOW and highest detection (ties → stricter). The chosen values replace the defaults in `application.yml`, and the report table is copied into `fixtures/flowcharts/README.md` with the date. If no pair reaches 90% detection with zero false-LOW, record the best pair and the shortfall and flag it to Matthew — do not silently accept.

## 4. Implementation details

### File structure
```
fixtures/flowcharts/README.md
fixtures/flowcharts/<college-slug>/<program-slug>.pdf          (one per collected college)
fixtures/flowcharts/<college-slug>/expected.json
backend/src/test/kotlin/ph/anevaino/parser/accuracy/FixtureExpectation.kt      (adds supported/unsupportedReason)
backend/src/test/kotlin/ph/anevaino/parser/accuracy/FixtureAccuracyTest.kt     (reports unsupported, doesn't fail on them)
backend/src/test/kotlin/ph/anevaino/parser/accuracy/ConfidenceMutations.kt
backend/src/test/kotlin/ph/anevaino/parser/accuracy/ConfidenceMutationTest.kt
backend/src/test/kotlin/ph/anevaino/parser/accuracy/ConfidenceTuningReport.kt
backend/src/main/resources/application.yml                                     (tuned values)
frontend/src/features/upload/knownPrograms.ts
.github/workflows/ci.yml                                                       (uploads confidence-tuning.md)
```

### CI
- `parser-accuracy` job: all `supported: true` fixtures ≥ 0.90 with expected confidence; unsupported fixtures listed with their current score in the report but don't fail.
- `ConfidenceMutationTest` runs with the configured thresholds and fails if false-LOW > 0 or detection drops below the rate recorded in the README (prevents silent erosion).
- The sweep (`ConfidenceTuningReport`) is tagged `@Tag("tuning")` and runs on demand (`./gradlew test -Ptuning`), not on every PR.

## 5. Considerations

- **Rules:** R-3.1, R-3.2, R-3.7, R-3.8, R-3.9, R-6.4, R-6.5.
- **Privacy/licensing:** flowcharts are public curriculum documents with no student data. Keep only unannotated official PDFs.
- **Risk:** if several colleges are unsupported, launch scope is Matthew's call (PRD §10 still-open 1). This RFC produces the evidence; it doesn't decide.
- **Performance:** every fixture must still parse in < 5 s.

## 6. Acceptance criteria

- **AC-013.1 [F15]** `fixtures/flowcharts/` contains a PDF and hand-written `expected.json` for every college Matthew collected, with source and date recorded in `fixtures/flowcharts/README.md`.
- **AC-013.2 [F15, G1]** Every `supported: true` fixture scores ≥ 0.90 in CI and matches its expected confidence; any fixture that can't is `supported: false` with an `unsupportedReason`, an open issue, and an entry under PRD §10 "Still open".
- **AC-013.3 [F15]** No parser code path depends on the college or file: RFC-003's import/branch test still passes and no fixture-specific constants were added (PR review checklist item, R-3.1).
- **AC-013.4 [F15]** Dedicated fixtures exist for two-column extraction (CCS real + synthetic geometries) and for both confidence outcomes (clean real fixtures = NORMAL; mutations and the synthetic LOW PDF = LOW).
- **AC-013.5 [F13]** `ConfidenceMutationTest` implements the four mutation kinds; with the configured thresholds, false-LOW = 0, `corrupt-prereq` detection = 100%, overall detection ≥ 90% (or the documented shortfall has been flagged to Matthew).
- **AC-013.6 [F13]** The chosen `term-unit-tolerance` and `max-mismatched-terms` are in `application.yml`, and the sweep table that justifies them is in `fixtures/flowcharts/README.md` with its date.
- **AC-013.7** `knownPrograms.ts` lists exactly the programmes of supported fixtures.
- **AC-013.8** Every fixture parses in < 5 s in CI.
- **AC-013.9** Every file listed in §4 "File structure" exists, including `ConfidenceMutations.kt`, `ConfidenceTuningReport.kt` and the CI upload of `confidence-tuning.md`.
