# RFC-008 — Left-to-Right Course Graph Component

| | |
|---|---|
| **Status** | Draft |
| **Complexity** | High |
| **Phase** | 3–4 (RULES places F34 in phase 4, but F16's review screen in phase 3 needs a graph preview — see RFCS.md C7) |
| **Features** | F34 |
| **PRD** | FR-8.1, FR-3.1 (preview), §6.1, NFR Performance, NFR Responsiveness |
| **Predecessors:** | RFC-001 |
| **Successors:** | RFC-009, RFC-011 |

Sections omitted: *API*, *schema*, *auth*, *backend* (frontend-only).

## 1. Summary

A reusable React Flow component that lays out courses in fixed term columns grouped by year, draws typed prerequisite edges that are distinguishable without colour, and supports pan, zoom, fit-to-screen and touch. It knows nothing about the API or rules: callers pass a view model and a node renderer. RFC-009 uses it as a read-only preview of a draft; RFC-011 uses it as the interactive quest map; RFC-012 paints biomes into the year bands it exposes.

## 2. Interfaces

```ts
// frontend/src/components/graph/types.ts
export interface GraphCourse {
  id: string; code: string; title: string; units: number | null; extraUnits: number;
  year: number | null; term: number | null;           // null allowed for unsaved drafts
  slotType: 'NONE' | 'MANDATORY_ELECTIVE'; slotLabel: string | null;
}
export interface GraphEdge { courseId: string; requiresCourseId: string; type: 'HARD' | 'SOFT' | 'COREQ' }
export interface YearBand { year: number | null; x: number; width: number; label: string }   // year null = "Unplaced"

export interface CourseGraphProps<T extends GraphCourse> {
  courses: T[];
  edges: GraphEdge[];
  NodeComponent: React.ComponentType<{ course: T }>;   // must be memoized by caller
  onCourseClick?: (course: T) => void;
  renderYearBackground?: (band: YearBand) => React.ReactNode;  // RFC-012 hook
  ariaLabel: string;
  interactive?: boolean;                               // false = preview (no click handlers)
}
```

## 3. Layout algorithm

```
column(c) = c.year == null || c.term == null ? UNPLACED : (c.year - 1) * 3 + (c.term - 1)
columns   = sorted distinct column values, UNPLACED last; x(col) = index * (NODE_W + COL_GAP)
          (empty terms inside a year still get a column so term positions line up across years)

y ordering: run @dagrejs/dagre on the graph (rankdir LR, nodesep 24, ranksep 80) using only
            edges whose source column < target column; take dagre's y for each node.
snap:       x := x(column(c)) regardless of dagre's x   // ranks fixed to term order (F34 tech note)
resolve overlaps inside each column: sort by dagre y, then place top→bottom with min gap NODE_H + 16
year bands: for each year, x from its first column to its last column + NODE_W, label "Year N";
            UNPLACED band label "Unplaced"
```
- `layoutGraph()` is a pure function in `components/graph/layout.ts`, memoized on a **structure key** = sorted ids + year/term + edges; it never re-runs on state or eligibility changes (R-2.30).
- Edges between courses in the same column (typically co-requisites) render as `smoothstep` edges that leave from the node's bottom and enter the other's top.
- **ELK fallback rule (RULES §1.1):** if Matthew judges the CCS map unreadable with dagre ordering, replace dagre with `elkjs` 0.12.0 using layered partitioning (`elk.partitioning.activate=true`, partition = column). Uninstall dagre in the same PR — never both.

## 4. Rendering

- React Flow with `nodesDraggable={false}`, `nodesConnectable={false}`, `elementsSelectable={interactive}`, `onlyRenderVisibleElements`, `minZoom 0.2`, `maxZoom 2`, `fitView` on first render and whenever the structure key changes.
- `<Controls>` (zoom in/out, fit view) with accessible labels "Zoom in", "Zoom out", "Fit map to screen". Touch pinch/drag are React Flow defaults (enabled).
- Edge styles (never colour alone, R-4.9):

| Type | Stroke | Marker | Legend text |
|---|---|---|---|
| HARD | solid 2px | closed arrow | "Required (hard prerequisite)" |
| SOFT | dashed `6 4` 2px | open arrow | "Recommended (soft prerequisite)" |
| COREQ | dotted `2 3` 2px | none, small "⇄" label at midpoint | "Take together (co-requisite)" |

- `<GraphLegend>` renders the three rows with inline SVG line samples.
- Column headers "Term 1/2/3" and year band labels are rendered as a non-interactive background layer (`<ViewportPortal>`) so they pan and zoom with the graph.
- The canvas container has `role="region"` and `aria-label={ariaLabel}`; the accessible action path is the list view (RFC-014), so the graph is pointer-first (R-4.11).

## 5. Implementation details

### File structure
```
frontend/src/components/graph/types.ts
frontend/src/components/graph/layout.ts
frontend/src/components/graph/structureKey.ts
frontend/src/components/graph/CourseGraph.tsx
frontend/src/components/graph/GraphBackground.tsx
frontend/src/components/graph/edges.tsx
frontend/src/components/graph/GraphLegend.tsx
frontend/src/components/graph/layout.test.ts
frontend/src/components/graph/CourseGraph.test.tsx
frontend/src/components/graph/__fixtures__/ccsGraph.ts
frontend/e2e/graph.spec.ts
```
`__fixtures__/ccsGraph.ts` is a hand-copied ~80-node view model derived from the CCS `expected.json` for tests (test data, not app data — allowed by R-6.3).

`CourseGraph` is lazy-loaded only by routes that need it, keeping React Flow out of the landing bundle (R-2.33). `@xyflow/react/dist/style.css` is imported inside the lazy chunk.

### Testing
- `layout.test.ts`: all courses with the same (year, term) share x; column x strictly increases with (year, term); an empty term keeps its column; null year/term go to the last "Unplaced" column; no two nodes in a column overlap; structure key unchanged when only non-structural fields change; layout of the 80-node fixture runs < 50 ms (Vitest timing, generous for CI).
- `CourseGraph.test.tsx` (jsdom): renders one custom node per course; `onCourseClick` fires with the course when interactive and not when `interactive=false`; legend has three entries; `renderYearBackground` called once per year band.
- `graph.spec.ts` (Playwright, a test-only harness page served in E2E mode by Vite `mode === 'e2e'`, excluded from production builds): 80-node fixture renders; "Fit map to screen" makes every node visible; mouse wheel zoom and drag pan change the viewport transform; mobile viewport 360 px with touch emulation pans; dashed/dotted edge `stroke-dasharray` attributes are present per type.

## 6. Considerations

- **Rules:** R-1.1 (only the pinned dagre + React Flow), R-2.29, R-2.30, R-2.33, R-4.9, R-4.11, R-4.14–R-4.16.
- **Performance:** ~80 nodes and ~120 edges; memoized nodes + visible-only rendering keep interaction smooth; measure with Playwright trace in RFC-018.
- **Edge cases:** prerequisite pointing to a later term (bad data) — drawn backwards, still visible; very long titles — node shows code + truncated title with full title in `title` attribute; 5-year programme → 15 columns.

## 7. Acceptance criteria

- **AC-008.1 [F34]** Courses are placed in columns ordered by (year, term) with every course of the same term sharing an x position, empty terms keeping their column, and draft courses with null year/term in a final "Unplaced" column — all proven by `layout.test.ts`.
- **AC-008.2 [F34]** Year bands with labels "Year N" (and "Unplaced" when needed) and term headers "Term 1–3" are rendered and move with pan/zoom; `renderYearBackground` receives every band.
- **AC-008.3 [F34]** Edges run from prerequisite to course and HARD/SOFT/COREQ differ by stroke pattern and marker as in §4, with a three-entry legend.
- **AC-008.4 [F34]** Pan, zoom (0.2–2×) and "Fit map to screen" work with mouse and with touch at a 360 px viewport (Playwright).
- **AC-008.5 [F34]** Layout recomputes only when the structure key changes, not on state changes; nodes are non-draggable and the NodeComponent is rendered through memoization.
- **AC-008.6 [F34]** The 80-node fixture lays out in < 50 ms in unit tests and renders fully in the E2E harness.
- **AC-008.7** The component has no API or rules imports, accepts `interactive=false` for previews, and is lazy-loaded (not in the landing bundle).
- **AC-008.8** dagre (`@dagrejs/dagre` 3.1.1) is the only layout dependency unless the §3 ELK fallback rule is invoked, in which case dagre is removed in the same PR.
- **AC-008.9** The E2E harness page exists only in `e2e` mode and is absent from the production build (checked in CI).
- **AC-008.10** Every file listed in §5 "File structure" exists, including `structureKey.ts`, `GraphBackground.tsx`, `edges.tsx`, `__fixtures__/ccsGraph.ts`, `CourseGraph.test.tsx` and `graph.spec.ts`.
