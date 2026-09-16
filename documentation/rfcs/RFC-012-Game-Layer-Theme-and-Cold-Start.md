# RFC-012 — Biomes, Progress Bar with Hero, Light/Dark Theme, Cold-Start Screen

| | |
|---|---|
| **Status** | Draft |
| **Complexity** | Medium |
| **Phase** | 5 — Fixtures & game (wk 10–11) |
| **Features** | F35, F36 (UI), F37 (UI), F40 (UI) |
| **PRD** | FR-8.2, FR-8.3, FR-8.4, NFR Cold starts, NFR Accessibility, §10 decision 7 |
| **Predecessors:** | RFC-011 |
| **Successors:** | RFC-014, RFC-017 |

Sections omitted: *backend*, *schema*, *API* (uses RFC-001 health and RFC-010 settings).

## 1. Summary

The game presentation and app-wide polish: one biome background per year band (always ending in lava + evil castle), the progress bar with the static pixel-art hero at the fill point, the System/Light/Dark theme with a persisted override and AA-verified colour tokens, and the friendly wake-up screen that hides Render's cold start.

## 2. Blocking input

Self-drawn pixel art (PRD §10 decision 7) must exist before this RFC can merge — R-6.3 forbids placeholder art in merged code:
`grassland.png`, `ice.png`, `desert.png`, `cloudy.png`, `lava.png` (seamless horizontal tiles, 64×64 or 128×128, transparent-free), `castle.png`, `evil-castle.png` (≤ 128×128), `hero.png` (32×32, transparent). PNG, ≤ 30 KB each. Matthew provides these; the implementer does not source art.

## 3. Technical approach

### 3.1 Biome sequence (F35, Q5, Q11)
```ts
// frontend/src/domain/biomes.ts  (pure)
export type Biome = 'GRASSLAND' | 'ICE' | 'DESERT' | 'CLOUDY' | 'LAVA';
export function biomeForYear(year: number, totalYears: number): Biome {
  if (year === totalYears) return 'LAVA';                // final year always lava (+ evil castle), even when totalYears = 1 (Q11)
  if (year === 1) return 'GRASSLAND';                    // (+ castle)
  if (year === 2) return 'ICE';
  if (year === 3) return 'DESERT';
  return 'CLOUDY';                                       // every year after 3 except the last
}
```
`totalYears` = max `year` over courses. Landmarks: castle sprite at the left edge of Year 1's band; evil castle at the right edge of the final band. Q5 (RULES default) matches this function for 2- and 3-year programmes (grass → lava; grass → ice → lava). Q11 (new): a 1-year curriculum is lava only — PRD rules conflict there ("Year 1 grassland" vs "final year always lava"); "final year" is given precedence; flagged. Marked `OPEN-QUESTION(Q5)` / `OPEN-QUESTION(Q11)`.

Rendering: `BiomeBackground` implements `renderYearBackground` from RFC-008; each band is a `div` with `background-image` (tile, `image-rendering: pixelated`, `background-repeat: repeat-x`), sized to the band — CSS layers, not per-node images (R-2.30). A theme-aware translucent overlay (`--biome-overlay`) keeps node text contrast ≥ 4.5:1 over art in both themes. No animated effects (so nothing to disable for `prefers-reduced-motion`; RFC-018's celebration handles motion).

### 3.2 Progress bar (F36)
`ProgressBar` in `AppHeader`'s `progressSlot`: track with fill width = `fillBasisPoints / 100` %, hero sprite absolutely positioned with its centre at the fill point (clamped so it stays inside the track), text "88 / 185 units" where earned = `progress.earnedUnits` and required = `progress.requiredUnits` (both include extra units). `role="progressbar"`, `aria-valuemin=0`, `aria-valuemax=100`, `aria-valuenow` = rounded percent, `aria-valuetext="88 of 185 units completed"`. The hero is decorative (`alt=""`). Updates with the optimistic map cache (RFC-011), so finishing/passing a course moves it immediately.

### 3.3 Theme (F37)
- Tokens in `app/theme.css` as CSS custom properties under `:root[data-theme="light"]` and `:root[data-theme="dark"]`: `--bg`, `--surface`, `--text`, `--text-muted`, `--border`, `--focus`, `--state-*` (7), `--elig-*` (3), `--warning-bg`, `--warning-border`, `--biome-overlay`. Tailwind v4 `@theme` maps them to utilities.
- `ThemeProvider`: effective theme = `me.theme` when signed in, else the last choice cached in `localStorage` (`anevaino.theme`, try/catch), else system. `SYSTEM` follows `matchMedia('(prefers-color-scheme: dark)')` with a change listener. Sets `document.documentElement.dataset.theme`.
- `main.tsx` (a module script, not inline — CSP R-2.23) applies the cached theme before first render to avoid a flash.
- `ThemeToggle` in the header: a menu button with three `menuitemradio` options System / Light / Dark; choosing one updates the attribute immediately, caches it, and (when signed in) `PATCH /api/me/settings { theme }`; the same control appears on `/settings`.
- `contrast.test.ts` computes WCAG contrast from the token values in both themes: text pairs ≥ 4.5:1, UI graphics (borders, state icons, focus ring) ≥ 3:1.

### 3.4 Cold-start screen (F40, R-2.32)
```
WakeGate (wraps the router):
  t0 = now; attempt = 0
  loop: GET /api/health with 5 s AbortController timeout
        200 → render children
        else → show WakeScreen after the first failure; wait min(1000 * 2^attempt, 8000) ms; attempt++
        if now - t0 > 60 s → WakeScreen error state
WakeScreen: hero sprite + "Waking up the server… This can take up to a minute on the first visit."
            (role="status", aria-live="polite")
            error state: "The server didn't wake up. Please try again in a moment." + "Try again" button (restarts loop)
api/client.ts: a 502/503/504 response that is NOT application/problem+json triggers wakeGate.recheck()
```
The first successful health check is never shown as a screen if it returns within 800 ms (no flash).

## 4. Implementation details

### File structure
```
frontend/src/assets/biomes/grassland.png
frontend/src/assets/biomes/ice.png
frontend/src/assets/biomes/desert.png
frontend/src/assets/biomes/cloudy.png
frontend/src/assets/biomes/lava.png
frontend/src/assets/biomes/castle.png
frontend/src/assets/biomes/evil-castle.png
frontend/src/assets/hero.png
frontend/src/domain/biomes.ts
frontend/src/domain/biomes.test.ts
frontend/src/features/map/BiomeBackground.tsx
frontend/src/components/ProgressBar.tsx
frontend/src/components/ProgressBar.test.tsx
frontend/src/app/theme.css
frontend/src/app/ThemeProvider.tsx
frontend/src/app/ThemeToggle.tsx
frontend/src/app/contrast.test.ts
frontend/src/app/ThemeProvider.test.tsx
frontend/src/app/WakeGate.tsx
frontend/src/app/WakeScreen.tsx
frontend/src/app/WakeGate.test.tsx
frontend/src/features/settings/ThemeSetting.tsx
frontend/e2e/theme-and-wake.spec.ts
```

### Testing
- `biomes.test.ts` (R-4.4 literal cases): 4 years → GRASSLAND, ICE, DESERT, LAVA; 5 years → GRASSLAND, ICE, DESERT, CLOUDY, LAVA; 6 years → …, CLOUDY, CLOUDY, LAVA; 3 years → GRASSLAND, ICE, LAVA; 2 → GRASSLAND, LAVA; 1 → LAVA.
- `ProgressBar.test.tsx`: 0%, 50%, 100% hero positions (clamped); aria attributes; text uses units incl. extra.
- `ThemeProvider.test.tsx`: SYSTEM follows mocked matchMedia and its change event; LIGHT/DARK override; toggle PATCHes when signed in and not when signed out; storage throwing doesn't break.
- `contrast.test.ts`: all token pairs pass thresholds in both themes.
- `WakeGate.test.tsx` (fake timers): immediate 200 → no screen; failures → screen with backoff 1/2/4/8/8 s; > 60 s → error state; "Try again" restarts; client 503 non-problem → recheck.
- `theme-and-wake.spec.ts`: dark mode via emulated `prefers-color-scheme`; manual toggle persists across reload; health route delayed 5 s shows the wake screen then the app; axe clean on `/map` in both themes.

## 5. Considerations

- **Rules:** R-2.26 (no grades in storage — only the theme is cached), R-2.30, R-2.32, R-4.10, R-4.12, R-4.13, R-6.1, R-6.3.
- **Phase note:** RULES puts F40 in phase 5; the backend health endpoint already exists from RFC-001, so nothing earlier depends on this UI.
- **Performance:** background tiles are cached static assets; no re-render on state change (bands depend on the structure key only).

## 6. Acceptance criteria

- **AC-012.1 [F35]** `biomeForYear` returns the §4 test sequences for 1–6 years; Year 1 shows the castle and the final year shows the evil castle; `OPEN-QUESTION(Q5)`/`(Q11)` markers are present.
- **AC-012.2 [F35]** Each year band on `/map` renders its biome tile as a CSS background layer with a theme overlay that keeps node text ≥ 4.5:1.
- **AC-012.3 [F36]** The header progress bar fills to `fillBasisPoints`, shows "earned / required units" including extra units, places the static hero at the fill point (clamped), exposes `role="progressbar"` with value and value text, and moves immediately when a course is finished or passed.
- **AC-012.4 [F37]** Theme follows the system by default, a System/Light/Dark control in the header and on `/settings` overrides it immediately, persists to `User.theme` when signed in, and survives reload without a flash.
- **AC-012.5 [F37]** All light and dark token pairs meet 4.5:1 (text) and 3:1 (UI graphics) in `contrast.test.ts`, and axe reports no serious/critical contrast issues on `/map` in both themes.
- **AC-012.6 [F40]** While `/api/health` fails or hangs, a friendly `role="status"` wake-up message with the hero is shown and retries with 1→8 s backoff for up to 60 s, then an error with "Try again"; a fast health response shows no screen.
- **AC-012.7** The eight art assets in §2 exist as self-drawn PNGs (no placeholders) within the size limits.
- **AC-012.8** Every file listed in §4 "File structure" exists, including `BiomeBackground.tsx`, `theme.css`, `ThemeToggle.tsx`, `WakeScreen.tsx`, `ThemeSetting.tsx`, `ThemeProvider.test.tsx`, `WakeGate.test.tsx` and `theme-and-wake.spec.ts`.
