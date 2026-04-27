# OSRS mining routine — brainstorm

Date: 2026-04-26
Owner: Mantas (lilbee)
Status: design only — implementation tracked under `recorder/mining/`.

## What the user wants

A `MiningLoop` orchestrator that locks onto one rock at a time, swings
a pickaxe, detects when the rock depletes, picks the next live rock in
a small candidate set, and never clicks while the player is already
mining. Power-mining first (drop ores in place when inventory fills);
banking is a deferred follow-up.

Hard constraint — same rules as the chicken loop:

1. No `client.menuAction(...)`. Every click is a synthesized
   `MouseEvent` on the canvas through `HumanizedInputDispatcher`.
2. All scene/perspective reads on the client thread via
   `clientThread.invoke` (mirror `onClient(...)` in
   `HumanizedInputDispatcher.java`).
3. No menuAction shortcuts — the click resolves through the engine's
   normal menu hit-test using `CLICK_GAME_OBJECT` with `verb="Mine"`.

## Game research summary (cited)

Sources: OSRS Wiki [Mining](https://oldschool.runescape.wiki/w/Mining),
[Pickaxe](https://oldschool.runescape.wiki/w/Pickaxe),
[Free-to-play Mining training](https://oldschool.runescape.wiki/w/Free-to-play_Mining_training),
[Copper rocks](https://oldschool.runescape.wiki/w/Copper_rocks),
[Tin rocks](https://oldschool.runescape.wiki/w/Tin_rocks),
[Iron rocks](https://oldschool.runescape.wiki/w/Iron_rocks),
[OpenOSRS AnimationID.java](https://github.com/JourneyDeprecated/OpenOSRS/blob/master/runelite-api/src/main/java/net/runelite/api/AnimationID.java).

- **Tick**: 0.6s. Mining "rolls" every N ticks based on pickaxe — bronze 8, iron 7,
  steel 6, mithril/black 5, adamant 4, rune 3, dragon 2.83. Each roll has a
  level-and-rock dependent success chance; ore drops on success and the rock
  depletes.
- **Respawns**: copper/tin 2.4s, iron 5.4s (Mining Guild halves), coal 30s,
  mithril 2min, adamant 4min, runite 12min.
- **F2P ores + level**: clay 1, copper/tin 1, iron 15, silver 20, gold 40,
  mithril 55, adamantite 70, runite 85. Members own everything past clay/copper/tin/iron
  for *fast* training but the rocks themselves exist in F2P at Mining Guild + Dwarven Mine.
- **F2P pickaxes**: bronze 1, iron 1, steel 6, black 11, mithril 21, adamant 31,
  rune 41. Dragon is P2P.
- **Mining animation IDs** (already in `runelite-api/.../AnimationID.java`):
  `MINING_BRONZE_PICKAXE=625`, `IRON=626`, `STEEL=627`, `BLACK=3873`, `MITHRIL=629`,
  `ADAMANT=628`, `RUNE=624`, `DRAGON=7139`, plus several special variants. The
  `localPlayer.getAnimation()` reflects whichever pickaxe is wielded.
- **Rocks are GameObjects** on the tile. Their `ObjectComposition.getActions()`
  list contains "Mine" while live; on depletion the engine swaps the ID to a
  spent variant whose actions list lacks "Mine". *We do not need to memorise
  exact full/depleted ID pairs* — we ask the composition each tick.
- **Inventory**: 28 slots. `client.getItemContainer(InventoryID.INV).count()` ≥ 28
  is "full". (`InventoryID.INV` lives in `net.runelite.api.gameval.InventoryID`,
  matches every existing recorder reference.)

## State machine

```
       start()
IDLE ─────────► SELECTING
                    │
                    │ inventory full?
                    ├───────► INVENTORY_FULL ──► (PowerMine drops, BankDeposit walks)
                    │
                    │ no live rock in candidates within range
                    ├───────► ABORTED ──► IDLE
                    │
                    │ live rock found  →  CLICK_GAME_OBJECT verb="Mine"
                    ▼
              SWINGING                  ◄── stay here while animation matches
                    │ poll per tick:
                    │  rock object id → depleted variant?  ──► DEPLETED ──┐
                    │  inventory item count ≥ 28?           ──► INVENTORY_FULL
                    │  animation dropped >2 ticks AND rock still alive ──► SELECTING (re-click same)
                    │  ore tally bump → keep swinging
                    │  stop signal    ──► IDLE
                    ▼
                DEPLETED ─── release lock ──► SELECTING ◄────────────────┘
```

`SELECTING → SWINGING` is the **only** transition that issues an
attack click. While `SWINGING`, the loop polls and **does not click**
unless the animation drops (re-click same rock) or the rock depletes
(release lock + reselect).

That last bit is the bot-tell killer. Real players don't spam clicks
while mid-swing; if you watch human gameplay, you see one click then
silence until the swing ends.

## Detection plumbing

### Animation as the source of truth

```
boolean isMining(Player self) {
   int anim = self == null ? -1 : self.getAnimation();
   return MINING_ANIMATIONS.contains(anim);   // bronze..crystal pickaxe set
}
```

While `isMining()` is true the loop sleeps. Breaking it requires either
the rock depleting or the player being moved off the tile.

### Depletion via composition

```
boolean isDepleted(int objectId, Client client) {
   ObjectComposition def = client.getObjectDefinition(objectId);
   if (def == null) return true;   // gone from scene
   String[] actions = def.getActions();
   if (actions == null) return true;
   for (String a : actions) if ("Mine".equalsIgnoreCase(a)) return false;
   return true;   // composition no longer offers "Mine" — depleted variant
}
```

This is the same pattern `TransportResolver.matchedAction` uses for
doors/gates (object id flips, composition swaps). We don't need a
hard-coded full↔depleted ID pair table — the verb presence tells us.

### Inventory full

```
boolean inventoryFull(Client client) {
   ItemContainer c = client.getItemContainer(InventoryID.INV);   // gameval id 93
   if (c == null) return false;
   return c.count() >= 28;
}
```

`count()` returns the number of slots in use, not the stack-summed total.
Coins-only stacks at slot 0 still count once. Standard 28-slot ceiling.

### Rock locking

`MiningTarget` is a record — `int gameObjectId, WorldPoint tile, OreType
oreType, int lockTick`. We do **not** keep a `GameObject` reference,
because the engine swaps the underlying object on depletion and the
old reference becomes stale; we re-resolve from the tile every poll.

## Multi-rock workflows

User configures 2–3 candidate tiles (panel button "Add rock here"
captures `getLocalPlayer().getWorldLocation()` adjusted for the rock
the user *clicked* last — same idiom as the route's "Add current"
button). On each `SELECTING`, `RockSelector` picks:

- not in the depleted-this-tick set (we keep a tiny ring buffer of
  recently-depleted rocks so we don't re-click a corpse before the
  engine's compose-swap propagates),
- on the player's plane,
- closest by Chebyshev distance to player,
- ties broken by the order they were configured (deterministic).

Cluster sweeping naturally rotates: rock A depletes → pick B → B
depletes by the time we look back, A is alive → pick A. So one
candidate is enough to function but two tend to keep the player swinging
without idle ticks.

## Variance

Real players don't deterministically pick the closest rock every time.
The selector exposes a small jitter:

- 20% chance: pick the second-closest rock instead of the closest.
- After 3 successful mines, randomly insert a 1–3 tick idle pause before
  the next `SELECTING`. Looks like a human glancing at chat.
- Pickaxe animation id has multiple variants (`BRONZE` vs `MOTHERLODE_BRONZE`
  vs `DRAGON_PICKAXE_UPGRADED`). We accept the *full set* in
  `MINING_ANIMATIONS` rather than the one matching the wielded pickaxe,
  so a user swap mid-loop doesn't fool the tracker.

The `HumanizedInputDispatcher` already adds per-click pixel jitter,
post-click parking, and humanized cursor paths — we don't reimplement
any of that, we just dispatch and let it run.

## Inventory-full strategy

Behind a `BankingStrategy` interface so we can swap `PowerMine` and
`BankDeposit` without touching the loop:

```
interface BankingStrategy {
   /** Run when inventory becomes full. Returns when done; loop returns
    *  to SELECTING. May throw InterruptedException if stop() requested. */
   void empty(MiningLoopContext ctx) throws InterruptedException;
}
```

### PowerMine (first build)

Iterate inventory slots. For each slot whose item id matches one of our
ore types, dispatch `CLICK_INV_ITEM` with `verb="Drop"`. The dispatcher's
`gameObjectClick` already implements right-click → menu → verb selection
via `selectMenuVerb`; we re-use that — refactor on first need to extract
a `widgetVerbClick(...)` helper, but for now just dispatch the existing
kind.

Drop loop ends when the next inventory snapshot has count < 28 (we don't
trust slot iteration alone — a stack might exist).

### BankDeposit (deferred)

Stub it. The chicken routine is also building a bank flow; we'll borrow
that when it lands. For now the loop just logs "bank strategy not
implemented; aborting" and returns to IDLE.

## Wiring

- `MiningLoop` constructed in `RecorderPlugin.startUp()` alongside the
  combat loop, given the dispatcher, client, clientThread, and a status
  callback.
- New "Mining" panel section in `RecorderPanel.buildMining()` — Start /
  Stop / status label / kill-counter ("ores mined: N") / "Add rock here"
  button. Use unique field names to avoid colliding with the combat
  agent's panel section.
- Daemon thread per loop. Volatile + `AtomicReference<MiningTarget>` for
  stop signalling.

## Edge cases up front

- **Depleted-rock-prediction race.** Engine swaps the comp on the same
  tick the ore lands in inventory. Our poller runs at ~120ms which is
  faster than a tick, but if the player is lagging we might issue a
  re-click before the comp updates. Mitigation: in `SWINGING`, only
  consider re-clicking when `animation has been dropped for >=2 ticks`
  *and* the comp says alive.
- **Another player pumps your rock.** Their click resolves first, our
  swing produces no XP, animation drops, rock depletes — we'll see
  `DEPLETED` and reselect. Suboptimal but correct.
- **Out-of-scene rocks.** If the player walked away and the candidate
  tile is no longer in the scene, `TransportResolver.tileAt()` returns
  null. We treat that as "not selectable this tick" and try the next
  candidate; if all are out of scene, ABORTED.

## Decided

- Package: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/mining/`.
- Classes (built now): `MiningTarget` (record), `OreType` (enum), `RockSelector`,
  `MiningStateTracker`, `MiningLoop`, `BankingStrategy` (interface) +
  `PowerMineStrategy`. `BankDepositStrategy` stubbed.
- State machine: `IDLE / SELECTING / SWINGING / DEPLETED / INVENTORY_FULL / ABORTED`.
- Click kind: `ActionRequest.Kind.CLICK_GAME_OBJECT`, `verb="Mine"`. Tile
  comes from `MiningTarget.tile()`; the dispatcher's `TransportResolver`
  handles object resolution.
- Animation set: union of all `MINING_*_PICKAXE` ids in
  `runelite-api AnimationID.java` (bronze, iron, steel, black, mithril,
  adamant, rune, gilded, dragon, dragon-upgraded, dragon-or, infernal,
  3a, crystal). Motherlode variants too — same skill, different scene.
- Depletion check: `ObjectComposition.getActions()` no longer contains
  "Mine".
- Inventory full: `client.getItemContainer(InventoryID.INV).count() >= 28`.
- Verb match for "Drop" (PowerMine): re-use the dispatcher's existing
  `selectMenuVerb` flow via `CLICK_INV_ITEM` with verb override (will
  add small dispatcher hook in follow-up if not yet present — defer
  until we hit it).
- All scene reads via `onClient(Supplier<T>)` mirroring the dispatcher
  helper. Loop body runs on a daemon thread, polling at ~120ms, with a
  hard cap of N ore mined or stop signal.
- Tests: `RockSelectorTest`, `MiningStateTrackerTest`, `MiningLoopTest`,
  `OreTypeTest`. Each ≤50 lines.
- Out of scope this iteration: bank deposit flow, multi-tier ore picking
  per location, depleted-rock-prediction (predict respawn instead of
  poll), pickaxe-in-toolbelt detection.
