# NPC Interaction API — design (2026-04-30)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans
> when this is approved. Steps use checkbox (`- [ ]`) syntax for tracking.
> Per project memory: keep code work inline; subagents reserved for QC.
> Per project memory: don't paste full Java sources — describe behavior;
> inspect APIs before specifying signatures.

**Goal:** Ship one reusable engine-grade API for clicking NPCs from any
script (attack, GE clerk, banker, etc.) so we stop hand-rolling click
math and silently failing into "Walk here".

**Architecture:** Thin façade in `sequence/scene/` that wraps the
existing `ActionRequest.CLICK_NPC` + `HumanizedInputDispatcher.npcClick`
pipeline. Adds one missing piece — "find nearest NPC matching a
predicate / id-set" — and a 2-line dispatch helper. No new Steps, no
new recovery DSL, no blackboard plumbing.

**Tech stack:** Java 17, RuneLite API (`NPC`, `WorldView`,
`Client.getTopLevelWorldView`), existing engine internals
(`ActionRequest`, `HumanizedInputDispatcher`, `PixelResolver`).

---

## Why this exists

The dispatcher already has the right primitive — `ActionRequest.Kind.CLICK_NPC`
+ `verb` is routed through `PixelResolver.resolveNpc` (samples a pixel
inside the actual convex-hull polygon, not its bounding rectangle, falls
back to the canvas tile poly, rejects against recent clicks). The
`HumanizedInputDispatcher.npcClick` path then does the hover-default
check, falls back to right-click + `VerbMatcher`-driven menu match, and
errors explicitly if the menu lacks the verb.

**The gap is that scripts can side-step that pipeline.** `GeInteraction.openGrandExchange`
did exactly that — computed `hull.getBounds()` center and right-clicked
that pixel, which lands on empty grass when the model is rendered
off-axis. Symptom seen on 2026-04-30 GE run: right-click opens a menu
with only "Walk here", `pickMenu("Exchange")` falls through, GE never
opens, step times out as `GeNotOpen`, retries 3× same bad pixel, abort.

That bug is patched on this branch. The plan here is to make the right
path the obvious path so it doesn't get reinvented.

## Scope

**In:** Finding an NPC matching a predicate / id-set on the loaded
scene, and dispatching `CLICK_NPC` with a verb. Migration of any
hand-rolled NPC click sites surfaced by the audit.

**Out, separate plans later:**
- `GameObjectInteraction` — same shape, but interacts with `TransportResolver`
  and verb resolution differently. Earn it after NPC ships.
- `GroundItemInteraction` — `CombatLoop` already does it; centralize after.
- Widget interactions — already centralized via `dispatcher.clickCanvas`
  on widget bounds. Widgets *are* rectangles; the hull/poly mismatch
  doesn't apply.
- "Walk to NPC if out of range" — engine-level recovery, separate.
- Combat-loop migration — `ChickenCombatLoop.doEngage` already uses
  `ActionRequest.CLICK_NPC` correctly. Style-only migration; defer.

## Package

`net.runelite.client.sequence.scene` (new). Lives under `sequence/`
so both `sequence/activities/*` and `plugins/recorder/*` can depend
on it without circular references.

## File map

**Create:**
- `runelite-client/src/main/java/net/runelite/client/sequence/scene/NpcInteraction.java` — the API class (~80 lines).
- `runelite-client/src/test/java/net/runelite/client/sequence/scene/NpcInteractionTest.java` — unit tests with a fake dispatcher.

**Modify (migration sites):**
- `runelite-client/src/main/java/net/runelite/client/sequence/activities/ge/GeInteraction.java` — drop `findNearestGeClerk` + manual `ActionRequest`-build; delegate to `NpcInteraction.clickNearest(GE_CLERK_NPC_IDS, "Exchange")`.
- Wherever the audit (Task 1) finds other hand-rolled `getConvexHull().getBounds()` + click pairs.

**Wire in (one site):**
- The factory class that constructs `GeInteraction` (currently in `sequence/activities/ge/GrandExchangeSequenceFactory.java` per file listing). Pass an `NpcInteraction` in. Same for any other activity factories surfaced by audit.

**Do NOT touch this round:**
- `combat/ChickenCombatLoop` — uses `ActionRequest.CLICK_NPC` correctly. Migration is style-only.
- Bank widget click code — widgets, not NPCs.

## API shape (signatures only — no implementation in plan doc)

`NpcInteraction` is a final class with constructor
`(Client client, HumanizedInputDispatcher dispatcher)` and these
public methods:

- `NPC findNearest(Set<Integer> ids)` — nearest scene NPC whose id is in the set, by squared world-distance from local player; null if none. Pure read.
- `NPC findNearest(Predicate<NPC> filter)` — same, custom filter.
- `boolean clickNearest(Set<Integer> ids, String verb)` — find + dispatch `CLICK_NPC` with verb. Returns true iff a target was found and dispatched.
- `boolean clickNearest(Predicate<NPC> filter, String verb)` — same, custom filter.
- `boolean attackNearest(Predicate<NPC> filter)` — sugar for verb="Attack".
- `boolean click(NPC npc, String verb)` — dispatch against a pre-resolved NPC ref. For callers that need the NPC first to read state.

Key behavioral rules (these go in the class javadoc, not just the plan):

1. **Fire-and-return.** No waiting on dispatcher idle, no engagement
   verification. The calling Step's `check()` polls the snapshot.
2. **No recovery.** Null target → false. Busy dispatcher → still
   queued via `dispatcher.dispatch(req)` (the same path
   `ChickenCombatLoop.doEngage` uses; it serializes correctly).
3. **Threading.** `findNearest` reads `Client.getTopLevelWorldView()`,
   must run on the client thread. The `clickNearest` / `attackNearest`
   wrappers route the lookup via `dispatcher.runOnClient(...)`
   internally so callers from any worker thread are safe.
4. **One log line per dispatched click** at INFO: id, name, verb.
   No log on null target — caller surfaces that as a step diagnostic.

## Behavior reference (clickNearest)

1. Run on client thread (via `dispatcher.runOnClient`):
   iterate `wv.npcs()`, filter by id set / predicate, pick min squared
   world-distance from `client.getLocalPlayer().getWorldLocation()`.
2. If null → return false.
3. Build `ActionRequest.builder().kind(CLICK_NPC).channel(MOUSE)
   .npcIndex(npc.getIndex()).verb(verb).build()`.
4. `dispatcher.dispatch(req)`.
5. Return true.

The hard parts — picking a pixel inside the hull polygon, verifying
the top menu entry matches the verb, falling back to right-click +
`VerbMatcher` — happen inside `HumanizedInputDispatcher.npcClick` and
are shared by every caller for free.

## Tests

One test class, no integration. Fake `HumanizedInputDispatcher` that
records every `dispatch(ActionRequest)` call. Stubbed `Client` returning
a synthetic `WorldView` with 2-3 NPCs at controlled `WorldPoint`s.
`runOnClient` immediately invokes the supplier in-thread (no real
client thread needed for tests).

Cases:

1. `clickNearest(ids, "Exchange")` with one matching NPC at world-dist 5
   and one non-match at dist 3 → dispatches `CLICK_NPC` for the matching
   id with verb="Exchange".
2. `clickNearest(ids, "Attack")` with two matches at dist 3 and 7 →
   picks dist 3.
3. `clickNearest` with no matches → returns false, no dispatch recorded.
4. `clickNearest` with `localPlayer == null` → returns false, no dispatch.
5. `attackNearest(filter)` → routes verb="Attack".
6. `click(npcRef, "Exchange")` → dispatches with that exact npcIndex,
   no lookup performed.

Pixel-resolution / menu-match correctness is already covered by
`HumanizedInputDispatcher`'s existing tests. Don't duplicate.

---

## Tasks

### Task 1 — Audit hand-rolled NPC click sites (no code changes)

- [ ] Grep `runelite-client/src/main/` for `getConvexHull().getBounds()`
      paired (within 30 lines) with `clickCanvas` or `rightClickAndPickMenu`.
- [ ] Grep for private `findNearest*Npc`-shaped helpers in `plugins/recorder/`
      and `sequence/activities/`.
- [ ] Grep for direct `ActionRequest.builder().kind(CLICK_NPC)` outside
      `combat/` and `sequence/dispatch/`.
- [ ] Append the resulting list at the bottom of this plan as a
      checklist (`### Migration sites — discovered`). One line per site
      with file:line and a one-sentence note. **Don't change code.**

Output of this task is a committed plan update. Used to scope Task 5
(skip if list is empty besides `GeInteraction`).

### Task 2 — Build `NpcInteraction`

- [ ] Create `runelite-client/src/main/java/net/runelite/client/sequence/scene/NpcInteraction.java`
      implementing the API in the "API shape" section above.
- [ ] Class javadoc covers the four behavioral rules (fire-and-return,
      no recovery, threading, log policy).
- [ ] Compile via `:client:compileJava` (NOT `:runelite-client:`).
- [ ] Commit: `seq(scene): add NpcInteraction — one API for find+click NPCs`.

### Task 3 — Unit tests

- [ ] Create `runelite-client/src/test/java/net/runelite/client/sequence/scene/NpcInteractionTest.java`
      covering the 6 cases in "Tests" above.
- [ ] Use a hand-written fake `HumanizedInputDispatcher` (Mockito can't
      mock final classes; combat already follows this pattern via
      `CombatDispatcher.forHumanized` — copy that style).
- [ ] Run `:client:test --tests "*NpcInteractionTest"` — green.
- [ ] Commit.

### Task 4 — Migrate `GeInteraction.openGrandExchange`

- [ ] Add `NpcInteraction` field + constructor parameter.
- [ ] Replace the body with one `npc.clickNearest(GE_CLERK_NPC_IDS, "Exchange")`
      call (plus the existing null-found warn).
- [ ] Delete `findNearestGeClerk` (now lives in NpcInteraction).
- [ ] Update `GrandExchangeSequenceFactory` to construct + pass the
      `NpcInteraction` instance.
- [ ] Run `:client:test --tests "*ge.*"` — green (covered by existing GE
      tests; the action interface didn't change, the impl did).
- [ ] Commit.

### Task 5 — Migrate audit-list sites (one commit per site)

- [ ] For each entry in `### Migration sites — discovered` (Task 1):
      replace the hand-rolled lookup + dispatch with `NpcInteraction`,
      remove now-dead helpers, run tests, commit. Skip entries already
      using `ActionRequest.CLICK_NPC` correctly.
- [ ] If audit list is empty, this task is a no-op — note it in the
      commit log of Task 4.

### Task 6 — Wire into shared activity context

- [ ] Decide one place to construct the singleton `NpcInteraction`
      (likely the plugin-level `RecorderManager` or `RecorderPlugin`
      that already holds `Client` + `HumanizedInputDispatcher`). Pass
      it into the activity factories that need it.
- [ ] No new DI framework. One field per consuming factory.
- [ ] Compile + commit.

---

## Risks

1. **`NPC.getIndex()` reuse across despawns.** RuneLite NPC indices are
   scene-local and recycled after a despawn. Between our `findNearest`
   and the dispatcher's `findNpc(npcIndex)` (called inside `npcClick`),
   the slot could in principle hold a different NPC. Mitigation: the
   gap is microseconds — `findNearest` and `dispatch` are in the same
   call. Steps that *hold* an NPC ref across ticks should re-find each
   tick. Document this in the class javadoc.
2. **Hull missing when off-screen.** If the matched NPC is on the
   loaded scene but its model isn't rendered (offscreen / occluded),
   `PixelResolver.resolveNpc` falls back to the canvas tile poly. The
   game's "Exchange" menu entry only appears when the model is
   physically under the cursor — so a tile-poly-only click can still
   land on "Walk here". This is an existing limitation of the dispatcher;
   not in scope to fix here. Calling Step's `onFailure` retries.
3. **Player out of interaction range.** Engine accepts the click but
   won't progress until the player walks adjacent. We don't wait for
   that — calling Step's `check()` does. No special handling needed.

---

## Done means

- [ ] `NpcInteraction` exists in `sequence/scene/` and its unit tests
      pass green under `:client:test`.
- [ ] `GeInteraction.openGrandExchange` is 4–5 lines that delegate to
      `NpcInteraction.clickNearest`.
- [ ] Audit list (Task 1) is committed; every entry has a migration
      commit referencing it OR a note explaining why it stayed.
- [ ] `:client:test` is green end-to-end.
- [ ] No new public types beyond `NpcInteraction`. No new dependencies.
- [ ] Branch is rebase-clean against `master`.

---

## Migration sites — discovered

_Populated during Task 1. Empty until then._
