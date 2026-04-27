# Lumbridge chicken routine — plan

Date: 2026-04-26
Owner: Mantas (lilbee)

## What the user wants

Build an end-to-end activity that runs autonomously and looks human while doing it.

**The activity:** kill chickens at the Lumbridge chicken pen.

1. Walk to Lumbridge bank.
2. Gear up (withdraw what's needed for chickens).
3. Walk to the chicken pen in Lumbridge.
4. Kill chickens.
5. Pick up feathers and raw chicken.
6. When the inventory is full, walk back to the Lumbridge bank.
7. Bank.
8. Rinse and repeat for X amount of time.

**Hard constraint — must not look like a bot.** The user described the
walking style explicitly:

- Don't walk the same tile/path every time. Use a corridor (e.g., a
  ~5-tile-wide gap of valid tiles) and pick within it.
- Don't shortest-path. Click whatever a human would consider convenient,
  not the optimal next tile.
- Don't always click the destination. Sometimes humans click ahead, then
  click again as they get closer.
- Variable cadence:
    - Sometimes one click and wait.
    - Sometimes a double click.
    - Sometimes 3 rapid clicks in succession while already moving.
    - Sometimes a pause longer than usual.
- The recorder plugin captures all of this from real human play; the
  agent should learn from those traces, not from hand-coded paths.

## What I (Claude) am recommending

**De-risk the dispatch pipeline before building humanization.** All the
corridor sampling and cadence work is worthless if the dispatcher can't
reliably drive a single minimap click. So step 3 below is a litmus test
that has to pass before we build steps 5+.

### Hard constraint on dispatch

All agent input must be **real synthesized mouse and keyboard events**,
not engine-level menu calls. The existing `DirectInputDispatcher` calls
`client.menuAction(...)` directly — that's "magic instant click" and is
explicitly off-limits for humanized play. It stays for tests only.

The agent talks to the game by posting `MouseEvent`/`KeyEvent` objects
to the AWT canvas (`client.getCanvas().dispatchEvent(...)`). RuneLite's
own listeners can't tell synthesized events from human ones; the engine
resolves clicks through the same path. Our recorder captures the
agent's actions identically to a human's (useful as a self-test).

Every click is preceded by a humanized cursor path (Bezier / WindMouse
style — variable speed, jitter, no teleport). Every key press includes
realistic hold-duration. No dispatch path skips the canvas.

This gives us `InputMode.HUMANIZED` (the enum already has the slot;
no implementation existed before this work).

### Order of work

1. **Rebuild the client** with the recorder fixes already coded —
   notably the minimap-walk capture (`MenuAction.SET_HEADING` →
   `world_click entityKind="minimap_walk"`) and the per-tick destination
   tracker (catches silent left-click minimap walks via
   `getLocalDestinationLocation`).

   **1b. (Parallel — does not need a recording)**
   Build the humanized input layer end-to-end so we can smoke-test it
   against hand-supplied coords *while* the user is recording the
   chicken loop:
   - `WindMouse` cursor pathing (Bezier with jitter + speed envelope).
   - `CanvasInput` low-level event poster (`MouseEvent`, `KeyEvent` →
     `canvas.dispatchEvent`).
   - `HumanizedInputDispatcher implements InputDispatcher` — handles
     all `ActionRequest.Kind` values via canvas events.
   - `PixelResolver` — given a target descriptor (world tile, NPC
     index, widget id+slot, etc.), resolve the current screen pixel:
       - World tile in scene → `Perspective.localToCanvas`
       - World tile out of scene → `Perspective.localToMinimap`
       - NPC → `npc.getCanvasTilePoly().getBounds()` centre + jitter
       - Widget → `widget.getCanvasLocation()` + slot offset
   - Test panel section: paste a list of `WorldPoint`s, hit Go, watch
     the cursor walk the route with real mouse moves. User can paste
     their old hardcoded GE-Lumbridge coords as a smoke test.

2. **User records one full chicken run, manually.** Walk to bank →
   withdraw → walk to pen → kill 28 chickens → loot → walk back → bank.
   Plays naturally. This becomes both the baseline data and the test
   case for replay.

3. **Add `WALK_MINIMAP` action + verbatim replayer.** The replayer
   reads the user's recording and fires every click with the original
   timing. No humanization, no corridor sampling, no decision logic —
   just "do exactly what the human did". ~1-2 hours.

4. **User hits Play and watches the agent run their own recording.**
   This validates that every click type the user used (ground walk,
   minimap walk, NPC attack, ground item pickup, bank booth, deposit
   slots) actually drives the character through `client.menuAction`.
   If anything fails, we find out before we've written a single line of
   corridor logic.

5. **Humanization, layered on top of the verbatim replayer:**
    - Replace fixed-tile clicks with corridor-sampled tiles.
    - Replace fixed cadence with sampled cadence (1×, 2×, 3× click
      patterns + occasional long pauses).
    - Pixel-level jitter inside the destination tile's bbox.
   
   All of this is purely additive to step 3 — same dispatcher, just a
   different "next click" function.

6. **Multi-recording distillation.** Once the user has 5+ runs of the
   chicken activity, an offline tool reads them all and emits:
    - `corridor.json` — tile heat-map per (from-anchor, to-anchor).
    - `cadence.json` — click-timing distribution, bucketed by remaining
      distance to the click target.
    - `npc_anchors.json` — where the user stood when attacking each NPC
      type.

7. **Activity spec + state machine.** The chicken routine encoded as
   `activity.json` with phases (`walk_to`, `bank_op`, `combat_loop`,
   `loot_loop`) and a planner that switches phases on inventory state,
   HP, and time elapsed. Loops until N minutes elapsed.

### What's missing in the recorder for the later steps

- **Ground-item snapshot at click time.** Today we capture the click
  but not what items existed near the player. Loot phases need this —
  add a `ground_items` snapshot similar to `nearby` for NPCs.
- **Anchor markers.** `walk_to(lumbridge_bank)` needs to know where
  the bank is. Either hardcode anchors or use the marker hotkey to
  tag arrival ("at bank") in each recording so distillation can derive
  the anchor coords.

These are needed for steps 6-7, not for steps 1-4.

## Status

- Step 1: rebuilding now.
- Step 2: pending (user action — record the activity).
- Step 3+: queued.
