# RFC-018 — Launch Readiness Gate and Course-Finished Celebration

| | |
|---|---|
| **Status** | Draft |
| **Complexity** | Medium |
| **Phase** | 7 — Beta → launch (wk 13–14) |
| **Features** | F38; verification of F29 (latency), F44 (full cross-user matrix), R-5.3 "done" for every Must |
| **PRD** | FR-8.5, §6 NFRs, §8, §9 wk 13–14 |
| **Predecessors:** | RFC-013, RFC-014, RFC-015, RFC-016, RFC-017 |
| **Successors:** | none |

Sections omitted: *schema*, *new API* (none).

## 1. Summary

The last RFC before public launch. It adds the one Could-have (a short, reduced-motion-aware celebration when a course is finished) and turns RULES §5.3's definition of done into automated gates across the whole app: full-stack E2E of J1–J5 on a production-like build, a cross-user access matrix that covers every owned endpoint, real-browser latency budgets, a placeholder/marker audit, and a deployment checklist.

## 2. Celebration (F38)

- Trigger: in `useCourseActions`, when an ALLOWED decision changes a course to `FINISHED` (not PASSED, per F38's wording), after the optimistic update.
- `CelebrationBurst`: a ≤ 1.2 s CSS keyframe animation of 8 self-drawn pixel sparkles (`assets/sparkle.png`, 8×8) around the node (graph) or the row's state cell (list), `aria-hidden="true"`, `pointer-events: none`. The existing live-region announcement (RFC-014) is the accessible equivalent.
- `@media (prefers-reduced-motion: reduce)` → the component renders nothing (R-4.13).
- If the server later rolls the action back, the animation simply ends; no second effect.

## 3. Launch gates

### 3.1 Full-stack E2E
- Backend runs with `./gradlew bootTestRun` in profile `e2e`, where `FakeGoogleIdTokenVerifier` (in **`src/test`**, so it can never be packaged) accepts signed test tokens for `alice@dlsu.edu.ph`, `bob@dlsu.edu.ph` and `mallory@gmail.com`; Postgres via Testcontainers; frontend `vite build` + `vite preview` with the `/api` proxy.
- `fullstack/j1…j5.spec.ts` run the five journeys against this stack (no API mocking), including a real CCS fixture upload, the CGPA 1.50 → 2.75 path, and J5 in a cookie-less second context.
- A startup guard (`ProfileGuard`) refuses to start when both `prod` and `e2e` profiles are active.

### 3.2 Cross-user matrix (F44)
`OwnershipMatrixTest` enumerates every handler from `RequestMappingHandlerMapping`; each must appear in `EndpointOwnership.kt` as `PUBLIC` (health, csrf, auth/google, shared view) or `OWNED(testCase)`. The test fails for an unlisted endpoint, and runs each `OWNED` case as Bob against Alice's resources, expecting 404 (or 400 for foreign ids inside Bob's own curriculum path, per RFC-010).

### 3.3 Performance budgets (R-2.29)
`perf.spec.ts` on the full stack (warm server), 80-course curriculum:
- state change visible (node label changes) < 300 ms after click-confirm, via `performance.mark` in `useCourseActions` read by Playwright;
- `course-actions` response < 1 s (p95 of 20 actions);
- `GET map` < 1 s; parse of every fixture < 5 s (already in RFC-013 CI);
- graph pan/zoom: Chrome trace over a 3 s pan shows no long task > 100 ms.

### 3.4 Completeness audit (R-6.3, R-5.3)
`scripts/audit-markers.mjs` (run in CI) fails on `TODO`, `FIXME`, `XXX`, "placeholder" or `it.skip`/`@Disabled` in `backend/src`, `frontend/src`, `shared/`; collects every `OPEN-QUESTION(Q<n>)` marker and fails if a Q-number isn't in RFCS.md's open-question table or if a question marked resolved there still has markers. It also asserts no route renders RFC-001's placeholder component (the component itself is deleted in this RFC).

### 3.5 Deployment checklist
`documentation/LAUNCH-CHECKLIST.md`, each item checked with date and initials in the launch PR:
- Render free-tier limits re-checked at render.com/docs/free on launch week (PRD §6.1); Neon limits re-checked.
- Google OAuth consent screen configured and published; authorised JavaScript origin = production Vercel domain; `GOOGLE_CLIENT_ID`/`VITE_GOOGLE_CLIENT_ID` match.
- All env vars from `.env.example` set on Render/Vercel; no secrets in the repo (`git log -p | gitleaks` clean — run locally, not a new dependency).
- `SPRING_PROFILES_ACTIVE=prod`, JSON logs, `-XX:MaxRAMPercentage=75`.
- HTTPS-only confirmed; security headers verified on production with `curl -I`.
- Every supported college fixture ≥ 90% (RFC-013 report attached); unsupported colleges have Matthew's recorded decision.
- Privacy contact set; privacy page reviewed.
- Beta (week 13) issues triaged: every Must-have bug fixed or explicitly deferred by Matthew.
- Cost check: $0 projected (G5).

## 4. Implementation details

### File structure
```
frontend/src/features/map/CelebrationBurst.tsx
frontend/src/assets/sparkle.png
frontend/src/features/map/useCourseActions.ts          (trigger + performance marks)
frontend/src/features/map/CelebrationBurst.test.tsx
frontend/e2e/fullstack/j1-first-time-setup.spec.ts
frontend/e2e/fullstack/j2-planning-enlistment.spec.ts
frontend/e2e/fullstack/j3-end-of-term.spec.ts
frontend/e2e/fullstack/j4-elective.spec.ts
frontend/e2e/fullstack/j5-sharing.spec.ts
frontend/e2e/fullstack/perf.spec.ts
frontend/playwright.fullstack.config.ts
backend/src/test/kotlin/ph/anevaino/e2e/FakeGoogleIdTokenVerifier.kt
backend/src/test/kotlin/ph/anevaino/e2e/E2eTestApplication.kt
backend/src/main/kotlin/ph/anevaino/common/ProfileGuard.kt
backend/src/test/kotlin/ph/anevaino/security/OwnershipMatrixTest.kt
backend/src/test/kotlin/ph/anevaino/security/EndpointOwnership.kt
scripts/audit-markers.mjs
documentation/LAUNCH-CHECKLIST.md
.github/workflows/ci.yml                               (fullstack-e2e, audit-markers jobs)
```

### Testing
- `CelebrationBurst.test.tsx`: renders on FINISHED only; not on PASSED; renders nothing with reduced motion; unmounts after animation end.
- The gates in §3 are themselves the tests.

## 5. Considerations

- **Rules:** R-2.29, R-4.1, R-4.5, R-4.13, R-5.1, R-5.3, R-6.3, R-6.6.
- **Not added:** PRD §8 tracks "Report parsing issue" feedback volume, but no FR or feature defines that button — not built (R-6.1); flagged as gap G2 in RFCS.md.
- **Should-haves and launch:** J5 is a launch gate only if RFC-017 shipped; if Matthew defers F41/F42, remove `j5` from the gate in the same PR and note it.

## 6. Acceptance criteria

- **AC-018.1 [F38]** Finishing a course (state → FINISHED) plays a ≤ 1.2 s pixel sparkle at the node or list row; PASSED does not trigger it; with `prefers-reduced-motion: reduce` nothing animates; the effect is `aria-hidden`.
- **AC-018.2** Full-stack Playwright specs for J1–J5 pass against the backend in the `e2e` profile (fake verifier only in `src/test`) with a real CCS upload and no API mocking, in CI job `fullstack-e2e`.
- **AC-018.3** `ProfileGuard` prevents starting with both `prod` and `e2e` profiles.
- **AC-018.4 [F44]** `OwnershipMatrixTest` fails for any endpoint missing from `EndpointOwnership.kt` and passes a cross-user case for every owned endpoint.
- **AC-018.5 [F29]** `perf.spec.ts` shows local state-change feedback < 300 ms, action saves p95 < 1 s and map load < 1 s on a warm server with 80 courses, and no > 100 ms long task during a 3 s pan.
- **AC-018.6** `audit-markers.mjs` passes in CI: no TODO/FIXME/placeholder/skipped tests in source; every `OPEN-QUESTION` marker matches an open question in RFCS.md; RFC-001's placeholder route component no longer exists.
- **AC-018.7** `documentation/LAUNCH-CHECKLIST.md` exists with the §3.5 items, all checked and dated in the launch PR.
- **AC-018.8** Every Must-have feature (F1–F5, F7–F19, F21–F26, F28–F37, F39, F40, F43, F44) has a passing test for each FEATURES.md acceptance criterion (R-4.1), confirmed by a traceability table appended to the launch PR.
- **AC-018.9** Every file listed in §4 "File structure" exists, including `sparkle.png`, `playwright.fullstack.config.ts`, `E2eTestApplication.kt`, `EndpointOwnership.kt` and the two new CI jobs.
