# Nav engine — deferred QC items (tracker)

Originally flagged when `nav-engine-integration` was merged into v21
on 2026-05-17 (see
`2026-05-17-nav-engine-integration-handoff.md`). Bot has been
running in production on v21 for 5+ days, so the engine works in
practice — these are known limitations, not blockers.

State verified against v21 (`9fb83d34e`) on 2026-05-22.

---

## ✅ FIXED on v21 — predicates now consulted by BFS

`WaypointPlanner.java:130-137` adapts the canonical
`PredicateRegistry` from the snapshot into a `TilePredicate` the
BFS kernel can consume. Tile blacklists set by scripts (e.g. the
20s stuck-tile blacklist in InvalidationClassifier) DO now gate
routing.

No further action.

---

## ⚠️ OPEN — PlayerState is stubbed

**File:line:** `runelite-client/.../recorder/nav/v2/planner/WaypointPlanner.java:117`
**What:** `extractPlayerStateOrNull` returns `null`, so every plan
falls through to `stubPlayerState()` (skill=1, isMember=false,
empty inventory).
**User-visible impact:** Every transport row in the transport TSV
with a requirement is filtered out. Bot can route through doors,
stairs, and basic gates only — no agility shortcuts, no fairy
rings, no spirit trees, no teleport items/spells, no member-only
quest gates.
**Why it's OK today:** Current scripts (chicken farm, agility,
cooking, fletching, rooftop, GE) don't depend on requirement-gated
transports. The bot walks through the world fine.
**How to fix:** Implement `extractPlayerStateOrNull` against the
live `Client` — read skill levels, inventory contents,
`isMember()`. Effort: M.
**How to verify after fix:** Plan a route that's only valid if the
bot can use an agility shortcut (e.g. Falador → Burthorpe via the
Coal Truck or similar). Confirm the planner picks the shortcut
when the player meets the requirement.

---

## ⚠️ OPEN — Acceptance harness not wired to the live planner

**File:line:** `runelite-client/.../recorder/nav/v2/qc/NavigationTestHarness.java:53`
**What:** `wirePlannerExecutor(...)` is defined and used by
`PredicateTest`, `TraceQualityTest`, `SameRegionRouteTest` — but no
main-code caller ever invokes it. The acceptance tests fail their
own `Assume` gate and skip.
**User-visible impact:** Zero — these are dev-time regression tests
the bot's runtime doesn't care about. Affects engineers, not the
runtime.
**Why it's OK today:** Engine has been hand-verified in production
on v21. We don't depend on the acceptance harness for confidence.
**How to fix:** Decide whether to (a) bridge canonical types ↔ qc
contracts adapter (~22 mirror files per memory; M effort) or
(b) accept the harness as deferred and re-architect when we next
touch the test infrastructure. Effort: M if (a).
**How to verify after fix:** The three acceptance tests above
should pass without `Assume.assumeTrue(false)`.

---

## ⚠️ OPEN — Lane 5 sparse-waypoint executor path unwired

**Files:**
- `runelite-client/.../recorder/nav/v2/V2Executor.java` (no refs)
- `runelite-client/.../recorder/nav/v2/executor/SidestepResolver.java` (dead code)
- `runelite-client/.../recorder/nav/v2/executor/PathStepCursor.java` (dead code)

**What:** Spec §1 ownership rule for the executor was supposed to
route through `SidestepResolver` + `PathStepCursor`. Both are
implemented and unit-tested in isolation, but `V2Executor.tick()`
never calls them — it falls back to the legacy `CanvasTilePicker`
over the full tile list.
**User-visible impact:** Works in practice because the planner
emits full tile sequences (not sparse waypoints), so the legacy
picker has the data it needs. The fancier sidestep logic intended
for sparse legs is unused.
**Why it's OK today:** Bot walks fine. The optimization is a
"nice to have" for cleaner sparse-waypoint handling.
**How to fix:** Wire `SidestepResolver` + `PathStepCursor` into
`V2Executor.tick()` per the original Lane 5 spec. Will likely
require deciding whether legacy + new paths coexist (gated by
config) or new replaces legacy. Effort: L.
**How to verify after fix:** Existing in-game flows still work
(don't break the chicken farm); log lines from the new picker
appear in `client.log` when the bot is walking.

---

## How to use this tracker

When picking up nav-engine work, read this first. When you fix
one, move it to a "Fixed on master at <sha>" section and delete it
from "OPEN." When you discover a new deferred item, add it here
with the same shape (file:line, what, impact, why-ok-today, how to
fix, how to verify).

Related docs:
- `2026-05-17-nav-engine-integration-handoff.md` — original handoff
- `2026-05-16-observation-aware-navigation-engine-design.md` — spec
- `2026-05-16-nav-engine-master.md` — master plan (5 lanes)
