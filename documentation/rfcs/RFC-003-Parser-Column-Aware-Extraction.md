# RFC-003 — Parser I: PDF Loading and Column-Aware Checklist Extraction

| | |
|---|---|
| **Status** | Draft |
| **Complexity** | High |
| **Phase** | 2 — Parser v1 (wk 3–5) |
| **Features** | F7, F5 (parser-side checks: magic bytes, encrypted, no text layer, corrupt) |
| **PRD** | FR-2.2, FR-2.3, FR-2.5, FR-2.6, NFR Performance, NFR Maintainability |
| **Predecessors:** | RFC-001 |
| **Successors:** | RFC-004 |

Sections omitted: *API contracts* (pure library, no endpoint — RFC-005 exposes it), *Data model/schema* (none), *UI* (none), *Auth/caching* (not applicable to a pure function).

## 1. Summary

The highest-risk code in the project (FEATURES "Highest-risk features" #1). Turns PDF bytes into an ordered list of **raw checklist lines** — term/year headings, course rows split into cells, and total rows — with rows from the two side-by-side term blocks never interleaved. It does **not** interpret cells (units, markers, slot types): that is RFC-004. Everything lives in the pure `parser/` package (R-2.3) and follows the first three stages of R-3.3: `PdfTextLoader → ColumnBandDetector → RowAssembler`.

## 2. Scope

**In:** loading bytes with PDFBox 3.0.8 in memory-only mode; failure classification (not a PDF, encrypted, corrupt, no text layer); positional word extraction; checklist-table detection; band (term-block) detection; in-band column detection; row assembly with multi-line cells; synthetic-PDF test builder; CCS fixture wiring.
**Out:** cell semantics and warnings (RFC-004), timeout/executor/size limit (RFC-005), other colleges' fixtures (RFC-013).

## 3. Prerequisite outside code

`fixtures/flowcharts/ccs/bscs-nis.pdf` (the CCS BSCS-NIS sample Matthew already has) and `fixtures/flowcharts/ccs/expected.json` **written by hand from the PDF, never generated from parser output** (a generated expectation only proves the parser agrees with itself). Schema of `expected.json` is defined in RFC-004 §5; this RFC only uses its ordered list of course codes per term.

## 4. Technical approach

### 4.1 Data flow
```
ByteArray
  └─ PdfTextLoader.load(bytes) : LoadResult
        Failure(NOT_PDF | ENCRYPTED | CORRUPT | NO_TEXT_LAYER)
        Success(pages: List<PageWords>)          PageWords = (index, width, height, words: List<Word>)
  └─ ColumnBandDetector.detect(page) : List<Band> Band = (xStart, xEnd, columns: List<ColumnSpan>, headerY)
  └─ RowAssembler.assemble(pages, bandsPerPage) : ExtractedChecklist
        lines: List<ChecklistLine>   // reading order: page ↑, band left→right, y top→bottom
          ChecklistLine.Heading(text, page, band)
          ChecklistLine.CourseRow(cells: Map<ChecklistColumn,String>, page, band, lineCount)
          ChecklistLine.TotalRow(text, page, band)
          ChecklistLine.Unassigned(text, page, band, reason)   // becomes a ParseWarning in RFC-004
        checklistFound: Boolean
```
`ChecklistColumn = CODE | TITLE | UNITS | PREREQUISITES`.
`Word = (text, x, y, width, height, fontSize, page)` in PDF user-space units with y measured from the top.

### 4.2 PdfTextLoader
1. If the first 5 bytes aren't `%PDF-` → `NOT_PDF` (R-2.20).
2. Load with `Loader.loadPDF(RandomAccessReadBuffer(bytes), …)` using a memory-only stream cache capped at 64 MB. `InvalidPasswordException` or `document.isEncrypted` → `ENCRYPTED`. Any other `IOException` → `CORRUPT`.
3. A `PDFTextStripper` subclass with `sortByPosition = true` overrides `writeString(text, positions)` and collects `TextPosition`s; consecutive glyphs on the same baseline (|Δy| ≤ 0.3 × height) join into one `Word` unless the horizontal gap exceeds 0.25 × the glyph's `widthOfSpace` × 2 (a tunable constant in `ParserGeometry`, not a CCS coordinate).
4. If the whole document has fewer than 20 non-whitespace characters → `NO_TEXT_LAYER` (FR-2.2 scanned message is rendered by RFC-005).
5. Always close the document (`use {}`).

### 4.3 ColumnBandDetector — detect, don't hard-code (R-3.2)
Per page:
1. **Header anchors.** Find header rows: a set of words on one baseline (±2 pt) containing, case-insensitively, a match for each of the four column label patterns in `ChecklistHeaderPatterns`:
   `CODE: /course\s*code|code/`, `TITLE: /course\s*title|title|description/`, `UNITS: /units?|credits?/`, `PREREQUISITES: /pre.?\/?\s*co.?requisites?|pre.?requisites?|requisites?/`.
   Multi-word labels may span two baselines within 1.5 line heights; merge them first.
2. Each header row's CODE-label x begins a band; bands on the same page are sorted by x. A band's `xEnd` is the next band's `xStart − 1` or the page width. Column spans inside the band start at each label's x (with the CODE column starting at the band start) and end at the next label's x.
3. **Fallback when a page has no header row but earlier pages had bands:** reuse the most recent page's band/column *ratios* scaled to this page's width (layouts repeat across checklist pages). Record `Unassigned(reason = "HEADER_REUSED")` once per page so RFC-004 can warn.
4. Pages with no header and no earlier bands (e.g. page 1, the diagram) contribute no bands.
5. `checklistFound = at least one band on any page`.

### 4.4 RowAssembler
Within a band, take its words (x in `[xStart, xEnd]`), drop words above `headerY`, group into visual lines by baseline, then walk lines top→bottom:
```
for line in lines:
  cells = assign each word to the column whose span contains word.x (tolerance 3 pt left)
  if line matches HeadingPattern (e.g. /(first|second|third|fourth|fifth|1st|2nd|3rd|4th|5th)\s+year/i,
                                   /term\s*[1-3]|(first|second|third)\s+term/i) and CODE cell empty-or-heading:
       emit Heading(fullLineText)
  elif line matches TotalPattern (/^\s*total/i in any cell):
       emit TotalRow(fullLineText)
  elif cells[CODE] matches CourseCodeShape (/^[A-Z][A-Z0-9-]{2,}\d*[A-Z]?$/ after trimming, also allowing codes like "GE Elective"? — see below):
       start new CourseRow(cells)
  elif current CourseRow exists and cells[CODE] blank:
       append each non-blank cell to the current row's cell with a single space; lineCount++
  else:
       emit Unassigned(text, reason = "UNRECOGNIZED_LINE")
```
Elective slot rows whose code cell is words (e.g. `GE Elective`) are recognized when the CODE cell is non-blank and the UNITS cell contains a number — the shape check is `codeShape OR (codeCell.isNotBlank() AND unitsCell has a digit)`.

Heading and total patterns live in `ChecklistHeaderPatterns` and must be confirmed against the CCS fixture; if the sample uses different wording, update the patterns (they are wording patterns, not coordinates, so this does not violate R-3.2).

### 4.5 Why not `PDFTextStripperByArea`
FR-2.6 allows either. Area stripping needs rectangles known up front, which means coordinates — exactly what R-3.2 forbids. The custom positional pass is chosen.

## 5. Implementation details

### File structure
```
backend/src/main/kotlin/ph/anevaino/parser/extract/Word.kt
backend/src/main/kotlin/ph/anevaino/parser/extract/PdfTextLoader.kt
backend/src/main/kotlin/ph/anevaino/parser/extract/PositionalWordStripper.kt
backend/src/main/kotlin/ph/anevaino/parser/extract/LoadResult.kt
backend/src/main/kotlin/ph/anevaino/parser/extract/ParserGeometry.kt
backend/src/main/kotlin/ph/anevaino/parser/extract/ChecklistHeaderPatterns.kt
backend/src/main/kotlin/ph/anevaino/parser/extract/Band.kt
backend/src/main/kotlin/ph/anevaino/parser/extract/ColumnBandDetector.kt
backend/src/main/kotlin/ph/anevaino/parser/extract/ChecklistLine.kt
backend/src/main/kotlin/ph/anevaino/parser/extract/ExtractedChecklist.kt
backend/src/main/kotlin/ph/anevaino/parser/extract/RowAssembler.kt
backend/src/test/kotlin/ph/anevaino/parser/support/SyntheticChecklistPdf.kt
backend/src/test/kotlin/ph/anevaino/parser/support/Fixtures.kt
backend/src/test/kotlin/ph/anevaino/parser/extract/PdfTextLoaderTest.kt
backend/src/test/kotlin/ph/anevaino/parser/extract/ColumnBandDetectorTest.kt
backend/src/test/kotlin/ph/anevaino/parser/extract/RowAssemblerTest.kt
backend/src/test/kotlin/ph/anevaino/parser/extract/CcsExtractionOrderTest.kt
fixtures/flowcharts/ccs/bscs-nis.pdf
fixtures/flowcharts/ccs/expected.json
```

### Logging
None inside `parser/` (pure). RFC-005 logs duration and outcome.

### Testing strategy
- `SyntheticChecklistPdf` builds PDFs in-test with PDFBox: N bands per page, configurable x offsets, column widths, font sizes, multi-line titles, headings, total rows, a diagram-only page. This proves geometry is detected: the same assertions must pass for at least three different offsets/widths.
- `PdfTextLoaderTest`: `%PDF-` missing → NOT_PDF; encrypted synthetic PDF → ENCRYPTED; truncated bytes → CORRUPT; image-only (no text) PDF → NO_TEXT_LAYER; normal → words with positions.
- `ColumnBandDetectorTest`: two bands detected at three geometries; header split across two baselines; page with no header after a page with bands → ratios reused + `HEADER_REUSED`; diagram-only page → no bands.
- `RowAssemblerTest`: interleaving trap — left and right bands with rows at identical y values produce all left rows before all right rows; multi-line title merged into one row with `lineCount = 2`; `GE Elective` row recognized; total row and heading emitted; stray text → `Unassigned`.
- `CcsExtractionOrderTest`: on the real fixture, the sequence of `CourseRow` CODE cells (excluding `MINOR\d+`) equals the course-code order derived from `expected.json` (year ↑, term ↑, order within term), and extraction finishes in < 2 s on CI (leaving headroom within the 5 s budget for RFC-004's stages and cold JIT).
- Coverage: `parser/extract` ≥ 90% line and branch (RULES §4.1).

## 6. Considerations

- **Rules:** R-2.3, R-2.20 (magic bytes, memory-limited, encrypted), R-2.29, R-3.1, R-3.2, R-3.3, R-3.4, R-6.2 (KDoc on public functions).
- **Edge cases:** rotated pages (use `TextPosition.getXDirAdj/YDirAdj`); ligatures; ` ` non-breaking spaces (normalize to space); hyphenated codes wrapping across lines (`NSTP-` / `01`) — join when the CODE cell ends with `-`; a term block that spills onto the next page (heading absent at top of next page — rows continue under the last heading; RFC-004 handles term context).
- **Risk:** the heading/label wording in other colleges may differ — RFC-013 is where that surfaces; patterns are in one file for that reason.
- **Dependencies:** Apache PDFBox 3.0.8 only.

## 7. Acceptance criteria

- **AC-003.1 [F5]** `PdfTextLoader` classifies input as `NOT_PDF` (missing `%PDF-` magic bytes, regardless of any declared MIME type), `ENCRYPTED`, `CORRUPT`, or `NO_TEXT_LAYER` (< 20 non-whitespace characters), each covered by a named test in `PdfTextLoaderTest`.
- **AC-003.2 [F5]** PDFs load with a memory-only stream cache capped at 64 MB, the document is always closed, and nothing is written to disk (a test runs with `java.io.tmpdir` pointed at an empty directory and asserts it stays empty).
- **AC-003.3 [F7]** Bands and in-band columns are derived from detected header labels (`ChecklistHeaderPatterns`) and page width, with no absolute coordinates; `ColumnBandDetectorTest` passes for at least three synthetic geometries with different x offsets and column widths.
- **AC-003.4 [F7]** A page without a header row that follows a page with bands reuses that page's band/column ratios and records `HEADER_REUSED`; a diagram-only page yields no bands.
- **AC-003.5 [F7]** Rows from side-by-side bands never interleave: output order is page ascending, band left→right, top→bottom — proven by the equal-y synthetic test.
- **AC-003.6 [F7]** Multi-line cells are merged into one `CourseRow`; hyphen-wrapped codes are joined; word-coded elective rows (e.g. `GE Elective`) are recognized as course rows.
- **AC-003.7 [F7]** Headings, total rows and unrecognized lines are emitted as `Heading`, `TotalRow` and `Unassigned` respectively — no line is silently dropped (R-3.4).
- **AC-003.8 [F7]** `ExtractedChecklist.checklistFound` is false when no page has a band (consumed by RFC-004 for F11).
- **AC-003.9 [F7]** On `fixtures/flowcharts/ccs/bscs-nis.pdf`, the extracted course-code sequence equals the order in the hand-written `expected.json`, and extraction completes in < 2 s in CI.
- **AC-003.10 [F7]** The `parser/` package has no Spring, JPA or logging imports (enforced by a test that scans the package's imports), and there is no college-specific branching (R-3.1).
- **AC-003.11** `parser/extract` has ≥ 90% line and branch coverage, and every public function has KDoc.
- **AC-003.12** Every file listed in §5 "File structure" exists, including `Word.kt`, `LoadResult.kt`, `PositionalWordStripper.kt`, `ParserGeometry.kt`, `Band.kt`, `ChecklistLine.kt`, `ExtractedChecklist.kt`, the test support `SyntheticChecklistPdf.kt` and `Fixtures.kt`, and the fixture pair `bscs-nis.pdf` + hand-written `expected.json`.
