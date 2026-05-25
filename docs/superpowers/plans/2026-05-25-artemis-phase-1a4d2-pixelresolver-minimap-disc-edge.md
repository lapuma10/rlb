# Phase 1A.4d.2 — PixelResolver minimap disc-edge projection with angular fallback

**Status:** approved 2026-05-25 — implementation slice.
**Sibling docs:**
- Run 04 (motivating evidence): `docs/learnings/2026-05-25-artemis-tier1-run-04.md`
- Parent slice: Phase 1A.4d (WalkStepBase introduction) + Phase 1A.4d.1 (retry/STUCK fix)

---

## 1. Problem

Tier 1 Run 04 found long minimap walks all collapsing to disc-center
pixel `(1157, 84)`. Operator's character did not move across 14+ click
attempts; `WalkStepBase` correctly STUCK + Aborted via the 1A.4d.1
clean Abort path. Cow loop never gets to run because the bot never
arrives.

Root cause (verified in source):
`PixelResolver.pullOffMinimapOverlay` (line 1126-1164) walks inward
from a clamped projection in 4-pixel steps to escape minimap-overlay
dead-zones (compass / orbs / world-map button). When every step is
occluded, it explicitly returns disc center:

```java
// PixelResolver.java:1161-1163 (pre-fix)
// Every step was occluded — return centre. MINIMAP-intent walks
// tolerate this; WORLD-intent clicks shouldn't reach here.
return new Point(cx, cy);
```

The comment's assumption is **false**: minimap clicks at disc center
map to the player's own tile in minimap coordinates, which OSRS
treats as walk-to-self (silent no-op — no flag, no movement). On the
operator's resizable layout with overlays clustered near the
upper-right rim, NE-bearing projections regularly trip this path.

## 2. Required behavior (corrected)

For non-self walk targets, `PixelResolver` MUST:

1. Project to the minimap disc **rim** in the target's bearing.
2. If the rim point is overlay-occluded, search **angular offsets at
   the same radius** around that bearing: `±10°, ±20°, ±30°, ±45°`
   (smallest deviation first).
3. Return `null` only when every candidate is blocked.
4. **Never return exact disc center** for a non-self target.

Returning null instead of center alone is insufficient — it prevents
known-bad clicks but doesn't make far walks work. The angular sweep
is the primary fix; null is the last-resort safety guard.

## 3. Hard facts (no speculation)

| Fact | Source |
|---|---|
| Resolver returns `(1157, 84)` = disc center for a 21-tile NE walk | Run 04 logs at 12:03:38+ |
| `pullOffMinimapOverlay` has explicit `return new Point(cx, cy)` fallback | `PixelResolver.java:1163` |
| `press()` has no guard blocking MINIMAP intent at the disc center | `HumanizedInputDispatcher.java:745-790` |
| `CanvasInput.mousePress` posts AWT event in canvas pixel coords (no HiDPI/coord-scaling layer) | `CanvasInput.java` |
| Player position in `nav-v21` plan-ok stayed at `(3240, 3262)` across all attempts | Run 04 logs at 12:03:38-12:04:06 |
| Phase 1A.4d.1 retry+STUCK fix verified — zero `IllegalStateException`, walk Aborts cleanly | Run 04 logs |
| `UiDeadZones` is `static`; testable via mocking `Client.getWidget(<overlay-id>).getBounds()` (existing pattern in `PixelResolverDeadZoneTest.java:75`) | Test infrastructure check |

## 4. Production design

### 4a. Rewrite `pullOffMinimapOverlay` — angular search at the same radius

```
Function: pullOffMinimapOverlay(Point p, int cx, int cy)
  if p is null → return null
  if p is not in minimap overlay → return p                 (direct path; common case)
  r ← distance from (cx,cy) to p
  if r < 1.0 → return null                                  (degenerate; already at centre)
  bearing ← atan2(p.y - cy, p.x - cx)
  for offDeg in [+10°, -10°, +20°, -20°, +30°, -30°, +45°, -45°]:
    x ← cx + r * cos(bearing + offDeg)
    y ← cy + r * sin(bearing + offDeg)
    if not in minimap overlay → return (x, y)               (angular fallback)
  log: rim fully blocked at bearing
  return null                                               (last-resort)
```

**Disc center is NEVER returned for a non-self target.** The
existing comment "MINIMAP-intent walks tolerate this" is removed.

### 4b. Handle `Perspective.localToMinimap == null` (very-far targets)

Currently `resolveWalkTarget` (line 141-142) returns null when the
target is beyond `Perspective`'s radial visibility check. New
behavior: derive the bearing via a closer-point projection, project
to the rim at that bearing.

```
Function: projectFarTargetToRim(WorldPoint target, WorldPoint here)
  dx ← target.x - here.x; dy ← target.y - here.y; distW ← hypot(dx, dy)
  if distW < 1 → return null
  scale ← 5.0 / distW                                       (5 tiles in target direction)
  hereLocal ← player.getLocalLocation()
  closerScene ← new LocalPoint(
    hereLocal.x + dx * scale * LOCAL_TILE_SIZE,
    hereLocal.y + dy * scale * LOCAL_TILE_SIZE,
    wv.id)
  closerPx ← Perspective.localToMinimap(client, closerScene)
  if closerPx is null → return null                         (extreme zoom edge case)
  
  // closerPx is inside the disc; derive its disc-relative bearing
  (cx, cy, r) ← disc geometry from minimap widget
  bx ← closerPx.x - cx; by ← closerPx.y - cy
  if bx == 0 && by == 0 → return null
  bearing ← atan2(by, bx)
  return new Point(cx + r * cos(bearing), cy + r * sin(bearing))
```

Reuses `Perspective.localToMinimap` to handle minimap rotation
correctly without reimplementing it. The 5-tile-in-direction point
is always within the minimap's visible range (assuming default
zoom), so `localToMinimap` returns non-null.

Integrated into `resolveWalkTarget`:
```
Point minimap = Perspective.localToMinimap(client, scene);
if (minimap == null) {
    minimap = projectFarTargetToRim(target, here);
    if (minimap == null) return null;
}
minimap = clampToMinimapDisc(minimap);   // existing flow continues
```

Also applied in `resolveMinimapOnly` (line 169-195) for symmetry.

### 4c. Diagnostic logging in PixelResolver

Per operator clarification: keep minimap geometry inside `PixelResolver`.
Add INFO logging inside `pullOffMinimapOverlay` ONLY when an
interesting decision is made:
- Angular fallback used → log bearing + offset angle + chosen pixel
- All blocked → log bearing
- Direct-clean (most common) → no log (avoid per-walk spam)

`HumanizedInputDispatcher` is unchanged — its existing
`walk → world {} via screen ({},{})` log is sufficient.

## 5. Tests

`runelite-client/src/test/java/.../sequence/dispatch/PixelResolverMinimapProjectionTest.java` (new).

Test seam: matches existing `PixelResolverDeadZoneTest.java:36-65`
pattern — mock `Client.getWidget(<minimap-widget-id>).getBounds()`
for disc geometry, mock `Client.getWidget(<overlay-id>).getBounds()`
for occlusion arcs. Drive private methods via reflection (same
`m.setAccessible(true)` idiom as `PixelResolverDeadZoneTest.java:75`).

Cases:

1. **`directClearRimPoint_returnedAsIs`** — input at NE rim, no
   overlay → returned unchanged.
2. **`overlayOnDirectRadial_findsAngularFallback`** — overlay at
   exact NE rim arc, force the function to try ±10° → assert result
   is at +10° or -10° on the same radius.
3. **`overlayOnEverySweptArc_returnsNull_notCenter`** — overlay
   covers all 9 candidates (direct + 8 offsets). Assert returned
   point is `null`. Assert it is NOT `(cx, cy)`.
4. **`farTargetNorth_projectsNearNorthRim`** — driving
   `resolveWalkTarget` end-to-end with a mocked far-N target;
   assert returned pixel y is near disc top (`cy - r ± inset`),
   x close to `cx`.
5. **`farTargetEast_projectsNearEastRim`** — same shape, east.
6. **`nonSelfTarget_neverReturnsDiscCenter_fuzz`** — loop across
   16 bearings × random overlay configs (some clear, some
   occluded); assert returned pixel is never exactly `(cx, cy)`
   even when null is returned.

Existing `PixelResolverRepeatTest` and `PixelResolverDeadZoneTest`
must still pass (regression).

## 6. Files touched

```
M  runelite-client/src/main/java/.../sequence/dispatch/PixelResolver.java
A  runelite-client/src/test/java/.../sequence/dispatch/PixelResolverMinimapProjectionTest.java
A  docs/superpowers/plans/2026-05-25-artemis-phase-1a4d2-pixelresolver-minimap-disc-edge.md  (this doc)
M  docs/learnings/2026-05-25-artemis-tier1-run-04.md   (one-line cross-ref)
```

**Not touched** (per scope constraints):
- `HumanizedInputDispatcher.java` — existing log is sufficient
- `WalkStepBase`, `StateDrivenEngine`, `Artemis`, `ArtemisImpl`
- `CowKillerScript`, `NpcQuery`, `NpcRef`, `ActionRequest`
- `liveTracked` NPC work (Phase 3)
- `RecorderPanel`, `RecorderConfig`
- `UiDeadZones` (we mock its dependencies, don't change it)
- `CanvasInput` (confirmed not the cause)
- Phase 1B grep gate allow-list

## 7. Risks (small, accepted)

| Risk | Severity | Mitigation |
|---|---|---|
| Angular search picks a bearing 45° off intended direction | Low | Smallest-deviation-first ordering puts ±10° before ±45°. Most cases only need ±10° / ±20°. |
| Layout where ALL 9 candidates are blocked (e.g., world map open) | Low | Function returns null; `walkClick` declines to dispatch; `WalkStepBase` STUCKs + Aborts cleanly per 1A.4d.1. Same clean failure path. |
| `projectFarTargetToRim` 5-tile scaling: targets at exactly 5 tiles away project to themselves (closerPx ≈ target's actual projection) | Negligible | Edge case — when target IS within minimap range, this branch isn't taken (only triggers when `localToMinimap` returns null). |
| Existing `PixelResolverDeadZoneTest` widget-mock pattern doesn't compose for our overlay arcs | Low | If reflection + widget-mock proves clumsy, fallback is a `@VisibleForTesting` package-private helper. Not pre-emptive — only if needed. |
| Performance: 9 candidates × per-candidate widget reads | Negligible | Runs once per walk dispatch (≤1Hz worst case). Each `inMinimapOverlay` call is a few widget lookups. |

## 8. Out of scope (deferred)

- Minimap-rotation-aware bearing math without `Perspective.localToMinimap`
  — closer-point approach avoids it cleanly.
- `UiDeadZones` expansion to detect new overlays — orthogonal slice if
  new overlays appear.
- Phase 3 `liveTracked` CLICK_NPC (still deferred).
- F-D2 chat-area park (still deferred).
- F2 stale-moving-cow (still Phase 3 territory).

## 9. Outcome

After 1A.4d.2 lands + rebuild + restart:
- Far walks (>10 tiles or beyond `Perspective.localToMinimap` range)
  project to disc rim in the target's bearing.
- Overlay-occluded rim points trigger angular fallback
  (`±10°` → `±45°` sweep).
- All-occluded → null → walk Aborts cleanly per 1A.4d.1.
- Operator can start Run 05 from any reasonable distance — should
  finally reach the cow field and exercise the cow loop.

Run 05 will be the first run that both completes long walks AND
exercises the cow loop at meaningful sample size. First chance to
fully verify Phase 2C.x.1 (dead-NPC filter).
