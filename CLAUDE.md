## Threading model — read FIRST. Most silent freezes come from getting this wrong.

The OSRS client has **one shared thread** that decodes packets, runs cs2
scripts (chatbox prompts, dialogues, bank rebuilds), ticks NPCs, renders
frames, and drains the click queue. Sleeping it for 7ms freezes the
whole game world for 7ms — including the cs2 that would open the
chatbox you're trying to type into. Symptom: "client looks throttled,
state never advances, my polling loop times out" — that's never OSRS
lagging, it's us holding its only thread.

**Three threading questions, in order. Answer all three before writing
the code, not after the runtime guard catches it.**

### 1. Am I reading game state? → client thread.

`Widget`, `Scene`, `Tile`, `NPC`, `Menu`, varbits, varplayers — all
require the client thread (RuneLite asserts under `-ea`). Marshal with
`clientThread.invoke(...)`, `BankInteraction.onClient(supplier)`,
`HumanizedInputDispatcher.onClient(...)`. Reads are microseconds — get
in, get out.

### 2. Am I just building data / making a decision? → either thread.

Constructing an `ActionRequest`, comparing snapshot fields, picking an
NPC from a list, computing a target qty — pure compute, runs anywhere.

### 3. Am I doing a multi-step input flow with internal waits? → dispatcher worker, NEVER the client thread.

Right-click → wait for menu → pick verb → wait for chatbox prompt →
type digits → press Enter → wait for varbit. Every one of those waits
is a `SequenceSleep.sleep(...)` on the calling thread. If the calling
thread is the client thread, you've frozen the game.

This is the question that's most often missed. The trap: an engine
`Step.onStart` / `check` / `tick` runs on the client thread, so calling
`bank.withdrawX(...)` (or any blocking method on `BankInteraction` /
`GeInteraction` / `HumanizedInputDispatcher`) **directly from a step is
a bug** even though it compiles and looks fine.

The fix shape is always the same — enqueue an `ActionRequest` to the
dispatcher worker, return; let `Step.check()` poll the snapshot:

```
Step.onStart (client thread)               Dispatcher worker
─────────────────────────────              ──────────────────────────
read state via onClient(...)               run the multi-step flow:
build ActionRequest    ─── enqueue ──►       click → SequenceSleep
return immediately                           → type → SequenceSleep
                                             → press Enter
Step.check (every tick)                      → done
read snapshot.inventory().count(itemId)
"did target reach?" → Succeeded / RUNNING
```

`SequenceSleep.sleep(client, ms)` already throws on the client thread
to make this fail loud — but that's *detection*, not *prevention*.
Don't ship work that depends on the runtime guard catching it. Walk
through the three questions before writing the step.

**Worked example — the bank-withdraw-X regression of 2026-04-30:**
`WithdrawItemStep.onStart` (client thread) called
`bank.withdrawX(itemId, delta)`. Inside, `tryWithdrawX` does
right-click → "Withdraw-X" → chatbox prompt → typed number → Enter,
with `SequenceSleep.sleep(...)` between each. First sleep threw,
the step aborted before any verb was selected, the bot just sat at
the bank doing nothing. Fix: a `BANK_WITHDRAW_X` `ActionRequest.Kind`
that the dispatcher worker runs; `WithdrawItemStep.onStart` enqueues
and returns; `check()` polls inventory until target reached.

### Worker threads

Worker threads (dispatcher, login-assistant, anything you spawn) are
safe for `SequenceSleep`. They are NOT safe for direct widget/scene
reads — marshal back to the client thread for the read, then continue.
The Swing EDT is also a worker, but treat it as off-limits for blocking
work — panel button click handlers must `Thread.start()` for anything
that calls `awaitIdle` or `dispatcher.dispatch`.

---

## Sequence engine — read FIRST for any NEW gameplay scripting

The repo has a state-driven sequence engine under
`runelite-client/.../sequence/`. New gameplay scripts MUST use it, not
the enum-FSM `tickLoop` pattern in `recorder/scripts/`.

See `runelite-client/src/main/java/net/runelite/client/sequence/ARCHITECTURE.md`
for the engine map, step lifecycle, and the cooking-banking case study.

The legacy enum-FSM scripts in `recorder/scripts/` (CookingScript banking-legacy
path, ChickenFarmV3, LumbridgeBankPenScript) predate engine adoption. Read them
for domain knowledge (widget IDs, dispatch semantics) but DO NOT copy their FSM
shape for new flows.

---

# File map — read this BEFORE searching

All bot code lives under one directory. Subpackages map 1-to-1 with
subsystems. When investigating a bug or making a change, identify the
subsystem first, then read those 1-3 files. **Do not grep the whole
repo.** Most fixes touch 1-2 files plus a matching test.

## Bot code

```
runelite-client/src/main/java/net/runelite/client/plugins/recorder/
  combat/     ChickenCombatLoop, CombatStateTracker, NpcSelector,
              AutoRetaliateToggle, CombatStyleSwitcher,
              TrainingSession, TrainingPlan, SkillRotation
  cook/       CookingInteraction (light fire / use tinderbox / cook)
  farm/       BankInteraction (deposit / withdraw)
  scene/      SceneScanner (find objects/NPCs on tiles)
  scripts/    ChickenFarmV3Script, CookingScript (top-level FSMs)
  trail/      TrailWalker, TrailPath, Leg (recorded-trail replay)
  walker/     UniversalWalker (waypoint walker, non-trail walks)
  widget/     WidgetActions (visibility checks, click widget)
  transport/  TransportResolver (find verb on game object at tile)
  RecorderPlugin.java + RecorderPanel.java — plugin glue, UI panel
```

## Click pipeline (cross-package, often touched together)

```
runelite-client/src/main/java/net/runelite/client/sequence/
  dispatch/HumanizedInputDispatcher.java  click execution, busy-state
  dispatch/PixelResolver.java             world tile → screen pixel
  internal/ActionRequest.java             the dispatched action
```

## Tests

`runelite-client/src/test/java/net/runelite/client/plugins/recorder/`
mirrors the main tree. **Read the matching `*Test.java` for any file
you change** — the fixtures tell you what behaviour is expected.

## Runtime data (NOT in the repo)

```
~/.runelite/logs/client.log               current session — tail this
~/.runelite/logs/client_YYYY-MM-DD.0.log  prior days (rotated)
~/.runelite/recorder/trails/*.json        recorded trails (TILE+TRANSPORT)
~/.runelite/                              all RuneLite settings + state
```

## RuneLite API (`runelite-api/`) — delegate, don't browse

Don't grep -r the whole api package yourself. The surface area is huge
and the noise destroys context. **Spawn an `Explore` subagent for any
api lookup** — it returns just the signature(s) + one-line JavaDoc,
keeping your context clean.

Required prompt shape (give it real context, not just a name):

> Find the RuneLite API symbol for *X*. **Context:** what subsystem
> you're working on, why you need it, what you already know /
> already tried. **Where to look:** the most likely package path
> (`runelite-api/src/main/java/net/runelite/api/<...>/`). **Return:**
> method/interface signature(s) + the one-line JavaDoc per match,
> max 3 candidates. **No file dumps.**

Example call:

```
Agent({
  description: "Find Player idle-state API",
  subagent_type: "Explore",
  prompt: "Find the RuneLite API symbol that tells you a Player is
           currently idle / standing still.
           Context: editing TrailWalker.handleTransportLeg — I want to
           skip clicking a stair object while the player is mid-walk
           to it. I already use Player.getPoseAnimation() ==
           Player.getIdlePoseAnimation(); want to know if there's a
           cleaner shortcut.
           Where to look: runelite-api/src/main/java/net/runelite/api/Player.java
           and any related interfaces (Actor, Renderable).
           Return: signature(s) + one-line JavaDoc, max 3. No code dumps."
})
```

## Bug investigation checklist

Do this in order. Don't skip ahead.

1. **Tail `~/.runelite/logs/client.log`** to the last few minutes.
   Establish what was running and where it stopped.
2. **Identify the subsystem from the logger tag** in the log:
   `[chicken-farm-v3]`, `[trail-walker]`, `[cooking-script]`,
   `[humanized-input]`, `[chicken-combat-loop]`. The tag tells you
   which subpackage to read.
3. **Read THAT subsystem's files** (usually 1-3 files in one
   subpackage above).
4. **Read the matching `*Test.java`** to see expected behaviour and
   understand the test fixtures.
5. Only **grep cross-package** if step 4 didn't answer it.
6. Only **delegate an api-search subagent** if step 5 also failed
   AND you genuinely need a `runelite-api/` symbol.

---

# CLAUDE.md — scripter self-check questions

Before writing or modifying any RuneLite-bot script, walk through these
questions. If the answer to any is "I don't know" or "I assumed yes",
**stop and verify in code first**. Most V3 bugs were silent failures
where the bot kept running but the engine was ignoring its clicks (or
treating them as walks because the target widget was hidden).

---

## 1. Widget visibility

> **Does the widget I'm interacting with actually need to be open?**

- A "widget click" only does what you want when the widget is **rendered
  on screen**. If it's hidden (parent tab collapsed, dialog dismissed,
  layout swap), the engine treats the click as a tile walk on whatever
  is behind the widget's stale bounds. Player drifts away. **You will
  not see a stack trace.**
- Always check **`isHidden()` up the parent chain**, not just the leaf
  widget. The engine renders only when every ancestor is visible.
- Use `recorder/widget/WidgetActions.isVisible(widgetId)` — it walks
  the chain.

> **If the widget is hidden, what opens it?**

- A sidebar tab (Combat, Inventory, Skills, Prayer, Magic, …) → click
  the tab icon OR press the keybind (F1–F12 by default, but **the user
  can rebind**, so don't hardcode F-keys without checking).
- A dialog (bank, NPC chat, level-up notice) → triggered by another
  action. Make sure that action ran first.
- The chatbox → almost never hidden but can be in a non-default state.

> **If I open a tab to do my work, am I expected to close / restore it?**

- Probably yes. If the user had the **inventory tab open** for kill
  loot, and your script switched to the combat tab to change style,
  switch back to inventory when done. Otherwise the next loot-take
  click hovers an empty sidebar and the menu pre-check fails.
- Track `client.getVarbitValue(Varbits.SIDE_PANELS)` (varbit 4607) at
  the **start** of your action; restore at the end.

## 2. Prerequisites

> **What state must be true before this click works?**

- Player must be **logged in** (`client.getGameState() == LOGGED_IN`).
- Player must be **on the right plane**, **in the right area**, **alive**.
- For an NPC interaction: the NPC must be on the **loaded scene** and
  within attack range. `WorldView.npcs()` only contains loaded NPCs.
- For a ground-item pickup: the item must be on the **player's tile**
  in the loaded scene, owned by the player (`OWNERSHIP_SELF`), and the
  inventory must have a free slot.
- For a bank deposit: bank widget must be open AND the bank inventory
  container must be loaded (the widget can be open for one tick
  before the container fills — `bank.bankReady()` checks this).

> **Has any previous click finished?**

- The shared `HumanizedInputDispatcher` runs **one chain at a time**.
  If you dispatch while busy, your request is silently dropped. Always
  call `dispatcher.isBusy()` (or `awaitIdle(timeoutMs)`) before
  dispatching from a non-walker subsystem (combat, training, banking).
- The walker checks `isBusy()` itself; combat-style / retaliate /
  training calls must do the same.

## 3. State machine assumptions

> **Can the state I'm in actually be reached?**

- Don't add an `if (state == X)` branch without proving X is reachable
  from a state transition. Dead branches mask bugs.

> **What's the FAST path? What's the slow path? What's the FAILURE
> path?**

- Every dispatch can: succeed, get dropped silently, succeed but the
  engine ignores it (offscreen), succeed and the player walks somewhere
  unexpected. If you can't enumerate all four for your action, you
  haven't thought it through.

> **If the action takes >1 tick, what guards against it being
> retriggered?**

- Throttle (e.g. `lastDispatchMs + THROTTLE_MS > now`).
- State flag (e.g. `pendingClick`).
- Verify the state changed (varplayer/varbit poll) before issuing
  another.

## 4. Combat / training specifics

> **The chicken died in 1 hit. Did my tracker observe the engagement
> tick?**

- A 600 ms server tick can encompass the click → walk-to-NPC →
  attack → damage → kill → despawn. Your tracker's first observation
  may see the NPC already gone. If the tracker requires
  `everEngaged=true` (set from `npc.getInteracting() == self`), it
  won't flag dead and the FSM hangs in IN_COMBAT forever.
- Solution: when the locked NPC vanishes, treat as dead. Over-counting
  someone-else-killed-it is a minor issue; under-counting deadlocks.

> **Is the combat-style selector actually a combat-tab widget?**

- `InterfaceID.CombatInterface._0` through `._3` are inside the
  combat-tab content. They are HIDDEN when a different sidebar tab is
  active. Don't dispatch a click without `combatTab.isCombatTabOpen()`.

> **Is auto-retaliate behaving the way I expect?**

- VarPlayer 172 (`OPTION_NODEF`) is **0 = ON, 1 = OFF**. Counter-
  intuitive. Test which value you read before assuming.

## 5. UI tab discipline

> **When I'm checking XP in the skills tab, is the skills tab open?**

- No. Open it first. Reading skill XP via `client.getSkillExperience()`
  works without the tab open (game state, not widget state). But
  HOVERING an icon to display the "X exp to next level" tooltip needs
  the **skills tab content to be visible** AND the cursor moved to the
  icon's bounds.
- After hovering, the engine takes 1-2 frames to render the tooltip.
  Read the tooltip widget AFTER that delay or it'll be stale.

> **When I deposit at the bank, is the bank widget actually open?**

- `bank.isBankOpen()` checks `Bankmain.UNIVERSE`. Use it. Don't trust
  state alone.
- After depositing, **close the bank** before walking. Stale bank UI
  can swallow walk clicks (cursor lands on a deposit button).

> **When I right-click a chicken to attack, is there an open menu in
> the way?**

- `client.getMenu().isOpen()` — if true, the engine routes your click
  to the menu, not the world. Close any leftover menu (press Escape
  via dispatcher) before the next world click.

## 6. Recorded trail discipline

> **Is the recorded trail tile actually walkable in the game right
> now?**

- Tiles recorded after a TRANSPORT (e.g. (3206, 3228, p=2) right after
  a Climb-down click) may have been the engine routing the player onto
  the staircase as part of the action. You can't walk to those tiles
  directly — only the staircase action will route you there.
- `TrailPath.fromTrail` skips them for stair transports.

> **Is the post-gate tile in the new walk leg different from the
> pre-gate tile?**

- The engine records a duplicate of the pre-gate tile right after a
  gate "Open" click. If that duplicate ends up in the post-gate walk
  leg, `chooseLegIndex` will see "next leg contains player" and SKIP
  past the TRANSPORT — the bot walks straight into a closed gate.
- `TrailPath.fromTrail` suppresses the duplicate.

## 7. Position freshness — never hardcode, never cache while moving

> **Did I look up this widget / item / object's position RIGHT BEFORE
> clicking it?**

- Hardcoded pixel coordinates are forbidden. The user can resize the
  client, swap fixed↔resizable, drag the chat window, swap layouts.
  Every dispatchable position must be derived from a live widget /
  bounds / convex-hull lookup at click time.
- Widget bounds: `client.getWidget(id).getBounds()` — call this on the
  client thread inside `PixelResolver.resolveWidget`, not earlier.
- Game object hull: `obj.getConvexHull()` — fresh per-tick, the
  rendered model moves with camera rotation.
- Tile poly: `Perspective.getCanvasTilePoly(client, scene)` — depends
  on camera + zoom, so cache invalidates when either changes.

> **Did I capture a position while the player / camera / object was
> moving, then click it after they stopped?**

- Don't. The position you captured is stale. Re-fetch when the entity
  is settled. Two cases worth special care:
  1. **Player walking, then click an NPC.** If you captured the NPC's
     hull during the walk, it'll be off-screen / off-position by the
     time you click. Wait for player pose to be idle, THEN read the
     NPC hull, THEN dispatch.
  2. **Camera mid-rotation, then click a game object.** The hull
     projection moves every frame. Same fix — settle, fetch, click.

> **My click resolution succeeded. Is the cursor still pointing at
> what I thought it was?**

- Between `moveCursorTo(...)` and `clickPress(...)`, anything could
  have changed: a chicken walked under the cursor, a player obstructed
  the tile, a menu opened. The dispatcher's `isLeftClickWalk` /
  `topMenuLabel` pre-checks read the **engine's actual hover state**,
  not your assumption — trust those, not the position you computed
  150ms ago.

> **For switching tabs / opening side panels: do I find the icon
> widget, get its bounds, click it?**

- Yes. Don't use F-keys (the user can rebind them). Don't hardcode
  canvas pixels (layout swaps move the icons). Use the icon widget's
  current bounds via `WidgetActions.clickWidget(widgetId)`. The same
  rule applies to ANY UI you want to open — locate the widget by id,
  let `PixelResolver.resolveWidget` compute fresh bounds at click
  time, dispatch.

## 8. Dispatcher quirks

> **A click I dispatched returned without error. Did the engine
> actually process it?**

- Three possible outcomes:
  1. Click reached the engine and produced the menu action you wanted.
  2. Click reached the engine but resolved to a different menu entry
     (e.g. "Take Feather" instead of "Walk here").
  3. Click was dropped because the dispatcher was busy (logged but
     no exception).
- For (2), the dispatcher does a `isLeftClickWalk` pre-check for walks
  and falls back to the minimap. For other actions, it'll just fire
  the wrong menu — verify state changed before assuming success.

> **Is the cursor still on the canvas after my click?**

- The dispatcher randomly parks the cursor off-edge after some clicks.
  If your next action computes a pixel and moves the cursor, fine. If
  it tries to read a hover state, the cursor isn't on canvas anymore.

> **The whole client looks frozen — NPCs aren't moving, cs2 isn't
> running, my UI prompt isn't opening. Is OSRS lagging?**

- No. **OSRS is never frozen on its own.** When game state stops
  advancing, the cause is almost always a right-click context menu
  the bot opened and never dismissed. OSRS blocks every game thread
  while a menu is up.
- Where this happens: any `selectMenuVerb(verb)` call that returns
  `false` (verb not in the menu) leaves the menu open. Five sites
  in `HumanizedInputDispatcher`: `boundsClick`, `widgetVerbClick`,
  `npcClick`, `gameObjectClick`, `invSlotClick`. All of them now
  `tapKey(VK_ESCAPE)` on failure — keep that pattern in any new
  verb-click helper.
- Symptom-to-cause: chatbox prompt that "doesn't open until our
  script gives up" = stuck menu blocking the cs2 that would open
  it. Once we abort, the menu eventually dismisses and the queued
  click effect finally renders.

## 9. Threading

See the **"Threading model"** section at the top of this file. The
three-question framework (read on client thread, build/decide
anywhere, multi-step blocking flows on dispatcher worker) is
load-bearing — re-read it before any sequence-engine step or
dispatcher work.

---

## How to use this file

When implementing a new behaviour:

1. **Pick the questions in §1–§8 that apply to your action.**
2. **Answer them in code or comments.** If the answer is "I'll
   verify before dispatching", write the verification call.
3. **Add a self-test or assertion** for the assumption you're making.
4. **If you can't answer a question, the bot will fail silently.**
   Stop and find the answer.

When debugging:

1. **The most common bug is "the click was dispatched but the engine
   didn't do what I expected".** Trace the click pixel: what was
   under the cursor at click time? Was the menu pre-check passing
   for the right reason?
2. **The second most common is "we're in state X but the FSM thinks
   we're in state Y".** Add a log at every `setState(...)` and walk
   the trace.
3. **The third most common is widget-hidden silent drift.** If the
   player is ending up in random spots, count widget clicks dispatched
   against player tile changes — they should not correlate.
