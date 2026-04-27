# API reference — sequence engine click pipeline

Low-level reference for the layer **underneath** the entity API. Read this
if you're:

- Adding a new click kind (e.g. minimap drag, drop-shift, alt-key combo).
- Debugging why a click didn't land (logs reference the phase numbers from
  this doc).
- Writing a routine that needs finer control than `Entity.interact(verb)`.

For most scripts, prefer the [Entity API](./entities.md) — it wraps all of
this.

---

## Layer map

```
   ┌─────────────────────────────────────────────────────────────────┐
   │  Script / state machine (e.g. ChickenCombatLoop)                │
   │      │                                                           │
   │      │ entities.nearestNpc("Chicken").attack()                   │
   │      ▼                                                           │
   │  net.runelite.client.sequence.entity.*  (high-level API)         │
   │      │                                                           │
   │      │ ActionRequest{ kind=CLICK_NPC, npcIndex, verb }           │
   │      ▼                                                           │
   │  HumanizedInputDispatcher.dispatch(req)                          │
   │      │                                                           │
   │      ├─ rotateCameraToward (skipped if target on-screen)         │
   │      ├─ resolveTargetPixel  (PixelResolver — engine click-shape) │
   │      ├─ moveCursorTo        (humanized path / WindMouse)         │
   │      ├─ settle              (180–400 ms beat)                    │
   │      ├─ hover-verify        (top of menu == verb on target?)     │
   │      └─ press   ┊   right-click + menu-row pick (fallback)       │
   │                                                                  │
   │  Engine reads: Tile, ItemLayer, NPC, Actor.getConvexHull,        │
   │  TileObject.getClickbox, Perspective.localToCanvas               │
   └─────────────────────────────────────────────────────────────────┘
```

---

## Engine APIs we hit

These are the RuneLite / OSRS APIs the dispatcher and resolver lean on.
Knowing what each one returns saves a lot of guessing.

### `TileObject.getClickbox()` → `Shape`

Engine-authoritative click hit-test for any `TileObject`: `GameObject`,
`WallObject`, `DecorativeObject`, `GroundObject`, **`ItemLayer`**. Returns
the on-canvas Shape that OSRS itself uses to decide whether a pixel "is a
click on this object".

For ground items, `Tile.getItemLayer().getClickbox()` is the union of every
item sprite in the pile. Right-click anywhere inside it ⇒ menu contains
every item's verb. **This is what `PixelResolver.resolveGroundItemPixel`
samples first.**

May be `null` for one frame after a fresh spawn (renderable not built yet).
Always have a fallback path.

### `Actor.getConvexHull()` → `Shape`

Engine-authoritative click hit-test for `Player` / `NPC`. The projected
model hull updates every frame, so it's already tracking when the actor
walks. Used by `PixelResolver.resolveNpc` with a tile-poly fallback for the
rare case where the hull isn't computed yet.

### `Perspective.getCanvasTilePoly(client, lp)` → `Polygon`

The on-canvas quad of a tile's floor. **Don't** use this as a click target —
its corners are floor edges that don't always carry interactables. It's
only useful as:

- An "is the tile on screen" test (returns `null` if off-screen).
- A bounding box for camera-visible-margin checks
  (`HumanizedInputDispatcher.isTileComfortablyVisible`).
- Last-resort sample area when nothing better is available.

### `Perspective.localToCanvas(client, point, plane)` → `Point`

Projects the *centre* of a `LocalPoint` (i.e. a tile centre) to canvas
pixels. Ground items render at this point, so it's a reliable click target
when the layer's clickbox isn't ready.

### `Perspective.getClickbox(client, wv, model, orientation, x, y, z)` → `Shape`

Lower-level model-to-canvas clickbox. RuneLite recommends `TileObject.getClickbox()`
when available; this is for cases where you have a raw `Model` (e.g. a
specific `Renderable` from an `ItemLayer.getBottom/Middle/Top` slot).
The doc strings warn it's `@ApiStatus.Internal` — fine to use, but treat as
a backup behind `TileObject.getClickbox()`.

### `WorldArea.canTravelInDirection(wv, dx, dy)` → `boolean`

Single-step walking validity check using the engine's standard collision
flag matrix (handles full-blocks, half-fences, wall flags, and the diagonal
corner cases). Used as the BFS step predicate inside
`TargetVisibility.isReachable` — that's the wall-attack filter.

### `WorldArea.hasLineOfSightTo(wv, other)` → `boolean`

Bresenham over projectile-LOS flags. **Not** the same as walking
reachability — half-fences pass projectile LOS but block walking, which is
why combat selection needs both checks.

### `Tile.getItemLayer()` → `ItemLayer`

`ItemLayer` extends `TileObject`, so it has `getClickbox()` and
`getCanvasTilePoly()` directly. Plus its own:

- `getHeight()` — local-space z offset where items render above the floor.
- `getBottom()` / `getMiddle()` / `getTop()` — up to three stacked
  `Renderable` slots (older items below, newest on top).

### `TileItem` (extends `Renderable`)

`getId()`, `getQuantity()`, `getOwnership()`, `getVisibleTime()`,
`getDespawnTime()`. Critical for chicken-loop loot: `getOwnership() == OWNERSHIP_SELF`
gates "is this drop mine" before we attempt a take.

`getModel()` may return `null` for a tick on fresh spawn — that's why the
ground-item resolver doesn't lean on per-item models, only on the
ItemLayer's union clickbox.

---

## `ActionRequest`

```java
ActionRequest req = ActionRequest.builder()
    .kind(ActionRequest.Kind.CLICK_GROUND_ITEM)
    .channel(ActionRequest.Channel.MOUSE)
    .tile(worldPoint)
    .itemId(ItemID.FEATHER)
    .verb("Take")
    .build();
dispatcher.dispatch(req);
```

The DTO every click goes through. Defined in
`net.runelite.client.sequence.internal.ActionRequest`.

### `Kind` — what to click

| Kind                | Required fields              | Verb behaviour                         |
|---------------------|------------------------------|----------------------------------------|
| `WALK`              | `tile`                       | always "Walk here"                     |
| `CLICK_TILE`        | `tile`                       | as `WALK`, no skip-if-already-here     |
| `CLICK_NPC`         | `npcIndex`                   | `verb` (default "Attack") — smart L/R  |
| `CLICK_GAME_OBJECT` | `tile`, `verb`               | required (e.g. "Open", "Climb-up")     |
| `CLICK_GROUND_ITEM` | `tile`, `itemId`             | `verb` (default "Take") — smart L/R    |
| `CLICK_WIDGET`      | `widgetId`                   | left-click on widget child             |
| `CLICK_INV_ITEM`    | `slot`, optional `verb`      | inventory slot; verb opens menu route  |
| `KEY`               | `keyCode`                    | tap                                    |

### `Channel` — how to deliver the input

- `MOUSE` — synthesised mouse events through `CanvasInput`. Used for all
  click kinds.
- `KEYBOARD` — for `KEY`.
- `CLIENT` — direct `MenuAction` invocation (no cursor / no humanization).
  Reserved for diagnostics / forced flows; do not use in normal scripts.

### `verb`

Case-insensitive, hyphen / whitespace tolerant via `VerbMatcher`. Pass the
human-readable verb you'd see in OSRS ("Take", "Attack", "Talk-to",
"Climb-up", "Bury"). The dispatcher hover-verifies against this string and
falls back to right-click menu lookup if needed.

`null` / blank → kind-specific default ("Take" for ground items, "Attack"
for NPCs, "Walk here" for walks).

---

## `HumanizedInputDispatcher`

Single-flight humanized click pipeline. One running click chain at a time;
new requests issued while busy are silently dropped (and logged
`dispatcher busy, dropping <kind>`).

### Entry points

```java
void dispatch(ActionRequest req);  // schedule a click chain
boolean isBusy();                  // true while a chain is running
@Nullable String lastErrorMessage();  // last failure reason; null on success
```

### Phase numbering

Logs reference phases by number — the comments inside each click handler
match these:

| Phase | Step                                                           |
|-------|----------------------------------------------------------------|
| 1     | Read target world tile + rough pre-aim                         |
| 2     | `rotateCameraToward(target)` — skipped if `isTileComfortablyVisible` |
| 3     | Long humanized cursor move toward pre-aim                      |
| 4     | Settle (180–400 ms — the "did I get the right one" beat)       |
| 5     | Re-resolve click pixel from FRESH engine state (model hull / item layer) |
| 6     | Small final-aim adjustment if pixel moved >4 px                |
| 7     | Hover-verify: top menu == `verb` on our target?                |
| 8     | yes → press immediately                                        |
| 9     | no → right-click → wait → click matching menu row              |

### Camera rotation specifics

`rotateCameraToward(target)`:

1. **Skips** if `isTileComfortablyVisible(target)` — i.e. target tile poly
   fully inside the viewport with a 40 px margin. Real players don't pan
   for things they already see.
2. Computes desired yaw from player→target world delta.
3. Adds **±150 yaw units (~26°) jitter** to the desired yaw, so the
   landing position is never exactly centred (perfect alignment is a
   bot-tell).
4. Streams the camera target in 25–40 ms steps over 500–1500 ms with
   smoothstep easing — looks like a hand drag, not a snap.

Don't call this from outside the dispatcher; it's part of every click
phase 2.

### Single-flight & error surfacing

```java
dispatch(req)
  → if busy.compareAndSet(false, true) succeeds:
      lastError = null
      worker thread runs handle(req)
      finally: busy = false
  → else:
      log "dispatcher busy, dropping <kind>"
      return  (no error set on the caller)
```

The "no error set on busy-drop" is intentional — the dispatcher's contract
is *single-flight* and it's the caller's job to gate on `isBusy()`. If you
re-dispatch while busy, expect silent drops.

Inside `handle(req)`, exceptions are caught and stored in `lastError`:

```java
String err = dispatcher.lastErrorMessage();
if (err != null) {
    log.info("dispatch failed: {}", err);
    // skip / retry / abort
}
```

Common error strings:

- `"item <id> no longer on tile <tile>"` — Phase 1 verified the item is
  gone before we even started.
- `"hover not on npc <idx> after final aim"` — Phase 7 saw a different
  NPC under the cursor (now followed by Phase 9 right-click flow).
- `"menu missing 'Take' on item <id> at <tile>"` — Phase 9 right-click
  opened a menu without the expected entry. Item probably moved layer or
  another player picked it up first.
- `"npc <idx> not on screen"` / `"npc <idx> disappeared during aim"` —
  Phase 1 / Phase 5 lost the target.

---

## `PixelResolver`

The "what pixel do I click?" layer. Always called from the client thread.

| Method                                  | Use                                                     |
|-----------------------------------------|---------------------------------------------------------|
| `resolveWalkTarget(WorldPoint)`         | Walking — main view OR minimap, biased away from actors |
| `resolveTilePixel(WorldPoint)`          | Player's-own-tile clicks (legacy loot fallback)         |
| `resolveGroundItemPixel(WorldPoint, id)`| **Ground items — primary path.** ItemLayer clickbox + jitter fallback |
| `resolveNpc(NPC)`                       | NPC click — convex hull primary, tile-poly fallback     |
| `resolveGameObject(GameObject)`         | Game objects — convex hull primary                      |
| `resolveWallObject(WallObject)`         | Wall objects — `getConvexHull()` / `getConvexHull2()`   |

### `resolveGroundItemPixel` strategy chain

The chain documented in [entities.md](./entities.md) lives here. Order:

1. `tile.getItemLayer().getClickbox()` — sample inside the engine's pile shape.
2. `Perspective.localToCanvas(scene, plane)` ± 10 px jitter — tile centre
   is where items render; jitter (±10 px) stays inside the typical
   24–40 px sprite footprint while breaking pixel-perfect repeats.
3. `resolveTilePixel` — random pixel inside `getCanvasTilePoly()`.

Each step that succeeds calls `record(point)` so subsequent clicks reject
candidates within `MIN_REPEAT_PX` (= 2) of recent picks. That prevents the
"same pixel twice in a row" tell.

### Recent-clicks ring

`recentClicks` (capacity 12) is shared across all resolver methods. Walking
clicks, NPC clicks, and item clicks all check it. Useful when you have
many similar actions on the same screen region.

---

## `TargetVisibility`

Lives in `plugins/recorder/combat/`, but it's a general-purpose visibility
filter. Returns `true` when *a real player could click on* this NPC right
now:

1. **Projectile LOS** — `WorldArea.hasLineOfSightTo`. Cheap, catches solid
   walls.
2. **Walking reachability** — BFS up to depth 8 using
   `WorldArea.canTravelInDirection`. Catches the half-fence case
   (Lumbridge chicken pen) where projectiles pass but feet don't.
3. **On-canvas** — `npc.getCanvasTilePoly()` non-null.
4. **Inside viewport** — pixel inside `client.getViewport*()` rect.
5. **Menu occlusion** — if `client.isMenuOpen()`, pixel must not be under
   the menu rectangle.

Each step short-circuits on the first miss. Use as a filter before passing
NPCs to the entity layer:

```java
List<NpcEntity> reachable = entities.npcsByName("Chicken").stream()
    .filter(e -> {
        NPC raw = e.npc();
        return raw != null && visibility.canSee(raw, self, wv);
    })
    .toList();
```

---

## Adding a new click kind

1. Add the enum value to `ActionRequest.Kind`.
2. Add a `case` in `HumanizedInputDispatcher.handle(req)`.
3. Implement the click handler — follow the phase numbering above. Always:
   - Pre-flight verify the target exists.
   - `rotateCameraToward(...)` (it self-skips when target is visible).
   - Pre-aim → settle → re-resolve → final-aim → hover-verify → press, OR
     fall through to right-click + menu-row pick.
4. Add a resolver method on `PixelResolver` for the new target shape, if
   the existing methods don't fit. Always sample inside an engine click-shape
   first; only fall back to tile / poly when the engine's shape is `null`.
5. Add an `Entity` subclass + a finder on `Entities`.
6. Update the table in [entities.md](./entities.md) → "Adding new entity types".

Don't bypass the dispatcher to "just send a quick click" — every shortcut
that skipped a phase has eventually shown up as a bot tell or a flaky
click. The phase numbers exist because each one fixes a class of bug.

---

## See also

- [`docs/entities.md`](./entities.md) — the high-level guide for scripts.
- [`docs/visibility.md`](./visibility.md) — every "can the player see
  this?" filter and the engine primitive behind each one (plane, LOS,
  reachability, roof flags, viewport, HUD widget tree, open menu).
- `runelite-client/.../sequence/dispatch/HumanizedInputDispatcher.java` —
  the canonical phase implementation. Read top-to-bottom when in doubt.
- `runelite-client/.../sequence/dispatch/PixelResolver.java` — pixel
  selection strategies, with the `resolveGroundItemPixel` strategy chain.
- `runelite-client/.../plugins/recorder/combat/TargetVisibility.java` —
  the visibility / reachability filter, including the BFS implementation.
