# Tier 1 Run 05 — Phase 1A.4d.2 + Phase 2C.x.1 verification

> Empty template prepared per Phase 2D §9
> (`docs/superpowers/plans/2026-05-24-artemis-phase-2d-operator-session-protocol.md`).
> Fifth supervised Tier 1 run; **verifies Phase 1A.4d.2 minimap
> disc-edge projection + angular fallback, then evaluates Phase
> 2C.x.1 dead-NPC filter and the cow loop end-to-end.**
> Fill in during / after the run. Do NOT commit empty.

---

## Purpose

Run 05 is the first run that should both **complete long walks**
(post-1A.4d.2) AND **exercise the cow loop at meaningful sample
size** (post-2C.x.1). Two slices under test together:

- **Phase 1A.4d.2** (commit `ed3e5d734`) — `PixelResolver` minimap
  projection. Long walks should no longer collapse to disc-center
  `(1157, 84)`. Resolver should pick a disc-rim pixel in the
  target's bearing, with angular fallback (±10° → ±45°) if the
  direct point hits an overlay. Disc-center is NEVER returned for
  a non-self target.

- **Phase 2C.x.1** (commit `6395736dc`) — `ArtemisImpl.matches()`
  dead-NPC filter. Once the bot can reach the cow field, this
  should finally be verifiable. After a cow dies, the same scene
  index must NOT reappear in consecutive attack attempts.

Regression watch (must remain fixed):

- **F1** scripted logout via Selector fallback (Run 01) → 0
- **F3** first-attack-after-walk dispatcher-busy drop (Run 01) → 0 or ≤1
- **F-D1** `WalkStep is single-use` IllegalStateException (Run 03) → 0
- **F-E1** minimap-walk click at disc center (Run 04) → 0

Out of scope for Run 05 (do NOT block):

- **F2** stale-moving-cow click misses — Phase 3 `liveTracked`
  CLICK_NPC still DEFERRED.
- **F-D2** off-canvas park lands over chat-area UI — separate
  dispatcher-safety slice.

---

## Identification

```
Runtime commit (binary):    ed3e5d734   (Phase 1A.4d.2; jar built 12:58:41)
Protocol/doc commit:        0fce87fac   (Phase 2D operator protocol)
Previous runs:              Run 01 (run-log 56863742f, runtime 0fce87fac)
                            Run 02 (run-log 3a28f6d76, runtime f3b75298e)
                            Run 03 (run-log c99a9af16, runtime 6395736dc)
                            Run 04 (run-log committed in slice
                                    1A.4d.2 bundle ed3e5d734, runtime
                                    d489543d0)
Branch:                     master
Date:                       <fill at start>
```

## Environment

```
Account:                 <test account label — NOT username>
World:                   <number>, <pop label>
Start location:          <near cow field / far north / other>
                         — F-E1 fix means long-walk start is OK now;
                          note where you start from
Inventory at start:      <empty / food / other>
Gear at start:           <brief>
Recording session:       <enabled — session JSON path: ... | disabled>
                         — recommended ENABLE so post-run analysis can
                          read Events.Step entries for the cow loop
```

## Timing

```
Start time:              HH:MM:SS
Stop time:               HH:MM:SS
Duration:                MM:SS
Stop trigger:            <operator-normal / failure-trigger §6.X / fallback §5.X>
```

## UI state transitions observed

```
- Start clicked at HH:MM:SS → status "running" at HH:MM:SS (Δ ms)
- Stop clicked at HH:MM:SS  → status "idle/disabled" at HH:MM:SS (Δ ms)
```

## Step tree behavior

```
Source: <session JSON post-run | operator-observed estimates>

- walkTo dispatches:                 <count> (success <n>, failure <n>)
  - long walks (>10 tiles) noted:
- "walk → world ... via screen      <count and rough screen positions —
  (...)":                            should NOT be (1157, 84) for any
                                     long non-self walk per 1A.4d.2>
- F-E1 indicator — clicks at         <count — must be 0 with 1A.4d.2>
  (1157, 84) for non-self target:
- PixelResolver "minimap angular     <count — should fire only when a
  fallback" INFO log lines (NEW):    rim point hits an overlay; common
                                     case (clear rim) is NOT logged>
- PixelResolver "minimap rim fully
  blocked" INFO log lines (NEW):     <count — should be rare; indicates
                                     a layout where overlays cover the
                                     entire rim arc>
- PixelResolver "far-target rim
  projection" INFO log lines (NEW):  <count — fires when Perspective.
                                     localToMinimap returned null and
                                     1A.4d.2 fallback kicked in>
- Cow targets selected:              <count distinct scene indices>
- Attacks dispatched:                <count>
- Busy-gate skip count:              <count or "qualitative only">
- F1 indicator — widgetVerbClick     <count — must be 0>
  Logout:
- F2b indicator — same scene index   <count — must be 0 with 2C.x.1>
  retried 2+ times in <30s window:
- F3 indicator — "dispatcher busy,   <count per walk arrival —
  dropping CLICK_NPC":                expected 0 or 1 per cycle>
- F-D1 indicator — "WalkStep is      <count — must be 0 with 1A.4d.1>
  single-use" IllegalStateException:
- "right-click menu did not          <total count vs Run 02 baseline
  contain 'Attack'":                  of 16; should drop sharply
                                     thanks to 2C.x.1>
```

## Exceptions / log anomalies

```
- <stacktraces, timestamps, brief tag>
```

## Failures observed

```
- <failure trigger §6.X at HH:MM:SS, what operator saw>
```

## Per-failure regression checks (vs prior runs)

```
F1 (scripted logout):
  Status:                <NOT OBSERVED — fix STILL VERIFIED | OBSERVED — regression>

F2 (stale-moving-cow click misses, Phase 3 territory):
  Avg clicks per cow:    <number; expect lower than Run 02's 4-5>
  Status:                <IMPROVED vs Run 02 | NO CHANGE | WORSE>

F2b (dying-cow retargeting, Phase 2C.x.1 target):
  Status:                <NOT OBSERVED — fix FULLY VERIFIED |
                          PARTIALLY VERIFIED — sample limited |
                          OBSERVED — regression>
  Evidence:              <log excerpt>

F3 (busy-drop after walk arrival):
  Drops per walk cycle:  <count>
  Status:                <NOT OBSERVED | OBSERVED but caught by
                          inner Selector>

F-D1 (WalkStep retry / premature STUCK):
  Status:                <NOT OBSERVED — fix STILL VERIFIED | OBSERVED — regression>

F-E1 (minimap walk at disc center, Phase 1A.4d.2 target):
  Status:                <NOT OBSERVED — fix VERIFIED |
                          OBSERVED — regression>
  Evidence:              <walks actually move the player;
                          screen positions for long walks are not
                          (1157, 84); player makes progress toward
                          LUMBRIDGE_COW_FIELD>
```

## Stop outcome

```
Stop worked cleanly?     <yes / no — describe>
Manual logout completed? <yes / no>
```

## Screenshots (optional)

Attach to `docs/learnings/2026-05-25-artemis-tier1-run-05/`:

- `ui-idle.png`
- `ui-running.png`
- `ui-after-stop.png`
- `canvas-attack.png`
- `walk-progress.png` (NEW — to evidence F-E1 fix: long walk visibly
  progressing toward cow field)

## client.log excerpt

```
Path: ~/.runelite/logs/client.log
Window: <UTC start>–<UTC end>
```

## Operator notes

```
- <free-form>
```

## Verdict

```
<Tier 1 PASS — F1/F2b/F3/F-D1/F-E1 all addressed; residual F2
  stale-moving-cow misses acceptable for v1 |
 Tier 1 PARTIAL — describe what passed and what didn't |
 Tier 1 FAIL — see §10 routing in Phase 2D protocol>
```
