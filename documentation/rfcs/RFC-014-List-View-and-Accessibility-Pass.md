# RFC-014 — Course List View and Accessibility / Responsive Pass

| | |
|---|---|
| **Status** | Draft |
| **Complexity** | Medium |
| **Phase** | 6 — Share, privacy, a11y (wk 12) |
| **Features** | F39, F28 (grayscale/contrast verification), F34 (mobile default view) |
| **PRD** | NFR Accessibility, NFR Responsiveness, FR-4.1 |
| **Predecessors:** | RFC-011, RFC-012 |
| **Successors:** | RFC-018 |

Sections omitted: *backend*, *schema*, *API*.

## 1. Summary

Adds the list/table view that is the keyboard and screen-reader path for every course action (R-4.11) and the default view on phones (R-4.15), then runs the app-wide WCAG 2.1 AA pass: axe gating in CI for the key pages, focus management, touch-target and no-horizontal-scroll checks, and a grayscale distinguishability check for the 7 states × 3 eligibilities.

## 2. Technical approach

### 2.1 View switch
`/map` gets a two-tab control (`role="tablist"`) "Map" / "List". Default: List below Tailwind `md` (768 px), Map otherwise; the choice is remembered per browser in `localStorage` key `anevaino.mapView` (try/catch). Both views read the same `['map']` query and the same `useCourseActions`, so behaviour is identical.

### 2.2 CourseListView (F39)
- A semantic `<table>` per year (`<caption>` "Year 1"), rows grouped by term with a row-group header "Term 1" (`<th scope="rowgroup">`).
- Columns: Code (`<th scope="row">`), Title, Units, State (icon + text from `stateVisuals`), Eligibility (badge + reason text, e.g. "Locked — requires CCPROG1"), Actions.
- Actions cell: RFC-011's `CourseActionButtons` rendered as an "Actions for CCPROG2" menu button (`aria-haspopup="menu"`) whose items are the state's actions, plus "Details" opening `CourseDetailPanel` (attempt history, exemption badge). All dialogs are RFC-011's.
- On phones (< md) rows render as stacked cards with the same content order; table semantics are preserved by changing CSS `display` on cells, not by replacing the table.
- After an action, focus returns to the row's action button, and a polite live region announces the result, e.g. "CCPROG2 is now in progress. Load 6 of 18 units."; blocked results are announced assertively with the violation text.

### 2.3 App-wide pass
- **Skip link** "Skip to main content" as the first focusable element.
- **Focus:** visible focus ring token (`--focus`, ≥ 3:1) on every interactive element; all RFC-009/011/012 dialogs trap and return focus.
- **Headings/landmarks:** one `<h1>` per page; `header`, `main`, `nav`.
- **Touch targets:** ≥ 44×44 px for buttons, links and inputs (R-4.16).
- **No horizontal page scroll** at 360 px on every route; only the graph canvas and the review table container scroll internally.
- **Reduced motion:** global CSS disables transitions under `prefers-reduced-motion: reduce`.
- **Grayscale check (F28):** `stateVisuals.tsx` exposes, per state and eligibility, `{ iconId, borderPattern, label }`; a unit test asserts all 7 state tuples and all 3 eligibility tuples are pairwise distinct ignoring colour. A Playwright check renders a legend of all 21 combinations with `filter: grayscale(1)` and saves a screenshot artifact for a one-time human review recorded in the PR.

### 2.4 Axe in CI (R-4.10)
`a11y.spec.ts` runs `@axe-core/playwright` (WCAG 2.1 A/AA tags) on `/` (sign-in), `/upload`, `/review` (with a LOW draft so the banner is included), `/map` in List and Map views, and `/settings`, each in light and dark themes, at desktop and 360 px. Any `serious` or `critical` violation fails CI.

## 3. Implementation details

### File structure
```
frontend/src/features/map/MapViewTabs.tsx
frontend/src/features/map/CourseListView.tsx
frontend/src/features/map/CourseListRow.tsx
frontend/src/features/map/useMapViewPreference.ts
frontend/src/features/map/ActionAnnouncer.tsx
frontend/src/components/SkipLink.tsx
frontend/src/app/reducedMotion.css
frontend/src/features/map/CourseListView.test.tsx
frontend/src/features/map/stateVisuals.test.ts
frontend/e2e/a11y.spec.ts
frontend/e2e/keyboard-journey.spec.ts
frontend/e2e/responsive.spec.ts
frontend/e2e/grayscale-legend.spec.ts
.github/workflows/ci.yml   (a11y job)
```
`grayscale-legend.spec.ts` uses RFC-008's E2E-mode harness (not in production builds).

### Testing
- `CourseListView.test.tsx`: grouping and captions; every action in RFC-011 §2.3's per-state table reachable from the menu; focus return; live-region messages.
- `stateVisuals.test.ts`: tuple distinctness.
- `keyboard-journey.spec.ts`: J2 and J3 completed keyboard-only in the List view (Tab/Shift+Tab/Enter/Space/Escape/arrow keys in menus).
- `responsive.spec.ts`: at 360 px each route has `scrollWidth <= clientWidth`; interactive elements' bounding boxes ≥ 44×44; List is default on mobile and Map on desktop; the choice persists.

## 4. Considerations

- **Rules:** R-2.12, R-4.9–R-4.16.
- **Phase:** RULES phase 6 owns F39; RFC-011's E2E specs use the graph, which is fine for pointer flows.
- **Scope guard:** no new actions or data. A missing action is fixed in `CourseActionButtons`, shared by both views.

## 5. Acceptance criteria

- **AC-014.1 [F39]** `/map` has Map/List tabs; the List view shows every course grouped by year and term with code, title, units, state (icon + text), eligibility (badge + reason) and an actions menu.
- **AC-014.2 [F39]** Every course action (start, start together, cancel start, finish, mark passed, fail, withdraw, mark incomplete, resolve incomplete, retake, undo result, choose elective course, log grade/professor) and the details panel are available from the List view via RFC-011's buttons and dialogs.
- **AC-014.3 [F39]** J2 and J3 can be completed with the keyboard alone (`keyboard-journey.spec.ts`); focus returns to the originating control after each dialog; results are announced in live regions.
- **AC-014.4 [F39, F34]** Below 768 px the List view is the default and the graph stays reachable via its tab with touch pan/zoom; the choice persists per browser.
- **AC-014.5 [F28]** State and eligibility visual tuples are pairwise distinct without colour (`stateVisuals.test.ts`), and the grayscale legend screenshot is reviewed and recorded in the PR.
- **AC-014.6** `a11y.spec.ts` runs axe on sign-in, upload, review (LOW), map list, map graph and settings in both themes at desktop and 360 px, and CI fails on any serious/critical violation.
- **AC-014.7** A skip link, one `<h1>` per page, landmarks, visible focus rings, ≥ 44×44 px touch targets, no horizontal page scroll at 360 px, and global reduced-motion handling are in place (`responsive.spec.ts`).
- **AC-014.8** Every file listed in §3 "File structure" exists, including `MapViewTabs.tsx`, `CourseListRow.tsx`, `useMapViewPreference.ts`, `ActionAnnouncer.tsx`, `SkipLink.tsx`, `reducedMotion.css`, `grayscale-legend.spec.ts` and the CI a11y job.
