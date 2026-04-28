# Cooking Script — Design

## Original prompt

> Look into player animating. How would you write a cooking script? brainstorm
> with the skill. see if you can figure out and find out how cooking works. I
> want a cooking script that works in lumbridge castle at p2 in the bank, there
> are logs that spawn there. I want you to use tinderbox and take out selected
> food from the bank (select in the script). then it should light a fire on the
> logs with a tinderbox(rightclick logs ont he ground select light) or click
> tinderbox with use(left click) then left click one of the logs. Then left
> click raw food selected, then click on the fire thats burning and in the
> dialogue select cook all(see cd .. starter script repo for hwo to dismiss
> level up dialogue, how to know when youre done cooking, how to know that your
> fire has died and u need to light a new one etc.) How to use the dialogue to
> select cook all. How to bank, rebank. BUUUT we need to use our runelite
> engines api for banking (we have none for cooking yet, or lighting a fire)
> also dont hardcode it. we should add more places to cook. But we start with
> lumby p2 bank(theres coords for it in the repo were in rn). Build it in a
> worktree so we wouldnt disturb the other stuff, do it based on main branch.
> us ebrainstorming to improve and ensure that its propper.and working, send
> out a subagent to look into rs wiki to see if we find something usefull. DO
> NOT assume anything, ensure to use APi, check api, use correct calls,
> animations, widget ids etc. the starter script should have correct widget
> ids. Go plan it out, think through it, qc it, then go implement it. DO NOT
> STOP until done. THEN Send out 2 qc suabgents to qc independetnly. also note
> down my original prompt here in the script file youll write this in. Keep it
> generic for us to be able to use different foods, and fires/ranges, as there
> are different places to cook, some places can be used with logs, others have
> ranges(range) etc.
>
> _follow-up:_ a gotcah is that you have to have whats needed in your inventory
> and in the bank, the scrolling, looking for items etc. and if you dont find
> it, the bot shouldnt crash right? so ensur ebank is open, were looking for
> it, then we close it etc.

## Goal

A generic cooking script that:

1. Cooks raw food at a configurable location, starting at Lumbridge Castle
   plane-2 bank.
2. Supports two heat-source kinds — **fire from logs** (tinderbox + logs on
   ground) and **range** (fixed cooking range game object).
3. Banks → withdraws raw food (and tinderbox/logs if needed) → walks to cooking
   spot → lights/finds fire → cooks all → rebanks.
4. Survives interruptions: level-up popup, fire dying mid-cook, missing items
   in the bank, dispatcher errors. Never crashes — always resolves to a clean
   state (close bank, halt cleanly, surface a status).
5. Reuses existing engine APIs for banking, walking, and clicking. Cooking and
   firemaking are new primitives we add.

## Locations (initial)

| Location              | Plane | Source kind     | Notes                            |
|-----------------------|-------|-----------------|----------------------------------|
| `LUMBRIDGE_CASTLE_P2` | 2     | FIRE_FROM_LOGS  | Logs spawn near banker; same room|

The location registry is open for additions; the script does not hard-code
coordinates beyond that table.

## Architecture

```
┌─────────────────────────────┐        ┌──────────────────────┐
│ CookingScript (FSM)         │        │ panel: Cooking tab   │
│  IDLE                       │◀──────│  Start/Stop, food    │
│  BANKING                    │        │  selector, location  │
│  WALK_TO_COOK               │        │  selector, status    │
│  LIGHTING_FIRE              │        └──────────────────────┘
│  COOKING                    │
│  WALK_TO_BANK               │        Reuses:
│  ABORTED                    │          UniversalWalker
└─┬───────────────────────────┘          BankInteraction (extended)
  │ uses                                  HumanizedInputDispatcher
  ▼
┌─────────────────────────────┐         New primitives:
│ CookingInteraction          │           CookingInteraction
│  - dismissLevelUp           │           CookingLocation / Locations
│  - selectCookAll            │           BankInteraction.withdraw* additions
│  - cookOnHeatSource         │
│  - lightLogsOnGround        │
│  - findHeatSource           │
│  - findGroundLogs           │
│  - isCooking / isFiremaking │
└─────────────────────────────┘
```

### Threading model

Same as `LumbridgeBankPenScript` / `ChickenFarmV2Script`: one daemon worker
loop, every read of client state hopped through `ClientThread.invokeLater`,
clicks dispatched via `HumanizedInputDispatcher`. The dispatcher serialises
itself; the worker thread paces ticks with `Thread.sleep`.

## Engine IDs (verified in `runelite-api`)

- `AnimationID.COOKING_FIRE` = 897 — cooking on a fire.
- `AnimationID.COOKING_RANGE` = 896 — cooking on a range.
- `AnimationID.FIREMAKING` = 733 — lighting a fire.
- `InterfaceID.SKILLMULTI` = 270 — "How many would you like to cook?" interface.
  - Children: `_1`, `_5`, `_10`, `OTHER`, `X`, `ALL` (id `0x010e_000c`).
- `InterfaceID.LEVELUP_DISPLAY` = 233 — level-up popup. Dismiss with Space.
- `InterfaceID.Bankmain.UNIVERSE` — bank widget root (re-used).
- `ItemID.TINDERBOX` = 590, `ItemID.LOGS` = 1511, raw fish IDs as table below.
- `ObjectID.RANGE` = 2859, `ObjectID.FIRE` = 3769 (one variant; we match by
  composition name "Fire" since multiple object IDs render as fires).

### Raw food table (initial; extensible)

| Cook target  | Raw item     | Cooked item | Burnt (best-effort) |
|--------------|--------------|-------------|---------------------|
| Shrimps      | RAW_SHRIMPS  | SHRIMPS     | BURNT_SHRIMP        |
| Anchovies    | RAW_ANCHOVIES| ANCHOVIES   | BURNT_FISH(317)     |
| Sardine      | RAW_SARDINE  | SARDINE     | BURNT_FISH          |
| Herring      | RAW_HERRING  | HERRING     | BURNT_FISH          |
| Trout        | RAW_TROUT    | TROUT       | BURNT_FISH          |
| Salmon       | RAW_SALMON   | SALMON      | BURNT_FISH          |
| Lobster      | RAW_LOBSTER  | LOBSTER     | BURNT_LOBSTER       |
| Swordfish    | RAW_SWORDFISH| SWORDFISH   | BURNT_SWORDFISH     |
| Shark        | RAW_SHARK    | SHARK       | BURNT_SHARK         |

User picks the raw item from the panel; everything else (cooked detection,
burnt counter) keys off the table.

## State machine

### IDLE → BANKING / WALK_TO_COOK

`start()` reads the player position + inventory:

- At bank with no raw food: BANKING (need to withdraw).
- At cook spot with raw food: WALK_TO_COOK (skip to cooking; no walk).
- Mid-route: WALK_TO_BANK if inventory has cooked food (rebank), else
  WALK_TO_COOK.

### BANKING

Paced at ≥ 2s between dispatches (mirrors `ChickenFarmV2Script.tickBanking`).

```
1. Walk to bank area (UniversalWalker + bank-side PathSpec).
2. If bank not open → click random booth (BankInteraction.clickBankBoothRandom).
3. If bank open:
   a. If inventory has cooked or burnt food → depositAll (or depositAllExcept tinderbox).
   b. If we still need raw food / tinderbox / logs:
      - For each required item:
         - If bank has it: withdraw (all for raw food, 1 for tinderbox).
         - If bank does NOT have it: status="missing X", close bank, ABORT.
   c. Once inventory satisfied → close bank → WALK_TO_COOK.
```

Two new helpers on `BankInteraction`:

- `withdrawAll(int itemId)` — finds the bank slot for that item, dispatches
  CLICK_WIDGET on it with verb "Withdraw-All". Returns true on dispatch.
- `withdrawOne(int itemId)` — same but verb "Withdraw-1".
- `bankContains(int itemId)` — read-only check on `InventoryID.BANK`.
- `bankAmount(int itemId)` — quantity in bank.

These read `client.getItemContainer(InventoryID.BANK)` for slot lookup; the
withdraw click goes through the bank-item child widget at that slot.

**Missing-item handling.** If `bankContains(rawId)` is false (or quantity < 1)
or — for fire mode — if `bankContains(TINDERBOX)` is false and we don't have
one in inv, the script:

1. Sets status to `"missing X — aborting"`.
2. Closes the bank widget.
3. Transitions to ABORTED. The panel surfaces the status; the worker exits.

### WALK_TO_COOK / WALK_TO_BANK

Two `PathSpec` instances per location: `bankToCookPath` and `cookToBankPath`.
For Lumbridge P2 these are short single-area walks (a few tiles). The walker
returns ARRIVED → next state.

### LIGHTING_FIRE (FIRE_FROM_LOGS only)

```
1. If a Fire game object is within `FIRE_SEARCH_RADIUS` tiles on our plane
   AND we still have raw food → LIGHTING_FIRE done, → COOKING.
2. Find ground logs (TileItem, name="Logs" or id matches) on the cook plane.
3. If no logs visible: status="waiting for logs"; tick again. Do not crash;
   logs respawn on a few-second timer.
4. Right-click the ground logs tile, select "Light" via the dispatcher's
   right-click + menu-pick flow.
5. Wait for the player to start FIREMAKING animation, then for the Fire
   object to appear (poll up to ~10s).
6. → COOKING.
```

The "Light" verb on a ground item is not currently supported by the dispatcher
— we extend `HumanizedInputDispatcher.groundItemClick` to honor
`req.getVerb()`. Default remains "Take"; passing a non-blank verb does the
hover→right-click→menu-pick flow exactly like `gameObjectClick`.

### COOKING

```
Each tick:
1. If LEVELUP_DISPLAY visible → dispatcher.tapKey(VK_SPACE); skip rest.
2. If raw food count == 0 → → WALK_TO_BANK (success path).
3. If we are not currently animating COOKING_FIRE/COOKING_RANGE
   AND we are not currently animating FIREMAKING:
   a. (FIRE only) If no Fire on plane → → LIGHTING_FIRE.
   b. Find raw food slot → click it (CLICK_INV_ITEM, no verb = left-click).
   c. Find heat source (Fire or Range) → click it (CLICK_GAME_OBJECT, "Use").
   d. Wait one tick for SKILLMULTI to appear.
   e. Click `InterfaceID.Skillmulti.ALL` (CLICK_WIDGET) — the explicit
      "Cook All" button. Pressing space is also an option but the explicit
      click is unambiguous.
4. Otherwise (already cooking) → wait. Re-check next tick.
```

**Fire-died detection:** between cook batches, `findHeatSource` returns null on
fire mode → re-light. Within a batch, OSRS will silently stop the queued
"cook all" if the fire dies; we recover next tick by re-lighting and re-using
food on the new fire.

**Stuck timer:** if no inventory change for ≥ 30s, abort with status
`"stuck — no progress"`. (Defensive — should not happen in practice.)

### ABORTED

Worker exits, status surfaces in panel. Bank is closed if it was open.

## Robustness rules (per user follow-up)

1. Every bank-touching path opens the bank, does its work, then closes it.
   Closing happens inside a `finally`-equivalent: if any step fails, the
   abort transition first issues `bank.closeBank()` before exiting.
2. Missing items in bank → ABORT with descriptive status, never throw.
3. All client-state reads are wrapped in `onClient(...)` and tolerate null
   returns (no NPE on un-initialised scene).
4. The dispatcher's `lastErrorMessage()` is checked after each dispatch; any
   error surfaces to status and skips the action this tick (caller decides
   whether to retry).

## Wiring (RecorderPlugin → RecorderPanel)

- `RecorderPlugin.startUp` constructs `CookingScript` with its own
  `HumanizedInputDispatcher` (independent busy flag) and a `TransportResolver`.
- `RecorderPanel` adds a "Cooking" tab beside "Mining":
  - Location dropdown (currently one entry).
  - Raw-food dropdown (entries from the table).
  - Source kind dropdown — auto-set from location, but exposed for clarity.
  - Start / Stop buttons + status label + cooked / burnt counters.

## Out of scope (deferred)

- Progressive cooking (auto-step up food tier with level).
- Cooking guild (members 99 path) — easy to add as another `CookingLocation`.
- Wine cooking — different progress UI; separate animation.
- Burnt food banking — we just deposit them with the cooked food.

## Verification plan

After implementation, two QC subagents review independently:

1. API/IDs: do widget IDs / animation IDs / object IDs / verbs match the
   actual OSRS engine? Do menu-action invocations follow existing patterns?
2. Robustness: does the FSM handle every dispatcher / scene null path? Is the
   bank always closed before transition out of BANKING / on ABORT?
