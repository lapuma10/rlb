# Entity API — interacting with ground items and NPCs

Scripts that automate gameplay (chicken loop, mining loop, future routines)
should drive **all** ground-item and NPC clicks through this entity layer.
It wraps the engine's authoritative click-shape APIs (`TileObject.getClickbox()`
for items, `Actor.getConvexHull()` for NPCs) and routes presses through the
humanized dispatcher, so a script never has to decide between left-click and
right-click, never has to compute screen pixels, and never has to babysit
camera state.

If you find yourself reading `TileItem`, `NPC`, `Perspective`, or
`HumanizedInputDispatcher` directly, stop — wrap it in an entity instead.

---

## TL;DR

```java
@Inject Entities entities;
@Inject HumanizedInputDispatcher dispatcher;

// NPC: smart left- or right-click an "Attack"
entities.nearestNpc("Chicken").ifPresent(NpcEntity::attack);

// Ground item: take a feather from a known kill tile
entities.groundItem(ItemID.FEATHER, killTile).ifPresent(GroundItemEntity::take);

// Wait for the click chain to finish before issuing the next one
while (dispatcher.isBusy()) Thread.sleep(60);
```

That's the whole pattern. The rest of this doc is *why* it works.

---

## Package layout

```
net.runelite.client.sequence.entity
├── Entity.java               # interface every entity implements
├── GroundItemEntity.java     # ground-item drop on a specific tile
├── NpcEntity.java            # NPC by engine index
└── Entities.java             # finder / factory (scripts inject this)
```

`Entities` is the only thing you instantiate — usually via Guice
(`@Inject Entities entities`). It already holds the `Client` and the
`HumanizedInputDispatcher`; every entity it returns is pre-wired.

---

## The Entity contract

```java
public interface Entity {
    boolean exists();              // engine record still in scene?
    @Nullable Shape getClickbox(); // engine's own click hit-test shape
    @Nullable WorldPoint getWorldLocation();
    void interact(String verb);    // smart click via dispatcher
}
```

Three rules every entity follows:

1. **No cached state.** Each call goes back to the engine. An entity stored
   for 10 minutes still resolves correctly (or returns `false`/`null` if the
   underlying record is gone). Safe to keep across ticks.

2. **Engine click-shapes only.** Click pixels come from
   `TileObject.getClickbox()` (ground items / objects) or
   `Actor.getConvexHull()` (NPCs / players) — never from a tile polygon
   sample. Earlier code that sampled inside `getCanvasTilePoly()` failed on
   small ground items because the sprite occupied a fraction of the tile,
   leaving most random pixels off-target. The fallbacks below exist purely
   for the one-tick window where the engine's renderable hasn't built yet.

3. **Async dispatch.** `interact(verb)` returns immediately and runs the
   click chain on a worker thread inside the dispatcher. The dispatcher is
   single-flight: a second `interact` while the first is in flight is
   silently dropped. **Always gate on `dispatcher.isBusy() == false`** before
   issuing the next action.

---

## GroundItemEntity

### Identity

```java
new GroundItemEntity(client, dispatcher, tile, itemId)
```

Addressed by **tile + item id**, not by `TileItem` reference. `TileItem`
records can be reused across ticks; storing the addressing fields lets the
entity re-find the live record on every call.

### Click area

`getClickbox()` returns `tile.getItemLayer().getClickbox()` — the engine's
union shape over every item visible in the pile. Right-clicking inside it
**guarantees** the menu contains a `Take` entry for every item on the tile
(this is the same shape the engine uses for its own hit-test, so OSRS itself
treats those pixels as item-clicks).

If the item layer's clickbox isn't built yet (one-tick race after a fresh
spawn), the dispatcher's `PixelResolver.resolveGroundItemPixel` falls back
through:

1. `ItemLayer.getClickbox()` — primary (engine truth).
2. `Perspective.localToCanvas(tile.center, plane)` ± 10 px jitter — tile
   centre is where ground items render in OSRS, jitter stays inside the
   sprite footprint while breaking pixel-perfect repetition.
3. Random pixel inside `getCanvasTilePoly()` — legacy behaviour, last
   resort, never preferred.

You generally don't need to know any of this — `interact("Take")` does the
right thing. The chain matters only when debugging "menu missing 'Take'"
log lines: each fallback step is logged.

### Methods

```java
WorldPoint tile();              // tile this drop sits on
int itemId();                   // engine ItemID
@Nullable TileItem tileItem();  // live record (client thread)
boolean exists();
@Nullable Shape getClickbox();
@Nullable WorldPoint getWorldLocation();
void take();                    // == interact("Take")
void interact(String verb);
```

### Common patterns

```java
// Take everything we own on a tile in one cycle
for (GroundItemEntity item : entities.groundItemsOn(tile)) {
    if (!item.exists()) continue;
    item.take();
    while (dispatcher.isBusy()) Thread.sleep(60);
}

// Use a "Drop" verb on an inventory item — same dispatch path,
// different verb. (Not yet wired, but the API supports it.)
entities.groundItem(ItemID.BONES, tile).ifPresent(b -> b.interact("Bury"));
```

---

## NpcEntity

### Identity

```java
new NpcEntity(client, dispatcher, npcIndex)
```

Addressed by the engine NPC index. The index is stable for the lifetime of
the NPC in the scene; respawns get a new index. Storing the index instead
of the live `NPC` reference keeps the entity safe to keep across ticks
(the live reference can be GC'd when the NPC despawns).

### Click area

`getClickbox()` returns `npc.getConvexHull()` — the projected model hull,
which is what OSRS itself hit-tests against. This is the right answer for
moving NPCs: as the chicken steps, the hull updates each frame, and the
dispatcher's "humanize then re-measure" sequence picks the **fresh** hull
right before the press, not the position from when the click was scheduled.

### Smart left vs right click

`interact(verb)` dispatches `CLICK_NPC` with the requested verb. Inside the
dispatcher, `npcClick(npcIndex, verb)` does:

```
Phase 1 — read NPC tile + rough pre-aim
Phase 2 — rotate camera (skipped if NPC tile already comfortably on-screen)
Phase 3 — long humanized cursor move toward pre-aim
Phase 4 — settle 180–400 ms
Phase 5 — re-resolve click pixel from the FRESH convex hull
Phase 6 — small final aim adjustment if hull moved >4 px
Phase 7 — hover verify: is left-click default `<verb>` on this NPC?
            yes → Phase 8: left-click, return.
            no  → Phase 9.
Phase 9 — right-click → wait for menu → click matching menu row
            no matching row → abort with `menu missing '<verb>'`.
```

So:

- **Single chicken in an empty area** → hover-default is "Attack Chicken",
  left-click fires immediately.
- **Multiple chickens stacked / shopkeeper with non-Attack default / banker
  with Talk-to default** → right-click + menu pick. No bot-tell of clicking
  through occluded targets, no useless "Walk here" menu pops.

### Methods

```java
int index();
@Nullable NPC npc();             // live engine record (client thread)
@Nullable String name();         // composition name, markup-stripped
boolean exists();
@Nullable Shape getClickbox();
@Nullable WorldPoint getWorldLocation();
void attack();                   // == interact("Attack")
void interact(String verb);
```

### Common patterns

```java
// Combat: nearest chicken, attack it
entities.nearestNpc("Chicken").ifPresent(NpcEntity::attack);

// Pickpocket
entities.npcsByName("Man").stream()
    .filter(n -> n.getWorldLocation() != null)
    .findFirst()
    .ifPresent(n -> n.interact("Pickpocket"));

// Talk to a shopkeeper
entities.nearestNpc("Shop assistant").ifPresent(n -> n.interact("Talk-to"));

// Wrap an NPC reference you already have (e.g. from an event)
NpcEntity wrapped = entities.wrap(eventNpc);
```

---

## Threading

| Call                                  | Thread                       |
|---------------------------------------|------------------------------|
| `Entities.*` finders                  | client thread                |
| `entity.exists()`                     | client thread                |
| `entity.getClickbox()`                | client thread                |
| `entity.getWorldLocation()`           | client thread                |
| `entity.npc()` / `entity.tileItem()`  | client thread                |
| `entity.interact(verb)`               | any thread (async dispatch)  |
| `dispatcher.isBusy()`                 | any thread                   |

If your script runs off the EDT (e.g. a state-machine worker like
`ChickenCombatLoop`), wrap reads in `clientThread.invoke(...)` or use the
loop's existing `onClient(...)` helper.

`interact` is fire-and-forget. To sequence two actions:

```java
chicken.attack();
while (dispatcher.isBusy()) Thread.sleep(60);
// chicken is dead by here; loot
feather.take();
while (dispatcher.isBusy()) Thread.sleep(60);
```

A long-running loop should put these waits on a deadline (see
`ChickenCombatLoop.doLoot` for the canonical pattern: wait for `isBusy()`
to clear, capped only by an overall per-action timeout).

---

## Visibility / reachability

The entity layer doesn't filter for visibility or path-reachability — that's
the selector's job (`NpcSelector` + `TargetVisibility` for combat). Scripts
typically:

1. Filter NPCs through the visibility / reachability check.
2. Pick a target from the survivors.
3. Wrap the chosen one in an `NpcEntity` (often via `entities.wrap(npc)`).
4. Call `attack()` / `interact(verb)`.

Putting the visibility check in front of `interact` would slow the click
chain for no benefit: by the time the cursor lands on the model, the engine
itself confirms the hover (Phase 7) and aborts cleanly if the target moved.

---

## When the entity returns null / false

`exists()` returning `false` mid-script is the engine telling you the world
moved on. Treat it as a normal terminal state:

- **Ground item gone**: someone else picked it up, or it despawned (loot
  visible-time / despawn-time elapsed). Re-select.
- **NPC despawned**: it died or walked out of scene. Re-select.
- **`getClickbox()` null but `exists()` true**: rare one-frame race after a
  spawn. Wait one tick and retry — the dispatcher's fallback chain handles
  this transparently for `interact`, so the only callers that need to care
  are overlays / debug tools that read the shape directly.

---

## Adding new entity types

Same pattern: implement `Entity`, store engine addressing fields (not the
live record), defer click-shape lookup to whatever the engine exposes
(`TileObject.getClickbox()` for game objects / wall objects / decorative
objects — they all extend `TileObject`). Then add a finder method on
`Entities`. The dispatcher already supports the click kinds; adding a new
class only requires the finder + a thin wrapper.

---

## See also

- [`docs/visibility.md`](./visibility.md) — every "can the player see this?"
  filter, the engine primitives behind each (plane, LOS, reachability,
  roof flags, viewport, HUD widget tree, open menu) and how to extend.
- [`docs/api.md`](./api.md) — low-level reference for the click pipeline,
  dispatcher phases, `ActionRequest` fields, pixel-resolver strategies.
- `HumanizedInputDispatcher` (`runelite-client/.../sequence/dispatch/`) —
  the layer underneath. Read it if you're adding new click kinds.
- `PixelResolver.resolveGroundItemPixel` — the strategy chain documented
  above for ground-item pixels.
- `NpcSelector` + `TargetVisibility` (`plugins/recorder/combat/`) — picking
  *which* entity to interact with, with LOS + walking-reachability filters.
- `ChickenCombatLoop` (same package) — canonical end-to-end example of
  using these pieces together.
