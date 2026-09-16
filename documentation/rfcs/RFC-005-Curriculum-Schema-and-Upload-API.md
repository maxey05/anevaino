# RFC-005 — Curriculum Schema and PDF Upload/Parse Endpoint

| | |
|---|---|
| **Status** | Draft |
| **Complexity** | Medium |
| **Phase** | 2 — Parser v1 |
| **Features** | F5 (service side), F11 (API error), F13 (config binding), F14, F44 (upload + curriculum ownership) |
| **PRD** | FR-2.2, FR-2.4, FR-2.10, FR-2.11, §6.2, NFR Security, NFR Privacy |
| **Predecessors:** | RFC-002, RFC-004 |
| **Successors:** | RFC-009 |

Sections omitted: *UI* (upload page is RFC-009 so upload and review ship as one flow).

## 1. Summary

Creates the persistent `curriculum`, `course` and `prerequisite` tables and their JPA entities/ownership-scoped repositories, and exposes the parser behind an authenticated multipart endpoint that enforces size, MIME and magic-byte limits, runs the parse on a bounded executor with a hard timeout, keeps the PDF only in memory, and returns the `ParsedCurriculum` draft (nothing is saved — saving is RFC-009).

## 2. Technical approach

```
POST /api/uploads (multipart "file")
  UploadController ─ rate limit (per user) ─ size/MIME check
    └─ UploadService.parse(bytes)
         └─ ParseExecutor.submit { ChecklistParser.parse(bytes, confidenceSettings) }.get(10 s)
               Rejected(reason) → ParseFailure(type) ; timeout → ParseFailure(parse/timeout)
         └─ ParsedCurriculumDto.from(curriculum)
  bytes reference dropped when the method returns (never stored, logged or written to temp)
```
- **Multipart in memory (F14):** `spring.servlet.multipart.max-file-size=10MB`, `max-request-size=11MB`, `file-size-threshold=11MB` (so the servlet container never spills the upload to a temp file).
- **Executor:** `ThreadPoolExecutor(core=1, max=2, queue=ArrayBlockingQueue(4))` with `AbortPolicy`; rejection → 503 `parse/busy`. On timeout `future.cancel(true)`; PDFBox may not observe interruption, so the bounded pool is what protects the 512 MB instance (R-1.4).
- **Rate limit:** 5 uploads per minute per user (R-2.22), reusing RFC-002's `TokenBucketRateLimiter`.
- **Config:** `ParserProperties` binds `anevaino.parser.confidence.term-unit-tolerance` (default 3), `…max-mismatched-terms` (default 1), `anevaino.parser.timeout` (default 10s) and builds `ConfidenceSettings` (R-3.7).
- `MeDto.hasCurriculum` becomes `curriculumRepository.existsByUserId(userId)`.

## 3. Interfaces

| Method | Path | Auth | Request | Success |
|---|---|---|---|---|
| POST | `/api/uploads` | session + CSRF | `multipart/form-data`, part `file` | 200 `ParsedCurriculumDto` |

`ParsedCurriculumDto` = JSON form of RFC-004 §2.2 (`totalUnits`, `totalExtraUnits`, `parseConfidence`, `courses[]`, `prerequisites[]` with `courseCode/requiresCode/type`, `warnings[]` with `code/message/courseCode/rawText/page`).

| Condition | Status | `type` | `detail` (user-facing, exact) |
|---|---|---|---|
| no `file` part / empty | 400 | `common/validation` | "Please choose a PDF file to upload." |
| > 10 MB | 413 | `parse/too-large` | "That file is over 10 MB. Please upload the official flowchart PDF." |
| declared type not `application/pdf` or magic bytes ≠ `%PDF-` | 415 | `parse/not-pdf` | "That file isn't a PDF." |
| encrypted | 422 | `parse/encrypted` | "This PDF is password-protected. Please upload an unlocked copy." |
| corrupt | 422 | `parse/corrupt` | "We couldn't open this PDF. It may be damaged." |
| no text layer | 422 | `parse/scanned` | "This looks like a scanned PDF, which isn't supported yet." (FR-2.2 verbatim, R-6.1) |
| no checklist | 422 | `parse/no-checklist` | "We couldn't find a Program Checklist table in this PDF. You can still build your map by hand in the editor." |
| timeout | 422 | `parse/timeout` | "Reading this PDF took too long. Please try again, or build your map by hand in the editor." |
| executor full | 503 | `parse/busy` | "We're reading other flowcharts right now. Please try again in a minute." |
| rate limited | 429 | `common/rate-limited` | – |

All slugs are added to `shared/problem-types.json` and both registries.

## 4. Data model — `V3__create_curriculum.sql`

```sql
CREATE TABLE curriculum (
  id                 UUID PRIMARY KEY,
  user_id            UUID NOT NULL UNIQUE REFERENCES app_user(id) ON DELETE CASCADE,  -- one per user (F54)
  program_label      TEXT NULL CHECK (program_label IS NULL OR length(program_label) <= 100),
  total_units        INTEGER NOT NULL CHECK (total_units >= 0),
  total_extra_units  INTEGER NOT NULL DEFAULT 0 CHECK (total_extra_units >= 0),
  parse_confidence   TEXT NOT NULL CHECK (parse_confidence IN ('NORMAL','LOW_CONFIDENCE')),
  created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE course (
  id              UUID PRIMARY KEY,
  curriculum_id   UUID NOT NULL REFERENCES curriculum(id) ON DELETE CASCADE,
  code            TEXT NOT NULL CHECK (length(code) BETWEEN 1 AND 32),
  title           TEXT NOT NULL CHECK (length(title) <= 200),
  units           INTEGER NOT NULL CHECK (units >= 0),
  extra_units     INTEGER NOT NULL DEFAULT 0 CHECK (extra_units >= 0),
  year            INTEGER NOT NULL CHECK (year BETWEEN 1 AND 10),
  term            INTEGER NOT NULL CHECK (term BETWEEN 1 AND 3),
  slot_type       TEXT NOT NULL CHECK (slot_type IN ('NONE','MANDATORY_ELECTIVE')),
  slot_label      TEXT NULL,
  exemption_note  TEXT NULL,
  state           TEXT NOT NULL DEFAULT 'NOT_TAKEN' CHECK (state IN
                    ('NOT_TAKEN','BEING_TAKEN','FINISHED','PASSED','FAILED','WITHDRAWN','INCOMPLETE')),
  UNIQUE (curriculum_id, code),
  CHECK ((slot_type = 'MANDATORY_ELECTIVE') = (slot_label IS NOT NULL))
);
CREATE TABLE prerequisite (
  course_id           UUID NOT NULL REFERENCES course(id) ON DELETE CASCADE,
  requires_course_id  UUID NOT NULL REFERENCES course(id) ON DELETE CASCADE,
  type                TEXT NOT NULL CHECK (type IN ('HARD','SOFT','COREQ')),
  PRIMARY KEY (course_id, requires_course_id),
  CHECK (course_id <> requires_course_id)
);
CREATE INDEX idx_course_curriculum ON course(curriculum_id);
CREATE INDEX idx_prereq_requires ON prerequisite(requires_course_id);
```
**Additions to PRD §6.2, flagged:** `Course.slotLabel` — FR-6.1 says a filled slot "keeps its original category label", which needs a column separate from the replaced `code`; `updatedAt` on curriculum. `app_user` naming per RFC-002. One edge per course pair (a pair can't be both HARD and SOFT).

## 5. Implementation details

### File structure
```
backend/src/main/resources/db/migration/V3__create_curriculum.sql
backend/src/main/kotlin/ph/anevaino/curriculum/Curriculum.kt
backend/src/main/kotlin/ph/anevaino/curriculum/Course.kt
backend/src/main/kotlin/ph/anevaino/curriculum/Prerequisite.kt
backend/src/main/kotlin/ph/anevaino/curriculum/CourseState.kt
backend/src/main/kotlin/ph/anevaino/curriculum/CurriculumRepository.kt
backend/src/main/kotlin/ph/anevaino/curriculum/CourseRepository.kt
backend/src/main/kotlin/ph/anevaino/parser/web/UploadController.kt
backend/src/main/kotlin/ph/anevaino/parser/web/UploadService.kt
backend/src/main/kotlin/ph/anevaino/parser/web/ParseExecutor.kt
backend/src/main/kotlin/ph/anevaino/parser/web/ParserProperties.kt
backend/src/main/kotlin/ph/anevaino/parser/web/ParsedCurriculumDto.kt
backend/src/main/resources/application.yml   (multipart + anevaino.parser.* keys)
backend/src/test/kotlin/ph/anevaino/curriculum/CurriculumSchemaTest.kt
backend/src/test/kotlin/ph/anevaino/parser/web/UploadControllerIntegrationTest.kt
backend/src/test/kotlin/ph/anevaino/parser/web/ParseExecutorTest.kt
shared/problem-types.json                    (parse/* slugs)
```
`parser/web` is the Spring-aware adapter; `parser/` proper stays pure (R-2.3). Entity `state` maps to `curriculum/CourseState.kt` (see §6 for why it is defined here and not in `rules/`).

### Repositories (R-2.19)
`CurriculumRepository.findByUserId(userId)`, `findByIdAndUserId(id, userId)`, `existsByUserId(userId)`. `CourseRepository.findByIdAndCurriculumUserId(id, userId)`. No unscoped `findById` is used by any service (enforced by a test that greps service sources for `.findById(`).

### Logging
`INFO parse.finished userId=… durationMs=… confidence=… courses=… warnings=…`; `WARN parse.low_confidence userId=…`; `INFO parse.rejected userId=… reason=…`. Never file names or content (R-2.20, R-2.26).

### Testing
- `CurriculumSchemaTest` (Testcontainers): second curriculum for same user violates unique; duplicate `(curriculum_id, code)` rejected; self-edge rejected; bad enum rejected; `MANDATORY_ELECTIVE` without `slot_label` rejected; deleting `app_user` cascades to curriculum → course → prerequisite (0 rows remain).
- `UploadControllerIntegrationTest`: CCS fixture → 200 with `parseConfidence: NORMAL` and course count equal to `expected.json`; 11 MB → 413; `.txt` renamed `.pdf` with `application/pdf` → 415 (magic bytes); encrypted, corrupt, scanned, no-checklist synthetic PDFs → exact types and detail strings of §3; unauthenticated → 401; no CSRF → 403; 6th upload in a minute → 429; after a request `java.io.tmpdir` contains no new files; no `curriculum` row is created by upload.
- `ParseExecutorTest`: a parser stub sleeping 11 s → `parse/timeout` within ~10 s; 7 concurrent slow submissions → at least one `parse/busy`.

## 6. Considerations

- **Rules:** R-1.4, R-2.3, R-2.9, R-2.10, R-2.19, R-2.20, R-2.22, R-2.25, R-2.26, R-3.7, R-4.3, R-4.6, R-4.8, R-6.1.
- **Parallel-branch note:** RFC-005 does not depend on RFC-006. To avoid a hidden dependency, `CourseState` is defined **here** in `curriculum/CourseState.kt` as the 7-value enum from R-2.2, and RFC-006's pure `rules/` package defines its own identical enum for pure use; RFC-010 maps between them with an exhaustive `when` (a compile-time guarantee they stay aligned).
- **Privacy:** the byte array is a local variable; no `MultipartFile.transferTo`, no caching.
- **Performance:** CCS parse < 5 s end-to-end measured in the integration test (warm).

## 7. Acceptance criteria

- **AC-005.1** `V3__create_curriculum.sql` creates `curriculum`, `course`, `prerequisite` exactly as in §4, including `UNIQUE(user_id)`, `UNIQUE(curriculum_id, code)`, enum/grade-free CHECKs, the slot-label CHECK, and `ON DELETE CASCADE` down from `app_user`; every case in `CurriculumSchemaTest` passes against real Postgres.
- **AC-005.2** JPA entities `Curriculum`, `Course`, `Prerequisite` validate against the schema (`ddl-auto=validate`), and `CourseState` (in `curriculum/CourseState.kt`) contains exactly the 7 R-2.2 values.
- **AC-005.3 [F44]** Repositories expose only user-scoped lookups (`findByUserId`, `findByIdAndUserId`, `existsByUserId`, `findByIdAndCurriculumUserId`); a test fails if any service calls an unscoped `findById`.
- **AC-005.4 [F5]** `POST /api/uploads` accepts a ≤ 10 MB `application/pdf` with `%PDF-` magic bytes and returns 200 `ParsedCurriculumDto`; the CCS fixture returns `NORMAL` and the expected course count.
- **AC-005.5 [F5, F11]** Each failure row in §3 returns its exact status, `type` and `detail` string, with the scanned message verbatim from FR-2.2.
- **AC-005.6 [F5]** Parsing runs on the bounded executor (core 1, max 2, queue 4) with a configurable timeout defaulting to 10 s; timeout → `parse/timeout`, saturation → 503 `parse/busy`, and the server keeps serving requests afterwards (`/api/health` 200 in the same test).
- **AC-005.7 [F14]** The upload is never written to disk, database or logs: multipart threshold ≥ max request size, no temp files appear during the integration test, and no `curriculum`/`course` row exists after an upload.
- **AC-005.8 [F13]** `anevaino.parser.confidence.term-unit-tolerance` (3) and `…max-mismatched-terms` (1) and `anevaino.parser.timeout` (10s) are read from `application.yml` via `ParserProperties`; changing them in a test profile changes the outcome of a boundary fixture.
- **AC-005.9** Uploads are rate-limited to 5/minute per user (429 on the 6th); the endpoint requires a session (401) and CSRF (403).
- **AC-005.10** `MeDto.hasCurriculum` reflects whether the user has a curriculum row.
- **AC-005.11** Log lines for parse finished/low-confidence/rejected contain user id, duration, confidence/reason and counts, and no file name or content.
- **AC-005.12** All `parse/*` slugs are present in `shared/problem-types.json`, `ProblemTypes.kt` and `problemTypes.ts` (parity tests pass).
- **AC-005.13** Every file listed in §5 "File structure" exists, including `ParseExecutor.kt`, `ParserProperties.kt`, `ParsedCurriculumDto.kt`, `CourseRepository.kt`, `ParseExecutorTest.kt` and `CourseState.kt`.
