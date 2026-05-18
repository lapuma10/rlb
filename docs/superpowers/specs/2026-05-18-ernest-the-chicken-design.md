# Ernest the Chicken — quest script design

**Date:** 2026-05-18
**Status:** approved, ready to implement
**Scope:** single quest script + minimal reusable step-type library under `recorder/quest/`

---

## Goal

Run Ernest the Chicken to completion, unattended, from any starting state — including resuming mid-quest after a crash or disconnect. Use the existing sequence engine; no new framework, no test suite, no UI panel.

Success = manual in-game run completes the quest and stops the script cleanly.

## Reference

The behavioural source of truth is the OSBot script shared in the brainstorming conversation (in-chat, not committed). Item ids, tile coords, dialog options, and the lever-puzzle pull-sequence are all 1:1 with current OSRS — the OSBot script is a working reference, translated as-is.

When the translated script behaves differently from the OSBot original, the OSBot original is correct; investigate the translation, don't "fix" the reference.

## Preflight — required starting state

The script does NOT route to Ernest from anywhere on the world map. It assumes the player is at one of two known starting points and refuses to start otherwise.

| Starting state                          | Action                                                                                  |
|-----------------------------------------|-----------------------------------------------------------------------------------------|
| Already at Draynor bank (within trail end zone, ~(3092, 3243, p=0)) | Skip preflight, proceed to milestone 1.                          |
| At Lumbridge bank, top floor (p=2, near (3208, 3219)) | Replay recorded trail `lumbridge_bank_to_draynor` → ends at Draynor bank → proceed.   |
| Anywhere else                           | Refuse to start. Log the player's current tile and exit cleanly. No "best-effort" routing. |

Implemented as a single `PreflightStep` (or two ordered steps: `EnsureStartingAreaStep` + `WalkTrailStep`) that runs before milestone 1. The preflight's `check()` returns SUCCEEDED once the player is inside the Draynor bank end-zone, regardless of which path got them there — so a mid-trail crash naturally resumes from wherever the player ended up (nav v2 from there → Veronica covers the remaining short hop in milestone 1).

## Quest flow (milestones)

The script walks through these in order. Each milestone may be skipped if its outcome is already true at script start (see [resumability](#resumability)).

1. Walk from Draynor bank → Veronica's area, talk to Veronica (start quest if not started).
2. Walk to Draynor Manor, climb to top floor, talk to Professor Oddenstein.
3. Pick up Fish food from the middle floor.
4. Pick up Poison from the ground-floor kitchen-side room (two doors on the way).
5. Search the living-room bookcase (opens hidden door).
6. Combine Poison + Fish food → Poisoned fish food.
7. Run the basement lever puzzle to get the Oil can.
8. Pick up the Spade, walk around the manor to the compost heap, search it for the Key.
9. Walk to the fountain, use Poisoned fish food on it, search it for the Pressure gauge.
10. Walk to the skeleton room (back of the manor), grab the Rubber tube.
11. Return to Professor Oddenstein, hand in the three parts, finish quest dialog, close completion widget, stop script.

## Architecture

**Reuse what's already in the sequence engine.** Inspection of `sequence/composite/` and `sequence/activities/` shows two patterns we can leverage rather than reinvent:

- `composite/LinearSequence` — ordered list of `Step`s with anchor support and a fluent `.then(step)` API. **This IS our quest-runner.** No `QuestSequence` class needed.
- `activities/WalkStep` — already wraps a `WorldPoint` + arrival radius, plugs into the engine. **This IS our WalkToStep.** No new walk class needed.
- Existing single steps (`HaveAtLeastInInventoryStep`, `CloseBankStep`, etc.) `implements Step` directly — no abstract `QuestStep` base class. We follow the same pattern.

New files under `runelite-client/src/main/java/net/runelite/client/plugins/recorder/quest/`:

```
steps/
  TalkToNpcStep.java            — wraps NpcInteraction.completeDialogue(...)
  InteractWithObjectStep.java   — generic verb-on-object: Pull lever, Open door, Search bookcase, Climb ladder, Use fountain
  TakeGroundItemStep.java       — closest("name") + "Take", poll inventory
  CombineItemsStep.java         — use inv item A on inv item B, poll for combined item
  UseItemOnObjectStep.java      — use inv item on world object, poll completion signal
  ReplayTrailStep.java          — preflight helper: replay a recorded trail by name (wraps TrailWalker)
ErnestTheChicken.java           — builds a LinearSequence populated with the recipe; owns the lever-puzzle private method
```

Six new step types (one is the preflight trail-replay helper), one quest class. No bases, no runner, no framework — everything else is provided by the existing engine.

## Step types & completion signals

Each step's `check()` returns SUCCEEDED based on game state — not on "did I dispatch the click."

| Step                  | Completion signal                                                                  |
|-----------------------|------------------------------------------------------------------------------------|
| `TalkToNpcStep`       | dialog ended AND (quest varbit advanced OR caller-supplied predicate true)         |
| `WalkStep` (existing) | player within `arrivalRadius` of the `WorldPoint` target — engine handles it       |
| `InteractWithObjectStep` | per-instance: animation played, varbit changed, or caller-supplied predicate true |
| `TakeGroundItemStep`  | inventory contains item id                                                          |
| `CombineItemsStep`    | inventory contains combined item AND missing one input                              |
| `UseItemOnObjectStep` | per-instance: varbit changed, item consumed, or follow-up dialog opened             |
| `ReplayTrailStep`     | player position inside the trail's recorded end-tile zone                          |

Where "per-instance" appears, the caller passes a small `Predicate<WorldSnapshot>` at construction. Steps stay generic; quest-specific completion logic lives in the recipe.

## Sequencing — provided by `LinearSequence`

`ErnestTheChicken` constructs a `LinearSequence` and adds child steps via `.then(step)` in recipe order. The engine drives child-by-child advancement, `Completion.Failed` propagation, and frame management via `LinearSequenceFrame`. We don't write this — it already works (used by other in-tree scripts).

Inside `ErnestTheChicken`, the constructor is essentially:

```java
LinearSequence seq = new LinearSequence("ErnestTheChicken")
    .then(new PreflightStep(...))            // ensure Draynor bank or run trail
    .then(new TalkToNpcStep("Veronica", OPTIONS))
    .then(new WalkStep(DRAYNOR_MANOR_DOOR))
    .then(new InteractWithObjectStep("Staircase", "Climb-up"))
    // ... etc
    ;
```

## Resumability

No save file. The game IS the state.

On script start, the runner walks the recipe top-to-bottom and advances past every step whose `check()` returns SUCCEEDED instantly. First step that returns RUNNING is where execution resumes. Same `check()` does double duty — in-flight poll AND resume-time skip check.

**Anchors per step:**
- Quest progress varbit where it advances (talk-to-NPC milestones).
- Inventory contents (item-pickup / item-combine steps).
- Player position (walk steps).
- Custom predicate for steps the above can't gate cleanly.

**Lever puzzle weak spot.** The puzzle is gated by `inventory.contains("Oil can")`. If the bot crashes mid-puzzle, the outer step's `check()` returns RUNNING (no oil can yet), and `onStart()` re-runs the canonical pull-sequence from the beginning. Lever toggle-states may be wrong mid-puzzle, but re-running the canonical sequence from any state should end up correct. If that turns out not to be true in-game, fall back to per-lever varbit reads as a follow-up.

## Lever puzzle scoping

The lever puzzle is a 14-pull + 8-door-open + 1-ladder subroutine. It's quest-specific and not worth abstracting yet. Lives as a private method on `ErnestTheChicken`, wrapped by a single outer `InteractWithObjectStep` (or a small bespoke `Step` subclass on the quest) whose `onStart()` runs the canonical sequence and whose `check()` polls inventory for the Oil can.

## Out of scope

- **No unit tests.** Verification is manual in-game.
- **No persistence layer.** Game state is the save file.
- **No quest-runner UI panel.** Started like any other script via `RecorderPanel`.
- **No framework abstractions for hypothetical second quests.** Add reusable pieces when a second quest actually needs them. Extract from Ernest after the fact, not before.
- **No registration / executor framework.** `QuestSequence` is just a List + index; each step owns its own `onFailure`.

## Things to verify during implementation

- Ernest the Chicken's exact progress varbit id (for cleanest `TalkToNpcStep` anchoring).
- `ItemID` constants for: Fish food, Poison, Poisoned fish food, Spade, Key, Oil can, Pressure gauge, Rubber tube.
- Door object ids 131, 137, 138, 140-145 referenced in the OSBot puzzle — confirm they still map to RuneLite's `ObjectID` / `NullObjectID` constants.
- Completion widget `(153, 17)` — `InterfaceID.QUEST_COMPLETE_*` constants in modern RuneLite.
