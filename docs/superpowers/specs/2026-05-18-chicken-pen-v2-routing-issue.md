# Chicken Pen V2 Routing — Issue Documentation

**Date:** 2026-05-18
**Status:** In progress (changes committed, not yet validated in-game)
**Branch:** `nav-engine-integration`

This is a working-context document. The bot has a routing issue that
took several conversations to fully understand. This captures the
state of the problem and what's been tried so future sessions don't
have to rebuild context from screenshots.

---

## The user's issue, in their words

> "I had to walk all of the way over here for it to even start walking
> in the direction, before that it just fucking failed."

Chicken farm V3 script tries to navigate from the Lumbridge bank to the
chicken pen. The bot fails at the start (V2 plan returns `NO_ROUTE`).
The user manually walked the player north until reaching a tile near
the cow pen, then the bot started walking again. The bot does **not**
need to enter the cow pen — the route goes past it.

## The route the bot wants to take

- **Start:** Lumbridge bank, plane 2 — `(3209, 3220, p=2)`
- **Stair descent:** NW tower stairs lands at `(3205, 3228, p=0)`
- **Walk north** through Lumbridge town path
- **Past cow pen** — cow pen is to the west of the route, NOT in the path
- **Through chicken pen south gate** at `(3236, 3295, p=0)` ↔ `(3236, 3296, p=0)`
- **Target:** Chicken pen interior — `(3235, 3295, p=0)`

The user confirmed via map markers (red line on world map):
- Castle in green / start
- Chicken pen in blue / target
- Cow pen to the left/west of the route — irrelevant to script
- Red marker stops at the cow pen gate area — where V2 fails to plan

## The technical cause

Skretzo's bundled collision data partitions plane 0 of Lumbridge into
two large connected components:

- **Component A** — Lumbridge castle interior, town, draynor path,
  everything south of y=3263
- **Component B** — cow pen area + chicken pen + everything north of
  y=3263

Confirmed by `ConnectivityComponents` flood-fill against the bundled
snapshot (run within `PenComponentsExploreTest`, now deleted):

```
stairBottom  (3205, 3228, p=0) = component A
draynorPath  (3225, 3219, p=0) = component A
townCenter   (3220, 3245, p=0) = component A
cowPenArea   (3253, 3265, p=0) = component B
gateInside   (3236, 3296, p=0) = component B   ← chicken pen interior
gateOutside  (3236, 3295, p=0) = component B   ← also B, NOT A
penTarget    (3235, 3295, p=0) = component B
```

Boundary at y=3263.5. The wall is the cow pen south fence and its
extension — Skretzo treats the whole fence row as collision.

**The wall is NOT phantom.** It's the actual gate-closed-state collision
encoded by Skretzo. In-game when the gate is open, the live overlay
clears the block, but V2's static-only `ConnectivityComponents` does
not consult the live overlay (by design — see
`docs/superpowers/specs/2026-05-17-collision-aware-dijkstra-design.md` §4).

## What this means for the bot

Even though the bot doesn't intend to interact with the cow pen gate,
**any route from Component A to Component B requires a transport edge
in the planning graph** — because V2's Dijkstra (collision-aware as of
2026-05-17) won't add a walk edge across a component boundary.

Skretzo's `transports.tsv` contains zero entries crossing the y=3263.5
line in this area. So pre-fix, V2 has no graph edge bridging the two
components and returns `NO_ROUTE` for any A→B request.

## Key in-game observations from screenshots (user-provided)

### Cow pen south gate area (image, 2026-05-18)

Player hover at `(3251, 3264, p=0)` (flag `0x0` = fully walkable).
ObjectDebugOverlay shows:
- `Close #1559 W` — open-state gate object id
- `Close #1567 W` — open-state gate object id (other half of same gate)
- `Open #2896 G` — different nearby openable object (north of gate)

When the gate is **open** (which it normally is), the two halves have
object ids 1559 and 1567 (current OSRS impostors). When **closed**,
the object id is different (not directly observed, but Skretzo's
neighbouring entries suggest a small id family).

### Chicken pen south gate (prior screenshots, 2026-05-17)

Open-state object id is `#56231` (per click-inspector). Closed-state
is object `1560`. Tile pair `(3236, 3295)` ↔ `(3236, 3296)`. Already
in `transports-overrides.tsv` from earlier work.

## What's been committed (NOT yet validated)

**Commit `7576971da` — fix(nav-v2): add cow-pen south gate transport**

Added to `runelite-client/src/main/resources/nav/transports/transports-overrides.tsv`:

```
3251 3263 0	3251 3264 0	Open Gate 1559						1
3251 3264 0	3251 3263 0	Open Gate 1559						1
```

Object id `1559` is the open-state id. The transport entry is purely
a **passability hint** to V2's planner — it does NOT mean "the bot
must click this gate to enter the cow pen." The executor at runtime:
- If gate is **open** (current OSRS state): no `Open` verb available
  on objects 1559/1567 → executor sees `verb 'Open' absent for 4 ticks`
  → treats transport as already-complete → walks through. Same path
  V1 TrailWalker uses for the chicken pen gate today.
- If gate is **closed**: `Open` verb available → executor right-clicks,
  selects Open, gate opens, walks through.

**The regression test was flipped from `UNREACHABLE` to `OK` and now
passes**, asserting the cow-pen gate object id appears in the plan
skeleton.

## What I expect this to do (claim is unverified)

1. V2 plans `bank → NW stair (p=2→p=0) → walk north to (3251, 3263) → cow-pen-gate transport → walk to chicken pen south gate at (3236, 3296) → chicken pen gate transport → walk to (3235, 3295)`. Plan returns OK with two transport nodes.
2. V2Executor walks the WALK legs without issue.
3. At the cow-pen gate transport step, executor finds the gate already open → skips the click → advances to the next leg.
4. Bot arrives at the chicken pen.

## Why this might NOT work end-to-end (open risks)

- **V2Executor's "gate already open" handling.** I haven't verified
  V2Executor mirrors V1's `verb 'Open' absent for 4 ticks → treating
  transport as already complete` logic. If V2Executor stalls waiting
  for the `Open` verb that never comes, the bot stalls at the gate.
  Needs in-game testing to confirm.
- **Object id resolution.** The override uses `1559` (open-state).
  If V2Executor's object lookup is strict-by-id and can't find object
  1559 when the gate is closed, the transport step fails. RuneLite's
  impostor resolver *usually* handles state changes by object id
  family, but this is not verified for this specific gate.
- **The chicken pen gate override (object 1560)** sits with both
  endpoints in component B (per the table above). It bridges nothing
  in the component graph. It may still be needed at the executor
  level if the chicken pen gate is ever closed, but it's not the
  bridge the bot route depends on.
- **Standing user instruction:** the cow pen and its gate are
  irrelevant to the script's intent — the bot walks past, not through.
  The override is a planning hint only. Do NOT add behaviour that
  routes the bot inside the cow pen.

## What was tried before this fix (failed paths)

1. **Adding only the chicken pen south gate (object 1560).** Useless
   because both endpoints are in component B. Verified by component
   exploration.
2. **Looking for a "missing gate" at the chicken pen.** Wrong wall —
   the chicken pen gate isn't the partition boundary. The cow pen
   gate is.
3. **Assuming Skretzo's data was wrong (phantom wall).** User
   corrected: the wall is real for the gate-closed state. Skretzo's
   data is conservative-correct, not buggy.

## How to validate (not yet done)

Rebuild + relaunch client. In the chicken-farm-v3 panel:
1. Set nav mode to `V2_STRICT`.
2. Set `stopAfterArrival = true` for clean test (optional).
3. With player at bank, click `Start`.
4. Expected: bot descends NW stairs, walks north through town, passes
   the cow pen (gate currently open in user's world), continues north,
   passes the chicken pen south gate, arrives at chicken pen tile.
5. If bot stalls at the cow pen gate area: V2Executor likely doesn't
   handle "gate already open" gracefully — investigate
   `V2Executor.java` transport-step logic.

## Cross-references

- Spec: `docs/superpowers/specs/2026-05-17-collision-aware-dijkstra-design.md`
- Plan: `docs/superpowers/plans/2026-05-17-collision-aware-dijkstra.md`
- Override file: `runelite-client/src/main/resources/nav/transports/transports-overrides.tsv`
- Regression test: `runelite-client/src/test/java/net/runelite/client/plugins/recorder/nav/v2/transport/LinkGraphDijkstraPenRouteRegressionTest.java`
