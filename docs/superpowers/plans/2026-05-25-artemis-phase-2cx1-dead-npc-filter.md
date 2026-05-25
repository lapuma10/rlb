# Phase 2C.x.1 — Artemis `matches()` dead-NPC filter

**Status:** approved 2026-05-25 — implementation slice.
**Sibling docs:**
- Run 02 (motivating evidence): `docs/learnings/2026-05-24-artemis-tier1-run-02.md`
- Phase 2C.x (parent slice): `docs/superpowers/plans/2026-05-24-artemis-phase-2cx-loop-tolerance.md`
- Phase 2D operator protocol: `docs/superpowers/plans/2026-05-24-artemis-phase-2d-operator-session-protocol.md`

---

## 1. Problem

Tier 1 Run 02 verified that Phase 2C.x's inner `Selector("attack-or-retry")`
keeps the cow-killer pilot alive across transient click failures (F1 +
F3 confirmed fixed). But Run 02 surfaced a new sub-case — **F2b**:

After a cow is killed, it remains in `WorldView.npcs()` for ~5–10 s while
the death animation plays. During that window:

- `npc.getName()` is still `"Cow"` (passes the name filter).
- `npc.getInteracting()` is `null` (combat ended), so the
  `requireUnengaged` filter — which only checks "interacting with
  another `Player`" — passes.
- The NPC's `WorldPoint` is still valid (passes plane + range).
- `RotationPolicy.ClosestWithSlack(2)` picks the dying cow because it's
  still the closest match.

The supplier therefore returns the same dying cow each iteration. The
inner Selector catches the resulting click failures (right-click menu
has no `Attack` on a dying NPC), idles ~1 tick, and the next iteration
picks the same dying cow again. Loop survives, click budget burns.

Concrete Run 02 evidence:
```
10:15:18  npc 2806  attempt 1 — right-click — Attack not in menu
10:15:36  npc 2806  attempt 2 — right-click — Attack not in menu
10:15:38  npc 2806  attempt 3 — right-click — Attack not in menu
```

## 2. Evidence — what Artemis exposes today

Read-layer audit (see Run 02 §Failures observed for the full table):

- `NpcRef` (`NpcRef.java:11–22`) carries: `index`, `id`, `name`,
  `originalLoc`, `healthRatio`, `observedTick`. Does NOT expose
  `isDead` / `animation` / `interacting`.
- `NpcQuery` filter chain in `ArtemisImpl.matches()` (`ArtemisImpl.java:511-548`):
  name, id, location-not-null, plane, range, `excludeIndices`,
  `requireUnengaged`. No `isDead` check today.
- `requireUnengaged` only catches NPCs whose `getInteracting()` is
  another `Player` — does NOT catch dying cows.
- RuneLite `Actor.isDead()` (NPC inherits) returns `true` while the
  death animation plays. Canonical engine signal.

## 3. Fix

Single-line filter in `ArtemisImpl.matches()`, placed between the
existing `excludeIndices` check and the existing `requireUnengaged`
check:

```java
if (npc.isDead())
{
    return false;
}
```

**Universal.** Every `Artemis.findNpc(...)` consumer (current and
future) skips dying / death-animation NPCs without script-side state.

## 4. Why a state-based filter, not a cooldown

- `npc.isDead()` is an engine-canonical signal — no inference, no
  timing window calibration.
- Script-side cooldown would need a failure-detection signal the
  script doesn't have today (the inner Selector(click, idle)
  both options Succeed at the engine level — click Succeeds blindly
  on dispatcher idle; idle always Succeeds; the supplier doesn't see
  "click failed").
- No new fields on `NpcRef`, no new builder on `NpcQuery`, no
  expanded NpcView framework. Stays tight per the explicit scope
  constraint.

## 5. Universal-by-default rationale

Filtering dead NPCs at the read layer is correct for v1: no normal
script wants to select an NPC mid-death-animation as an action
target. If a future script ever needs to observe dying NPCs (e.g.,
"loot the corpse before it despawns" — note: ground items come from
`findItem`, not the corpse itself), that should be an explicit
opt-in query mode, not the default behavior. v1 ships the
default-skip; refine if/when a use case appears.

## 6. Scope

**Modified:**
- `runelite-client/src/main/java/net/runelite/client/sequence/artemis/ArtemisImpl.java`

**Added:**
- `runelite-client/src/test/java/net/runelite/client/sequence/artemis/ArtemisImplNpcFindTest.java`
  (parallels the existing `ArtemisImplObjectFindTest.java` pattern;
  no `findNpc` test class exists today)
- `docs/superpowers/plans/2026-05-25-artemis-phase-2cx1-dead-npc-filter.md`
  (this doc)

**Optional:**
- One-line cross-reference from
  `docs/learnings/2026-05-24-artemis-tier1-run-02.md` pointing to this
  plan.

**NOT touched (per scope constraints):**
- `CowKillerScript.java` — filter is below the script API surface
- `CowKillerScriptPlanShapeTest.java` — script behaviour unchanged
- `NpcRef`
- `NpcQuery` interface
- `Artemis` interface
- `PixelResolver`, `HumanizedInputDispatcher`, `ActionRequest`,
  `liveTracked` NPC mode
- Loot / drop / inventory-full work
- Recorder UI
- Phase 1B grep gate allow-list

## 7. Tests

`ArtemisImplNpcFindTest` mock pattern follows `ArtemisImplObjectFindTest`:
mock `Client`, `ClientThread` (`isClientThread → true` so reads run
inline), `LocalPlayer` at a known `WorldPoint`, `WorldView.npcs()`
returning a list of mock `NPC` instances. Each mock NPC stubs
`getName`, `getId`, `getIndex`, `getWorldLocation`, `getHealthRatio`,
`getInteracting`, **`isDead`**.

Cases:

1. **`findNpc_skipsClosestDeadCow_returnsNextLiveCow`** — two cows: a
   closer dead cow (`isDead=true`, distance 1) and a farther alive cow
   (`isDead=false`, distance 4). `findNpc(byName("Cow"))` returns
   the alive cow.
2. **`findNpc_allMatchingNpcsDead_returnsEmpty`** — only dead cows.
   `findNpc(byName("Cow"))` returns `Optional.empty()`.
3. **`findNpc_aliveCandidate_unaffected`** — single alive cow,
   `isDead=false`. Returned as before. Regression guard: the filter
   excludes ONLY dead, not all.

## 8. Risks (small, accepted)

| Risk | Severity | Mitigation |
|---|---|---|
| One-tick race between HP-zero and `isDead` flip — one wasted click in the gap | Very low | Inner Selector(click, idle) absorbs the single wasted click (Phase 2C.x). |
| Some NPC type has no death animation — `isDead()` never trips for that NPC | Very low | Cows have a death animation. Future scripts targeting non-cow NPCs discover this via testing. |
| Filtering at read layer hides a dying NPC from a hypothetical "examine corpse" script | Very low | No such script exists; can override via explicit opt-in query mode later. |

## 9. Outcome / next

After this slice lands:

1. Rebuild `:client:shadowJar` (operator-gated per
   `feedback_dont_build_client_unprompted`).
2. Kill stale RuneLite + relaunch from new jar (per Phase 2D
   rebuild-restart pattern).
3. Run Tier 1 Run 03 per Phase 2D operator protocol.
4. Fill `docs/learnings/2026-05-25-artemis-tier1-run-03.md`.

If Run 03 shows the "constant right-clicking after kill" pattern is
gone and Tier 1 §7 criteria pass, mark Tier 1 PASSED. If F2 remains
operationally noisy in a way the dead-NPC filter doesn't address
(e.g., moving-cow stale-position misses), evaluate Phase 3
`liveTracked` priority.
