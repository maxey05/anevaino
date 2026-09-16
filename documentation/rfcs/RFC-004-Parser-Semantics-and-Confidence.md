# RFC-004 — Parser II: Course Fields, Prerequisite Markers, Warnings and Parse Confidence

| | |
|---|---|
| **Status** | Draft |
| **Complexity** | High |
| **Phase** | 2 — Parser v1 |
| **Features** | F8, F9, F10, F11 (detection), F12 (generation), F13 (evaluation), F15 (accuracy harness + CCS gate only) |
| **PRD** | FR-2.3, FR-2.4, FR-2.7, FR-2.8, FR-2.9, FR-2.11, G1, NFR Maintainability |
| **Predecessors:** | RFC-003 |
| **Successors:** | RFC-005, RFC-013 |

Sections omitted: *API contracts* (RFC-005), *Database schema* (none — `ParsedCurriculum` is an in-memory draft), *UI* (RFC-009).

## 1. Summary

Interprets RFC-003's raw lines into a `ParsedCurriculum` draft: course fields incl. parenthetical extra units, year/term context, mandatory-elective slots, typed prerequisite edges, exemption notes, dropped Minor Program placeholders, row-linked warnings, and the `NORMAL | LOW_CONFIDENCE` verdict. Adds the fixture accuracy harness and a CI gate requiring the CCS sample to score ≥ 90% (RULES phase 2 exit gate). Completes R-3.3's pipeline: `CourseRowParser → PrerequisiteParser → ConfidenceEvaluator`, fronted by one `ChecklistParser` facade.

## 2. Technical approach

### 2.1 Facade
```kotlin
object ChecklistParser {
  fun parse(pdf: ByteArray, settings: ConfidenceSettings): ParseOutcome
}
sealed interface ParseOutcome {
  data class Parsed(val curriculum: ParsedCurriculum) : ParseOutcome
  data class Rejected(val reason: RejectReason) : ParseOutcome
}
enum class RejectReason { NOT_PDF, ENCRYPTED, CORRUPT, NO_TEXT_LAYER, NO_CHECKLIST }
data class ConfidenceSettings(val termUnitTolerance: Int, val maxMismatchedTerms: Int)
```
`NO_CHECKLIST` when `checklistFound == false` **or** zero course rows survive (F11). There is no diagram-arrow attempt (F50; see RFCS.md conflict C8).

### 2.2 Draft model (also the JSON contract RFC-005 returns)
```kotlin
data class ParsedCurriculum(
  val totalUnits: Int?, val totalExtraUnits: Int?,
  val parseConfidence: ParseConfidence,               // NORMAL | LOW_CONFIDENCE
  val courses: List<ParsedCourse>,
  val prerequisites: List<ParsedPrerequisite>,
  val warnings: List<ParseWarning>)
data class ParsedCourse(val code: String, val title: String, val units: Int?, val extraUnits: Int,
  val year: Int?, val term: Int?, val slotType: SlotType, val exemptionNote: String?)   // SlotType = NONE | MANDATORY_ELECTIVE
data class ParsedPrerequisite(val courseCode: String, val requiresCode: String, val type: PrerequisiteType) // HARD|SOFT|COREQ
data class ParseWarning(val code: WarningCode, val message: String, val courseCode: String?, val rawText: String?, val page: Int?)
```
Nullable `units/year/term` are how "couldn't read" is represented without inventing a value (R-3.4); RFC-009's save validation requires them.

### 2.3 Term context (CourseRowParser)
Walk `ChecklistLine`s in order, keeping `currentYear`, `currentTerm`:
- `Heading` → year from ordinal words/numbers (`first|1st → 1` … `fifth|5th → 5`), term from `term\s*([1-3])` / `(first|second|third)\s+term`. A heading may set both.
- A `CourseRow` takes the current values; if either is null → `MISSING_TERM_CONTEXT` warning. `term > 3` → `TERM_OUT_OF_RANGE` warning and `term = null` (trimester assumption, F8).
- `TotalRow` → parse `(\d+)\s*(?:\((\d+)\))?` and record it against `(currentYear, currentTerm)`.
- After the walk: the curriculum total is the `TotalRow` with the largest unit value **if** it is strictly greater than every other total; it is removed from the term totals. None → `totalUnits = null` + `TOTAL_NOT_FOUND`.
- `Unassigned` → `UNREADABLE_ROW` (or `HEADER_REUSED` passthrough) with `rawText`.

### 2.4 Course fields
- `code`: trimmed, internal whitespace collapsed, uppercased unless it contains a lowercase word (elective labels like `GE Elective` keep their casing).
- `/^MINOR\d+$/i` → row dropped entirely (F10); code remembered in `minorCodes` so references to it never warn.
- `title`: whitespace-collapsed TITLE cell.
- Units cell: `^\s*(\d+)?\s*(?:\((\d+)\))?\s*$` → `units = g1 ?: 0` when g2 present, else `g1`; `extraUnits = g2 ?: 0`. `NSTP-01 (3)` style (extra in the code cell) is also checked: a trailing `\((\d+)\)` on the CODE cell moves into `extraUnits`. No match → `units = null`, `UNREADABLE_UNITS`.
- `slotType = MANDATORY_ELECTIVE` when code or title matches `/elec(tive)?/i`, else `NONE`. A range code like `NSELEC1-3` is kept as **one** row with `ELECTIVE_RANGE` warning — the PRD doesn't say whether it means one or three slots, and the parser must not guess (R-3.4); the student splits it in the editor. (Flagged as gap G1 in RFCS.md.)
- Duplicate code → second row kept, `DUPLICATE_CODE` warning (the editor's save validation blocks it).

### 2.5 PrerequisiteParser (F9)
Tokenize the PREREQUISITES cell:
```
tokens = regex /\((H|S|C|E)\)|[A-Z][A-Z0-9-]{2,}/  over the cell, left to right
marker = null
for t in tokens:
  if t is marker: marker = t; continue
  code = t
  if marker == null: warn UNMARKED_PREREQUISITE(code); continue        // no edge
  if the previous token was a code (not a marker): warn INHERITED_MARKER(code, marker)
  when marker:
    E -> exemptionNotes += code                                        // never an edge, never resolved
    H/S/C -> if code == course.code: warn SELF_PREREQUISITE; else edges += (course, code, type)
```
Words `none`, `-`, `n/a` produce nothing. Duplicate edges are de-duplicated. `exemptionNote = exemptionNotes.joinToString(", ")` or null.
After all courses: for each H/S/C edge whose `requiresCode` isn't an extracted course code and isn't in `minorCodes` → `UNRESOLVED_PREREQUISITE` warning (the edge stays in the draft so the editor can show it; save validation blocks it). `(E)` codes are never checked (FR-2.7).

### 2.6 ConfidenceEvaluator (F13)
```
unresolved = count(UNRESOLVED_PREREQUISITE)
mismatchedTerms = for each (year, term) present in courses OR in term totals:
     printed = termTotals[(year,term)]
     if printed == null -> mismatched (and TERM_TOTAL_MISSING warning)
     else if |Σunits − printed.units| > tol or |ΣextraUnits − printed.extra| > tol -> mismatched (TERM_TOTAL_MISMATCH warning with both numbers)
confidence = if (unresolved > 0 || mismatchedTerms > settings.maxMismatchedTerms) LOW_CONFIDENCE else NORMAL
```
Courses with `units == null` contribute 0 to Σ. Settings come from `anevaino.parser.confidence.term-unit-tolerance` (start: **3**) and `…max-mismatched-terms` (start: **1**), deliberately loose per R-3.7; RFC-013 tunes them against real fixtures.

### 2.7 Warning codes
`UNREADABLE_ROW, HEADER_REUSED, UNREADABLE_UNITS, MISSING_TERM_CONTEXT, TERM_OUT_OF_RANGE, TOTAL_NOT_FOUND, TERM_TOTAL_MISSING, TERM_TOTAL_MISMATCH, DUPLICATE_CODE, ELECTIVE_RANGE, UNMARKED_PREREQUISITE, INHERITED_MARKER, SELF_PREREQUISITE, UNRESOLVED_PREREQUISITE`. Each has a fixed plain-language message template in `WarningCode.kt` (e.g. `UNRESOLVED_PREREQUISITE`: "Prerequisite {code} doesn't match any course in this flowchart.").

## 3. Fixture format and accuracy harness

`fixtures/flowcharts/<college>/expected.json`:
```json
{
  "college": "CCS", "program": "BSCS-NIS", "pdf": "bscs-nis.pdf",
  "supported": true,
  "expectedConfidence": "NORMAL",
  "totalUnits": 176, "totalExtraUnits": 9,
  "courses": [ { "code": "CCPROG1", "title": "…", "units": 3, "extraUnits": 0, "year": 1, "term": 1,
                 "slotType": "NONE", "exemptionNote": null } ],
  "prerequisites": [ { "course": "CCPROG2", "requires": "CCPROG1", "type": "HARD" } ]
}
```
(`176 (9)` is quoted from the PRD; every other value must come from the PDF.)

**Score** (per fixture):
```
courseMatch(e, p) = code equal AND title equal after whitespace-collapse + case-fold
                    AND units, extraUnits, year, term, slotType equal
                    AND exemptionNote equal (null == null)
matchedCourses  = |{e ∈ expected.courses : ∃ p courseMatch}|
matchedLinks    = |expected.prerequisites ∩ parsed.prerequisites|   (triple equality)
spurious        = (parsed.courses not matched by code) + (parsed links not in expected)
accuracy        = (matchedCourses + matchedLinks) / (|expected.courses| + |expected.prerequisites| + spurious)
```
Spurious items are in the denominator so an invented row lowers the score (R-3.4). `FixtureAccuracyTest` discovers every folder with `expected.json` and `supported: true`, asserts accuracy ≥ 0.90 and `parseConfidence == expectedConfidence`, and writes `backend/build/reports/parser-accuracy.md` (a per-fixture table). The CI job `parser-accuracy` runs it and uploads the report. `supported: false` handling is RFC-013's.

## 4. Implementation details

### File structure
```
backend/src/main/kotlin/ph/anevaino/parser/ChecklistParser.kt
backend/src/main/kotlin/ph/anevaino/parser/ParseOutcome.kt
backend/src/main/kotlin/ph/anevaino/parser/model/ParsedCurriculum.kt
backend/src/main/kotlin/ph/anevaino/parser/model/ParseWarning.kt
backend/src/main/kotlin/ph/anevaino/parser/model/WarningCode.kt
backend/src/main/kotlin/ph/anevaino/parser/model/Enums.kt
backend/src/main/kotlin/ph/anevaino/parser/semantics/TermContextTracker.kt
backend/src/main/kotlin/ph/anevaino/parser/semantics/CourseRowParser.kt
backend/src/main/kotlin/ph/anevaino/parser/semantics/UnitsCellParser.kt
backend/src/main/kotlin/ph/anevaino/parser/semantics/PrerequisiteParser.kt
backend/src/main/kotlin/ph/anevaino/parser/semantics/ConfidenceEvaluator.kt
backend/src/main/kotlin/ph/anevaino/parser/semantics/ConfidenceSettings.kt
backend/src/test/kotlin/ph/anevaino/parser/semantics/TermContextTrackerTest.kt
backend/src/test/kotlin/ph/anevaino/parser/semantics/CourseRowParserTest.kt
backend/src/test/kotlin/ph/anevaino/parser/semantics/UnitsCellParserTest.kt
backend/src/test/kotlin/ph/anevaino/parser/semantics/PrerequisiteParserTest.kt
backend/src/test/kotlin/ph/anevaino/parser/semantics/ConfidenceEvaluatorTest.kt
backend/src/test/kotlin/ph/anevaino/parser/ChecklistParserTest.kt
backend/src/test/kotlin/ph/anevaino/parser/accuracy/FixtureExpectation.kt
backend/src/test/kotlin/ph/anevaino/parser/accuracy/FixtureAccuracy.kt
backend/src/test/kotlin/ph/anevaino/parser/accuracy/FixtureAccuracyTest.kt
.github/workflows/ci.yml   (adds job parser-accuracy)
```
`Enums.kt` holds `SlotType`, `PrerequisiteType`, `ParseConfidence` with exactly the strings of R-2.2/§6.2.

### Testing
- `UnitsCellParserTest`: `3`→(3,0); `(3)`→(0,3); `0 (3)`→(0,3); `3 (1)`→(3,1); `three`→null+warning; code cell `NSTP-01 (3)` → extra 3.
- `PrerequisiteParserTest`: `(H) CCPROG1, (S) CCPROG2` → HARD + SOFT; `(C) A (E) BASMATH` → COREQ edge + note; `(H) A, B` → B inherits with warning; `A` alone → unmarked warning, no edge; self-reference; `none`; duplicate edge.
- `CourseRowParserTest`: `MINOR01`–`MINOR04` dropped and references to them unwarned; `GE Elective` → MANDATORY_ELECTIVE; `NSELEC1-3` → one row + `ELECTIVE_RANGE`; duplicate code warning; row before heading → `MISSING_TERM_CONTEXT`.
- `ConfidenceEvaluatorTest`: all-matching → NORMAL; one unresolved code → LOW; mismatched terms = max → NORMAL, = max+1 → LOW; missing term total counted as mismatched; tolerance boundary (|Δ| = tol → match, tol+1 → mismatch).
- `ChecklistParserTest`: synthetic PDF with no header → `Rejected(NO_CHECKLIST)`; loader failures propagate as `Rejected`; a synthetic "LOW" PDF (wrong term totals + unresolved code) → `LOW_CONFIDENCE`; CCS fixture → `NORMAL` in < 5 s total (NFR Performance).
- Coverage ≥ 90% line and branch for `parser/`.

## 5. Considerations

- **Rules:** R-2.1 (vocabulary), R-2.2, R-2.3, R-3.1, R-3.3–R-3.8, R-4.1, R-6.2.
- **Contradiction handled:** FR-2.4 calls the diagram page a "fallback … if the confidence check fires" but also says diagram parsing is out of scope. Followed the out-of-scope reading (F11, F50): LOW confidence shows a banner, it never triggers diagram parsing. See RFCS.md C8.
- **Performance:** all stages are linear in words; no regex backtracking hazards (patterns are anchored or token-level).
- **Security:** a malicious PDF can only yield strings; code/title lengths are capped at 32 / 200 chars with `UNREADABLE_ROW` warning beyond that, so the draft can't carry megabyte strings to the browser.

## 6. Acceptance criteria

- **AC-004.1 [F8]** Each course row yields `code`, `title`, `units`, `extraUnits`, `year`, `term`, `slotType`; all `UnitsCellParserTest` cases in §4 pass, including `NSTP-01 (3)` and `0 (3)`.
- **AC-004.2 [F8]** Year and term come from preceding headings; `term` is limited to 1–3; missing or out-of-range context yields `null` plus `MISSING_TERM_CONTEXT` / `TERM_OUT_OF_RANGE`.
- **AC-004.3 [F8]** `totalUnits`/`totalExtraUnits` come from the largest total row per §2.3, or are null with `TOTAL_NOT_FOUND`.
- **AC-004.4 [F8]** Codes/titles matching `/elec(tive)?/i` get `MANDATORY_ELECTIVE`; range codes stay one row with `ELECTIVE_RANGE`.
- **AC-004.5 [F9]** `(H)`, `(S)`, `(C)` produce `HARD`, `SOFT`, `COREQ` edges; `(E)` codes go into `exemptionNote` (comma-joined), never create an edge and are never resolution-checked; mixed-type cells split correctly — all `PrerequisiteParserTest` cases pass.
- **AC-004.6 [F9]** Unmarked codes produce `UNMARKED_PREREQUISITE` and no edge; marker inheritance produces `INHERITED_MARKER`; self-references produce `SELF_PREREQUISITE` and no edge.
- **AC-004.7 [F10]** `MINOR\d+` rows produce no course, are excluded from term sums and totals, and references to them never produce `UNRESOLVED_PREREQUISITE`.
- **AC-004.8 [F11]** `ChecklistParser.parse` returns `Rejected(NO_CHECKLIST)` when no checklist band exists or no course row survives, and returns `Rejected(<loader reason>)` for RFC-003's loader failures; no diagram parsing is attempted.
- **AC-004.9 [F12]** Every warning in §2.7 carries a code, a plain-language message from `WarningCode.kt`, and `courseCode`/`rawText`/`page` where known; `UNRESOLVED_PREREQUISITE` is emitted for H/S/C codes that match no extracted course and never for `(E)`.
- **AC-004.10 [F13]** `ConfidenceEvaluator` returns `LOW_CONFIDENCE` iff any H/S/C code is unresolved or mismatched terms exceed `maxMismatchedTerms`, using `termUnitTolerance`; both settings are constructor inputs (no hard-coded thresholds), starting defaults 3 and 1 are documented for RFC-005 to bind from `application.yml`.
- **AC-004.11 [F13]** A synthetic PDF produces `LOW_CONFIDENCE` and the CCS fixture produces `NORMAL` (RULES phase 2 exit gate).
- **AC-004.12 [F15]** `FixtureAccuracyTest` scores every `supported: true` fixture with the §3 formula (spurious items in the denominator), fails below 0.90 or on a confidence mismatch, and writes `build/reports/parser-accuracy.md`; the CI job `parser-accuracy` runs it on every PR.
- **AC-004.13 [F15]** The CCS fixture scores ≥ 0.90 and its full parse runs in < 5 s in CI.
- **AC-004.14** Code > 32 chars or title > 200 chars is truncated to a warning (`UNREADABLE_ROW`), never passed through.
- **AC-004.15** `parser/` coverage ≥ 90% line and branch; KDoc on all public functions; still no Spring/JPA imports.
- **AC-004.16** Every file listed in §4 "File structure" exists, including `ParseOutcome.kt`, `Enums.kt`, `TermContextTracker.kt`, `ConfidenceSettings.kt`, `FixtureExpectation.kt`, `FixtureAccuracy.kt`, and the `parser-accuracy` CI job in `ci.yml`.
