# PRD Review — Anevaino: DLSU Flowchart Quest Map

**Reviewed:** `PRD.md` v2 (Draft v2 — verified, 2026-09-16) → produced `PRD.md` v3 (Draft v3 — parser architecture, CGPA formula and minor-program scope finalized, 2026-09-16)
**Product type:** Web app. Same checks applied/skipped as v1/v2 — no change to that classification.
**Not under version control:** v2 was copied to `PRD.v2.md` before this round overwrote `PRD.md` (v1 is also preserved, in `PRD.v1.md`, from the previous round).

This is round 2 of the verification pass. Round 1's full findings are preserved below for traceability, since downstream steps (FEATURES.md, RFCs) need the complete decision history, not just the latest diff.

## Round 2 — this pass

### Grounding (Step 0)

No new source document this round — you made three explicit decisions on open questions round 1 had left unresolved. I treated each as a real product decision with downstream consequences to work through, not just a checkbox to flip:

- **"Every college has the same checklist table, drop the per-college parser."** I applied this, since it's your call to make and you're the one who's seen the actual flowchart landscape. But I want to be direct about what it costs: round 1's review flagged this exact assumption as verified against **one sample only**, and recommended confirming against 2–3 more colleges before committing the architecture to it. Dropping the per-college strategy pattern means there's no longer an isolation boundary — if one college's checklist table turns out to differ, a single shared parser is more likely to either mis-parse it silently or break for everyone, instead of just failing gracefully for that one college. I didn't block on this — you get final say on your own project's risk tolerance — but I added a concrete mitigation instead of accepting the risk silently: a parse-confidence check (FR-2.11) that flags a suspicious parse for manual review rather than presenting it as trustworthy. That's the difference between "assume it'll work" and "assume it'll work, but verify automatically and say so when it doesn't."
- **"Failed grade brings CGPA down."** Applied directly (FR-5.4) — this matches how DLSU itself computes CGPA. I also had to resolve an interaction the instruction didn't spell out: what happens to that 0.0 once the course is retaken? I kept the existing "latest attempt wins" rule from v2 and made the interaction explicit with a worked example, since "failed brings it down" and "latest attempt counts" needed to be reconciled, not just individually true.
- **"No minor programs, outside MVP."** Applied by removing the middle-ground v2 had proposed (display Minor Program slots but don't let students interact with them) in favor of not parsing them at all. This is strictly simpler to build, and matches "outside MVP" more literally than v2's compromise did.

### Findings this round

| # | Finding | Impact |
|---|---------|--------|
| H5 | Removing the per-college parser strategy removes the isolation boundary round 1 relied on to contain a bad parse to one college. Without a replacement safeguard, a layout divergence in any single college's PDF could silently corrupt data for that college's users rather than failing loudly. Added a parse-confidence check (FR-2.11) and a review-screen warning banner (FR-3.5) as the replacement safeguard. | **High** |
| M6 | "Failed brings CGPA down" and "latest attempt supersedes" (kept from v2) needed to be reconciled explicitly, or a retaken-and-passed course could ambiguously read as either "still dragged down by the old 0.0" or "fully cleared." Resolved with a worked example in FR-5.4. | **Medium** |
| M7 | Confirmed Withdrawn stays excluded from CGPA even though Failed now counts — these are easy to conflate ("both are bad outcomes") but DLSU treats them differently (a W isn't a grade point). Called out explicitly in FR-4's state definitions and FR-5.4 so the distinction isn't lost on a future re-read. | **Medium** |
| L3 | Minor Program handling is now simpler to build (skip entirely vs. display-but-inert), which also simplifies the data model — dropped the `OPTIONAL` slot type that v2 had introduced for this. | **Low** |
| L4 | Data model gained `Curriculum.parseConfidence` and lost the college-as-parser-key framing (`programLabel` is now purely descriptive) — mechanical consequence of decisions 9 and 11, not a new judgment call. | **Low** |

### Recommendations

1. **Treat Weeks 10–11 (§9) as the real test of decision 9**, not a formality. That's the first point in the timeline where the universal parser meets PDFs it wasn't built against. If it holds up, great — the architecture was the right call. If it doesn't, the PRD's new "still open" question (below) is there so that moment has a pre-agreed decision process instead of a scramble.
2. **Keep the parse-confidence check's thresholds loose at first** (FR-2.11) — a check that's too strict will flag every normal parse as low-confidence and train you to ignore the warning; a check that's too loose defeats the point. Tune it against real fixtures once you have them (Weeks 1–2, 10–11), not from first principles now.
3. No action needed on the CGPA or Minor Program decisions beyond what's now written into FR-5.4 and FR-6.2 — both were clear, self-contained instructions with only one real ambiguity each, and both are resolved above.

### Quality assessment (cumulative, v1 → v2 → v3)

| Dimension | v1 | v2 | v3 | Why it moved this round |
|---|---|---|---|---|
| Completeness | 6/10 | 8/10 | 8/10 | No new gaps opened or closed at the "missing entirely" level this round — the three open questions closed were already tracked, not discovered. |
| Clarity | 7/10 | 8/10 | 9/10 | The CGPA/retake interaction and the Withdrawn-vs-Failed distinction are now spelled out with a worked example instead of left for a future reader to infer. |
| Feasibility | 6/10 | 8/10 | 7/10 | This is the one score that went *down*. Dropping the per-college parser strategy is a real feasibility trade: it's less code to write, but it concentrates risk instead of isolating it, on the strength of one sample PDF. The confidence check (FR-2.11) is a genuine mitigation, not a formality, but it doesn't fully offset betting the whole parser on an unconfirmed university-wide assumption. |
| User-Focus | 7/10 | 8/10 | 8/10 | The CGPA rule now matches what a DLSU student would actually expect to see on their own transcript-equivalent; no regression, but also not a new user-facing gap closed this round. |

## Round 1 — prior pass (summary, full detail in `PRD.v2.md`'s history)

**Grounding:** read the actual CCS BSCS-NIS flowchart PDF you attached; verified Render's and Neon's current free-tier terms via their own pricing pages.

**High-impact findings:** the parser was designed around diagram arrows when a far more reliable text-based checklist table exists in the same PDF (H1); the 4-state course lifecycle couldn't represent real pass/fail, incomplete, or withdrawn courses (H2); a 4th prerequisite marker `(E)` (exemption) wasn't handled and would have broken the existing validation rule (H3); GPA/CGPA was underspecified and needed a concrete, testable formula (H4).

**Medium/low findings:** parenthetical "extra units" needed explicit handling (M1); an unanticipated "Minor Program" optional-slot mechanic was discovered in the sample (M2, later resolved in round 2 as fully out of scope); the checklist table's two-column layout is a known PDF-extraction trap worth calling out explicitly (M3); hosting free tiers were confirmed rather than left as an unverified assumption (M4); the 5-year-program biome rule was generalized instead of left as a placeholder (M5); pixel-art licensing and branding were closed out with no PRD changes needed (L1, L2).

Round 1 scores: Completeness 6→8, Clarity 7→8, Feasibility 6→8, User-Focus 7→8 (table above shows the full v1→v2→v3 progression).

## Self-check (this round)

- Recounted this round's findings table from the content just written: 1 High, 2 Medium, 2 Low = 5 findings, matching the table above.
- Cross-checked every FR number referenced in this review (FR-2.5, FR-2.8, FR-2.11, FR-3.5, FR-4, FR-5.4, FR-6.2, §9, §10) against the updated `PRD.md` — each exists and matches what's described here.
- Checked for disagreement between `PRD.md`'s tables: §3 Scope (Minor Programs and per-college parsing both listed as confirmed out of scope), FR-6 (Minor slots skipped at parse time), and §6.2's data model (no `OPTIONAL` slot type, `parseConfidence` field present) — all three agree with each other.
- Verified the decision-log numbering in `PRD.md` §10: Round 1 items are numbered 1–8 (unchanged from the prior pass), Round 2 items are numbered 9–11 and don't collide with Round 1's numbering or with the "Still open" list's own 1–2 numbering (which is intentionally a separate, smaller sequence).
