# Visibility — what the player can actually see and click

Scripts must never click on something a real player wouldn't see. "Click
through wall", "click through inventory", "click on tile under chatbox" are
all immediate bot tells, even when the engine technically allows them.

This doc inventories the engine's documented visibility primitives, lists
every filter currently applied in `TargetVisibility.canSee`, and explains
which primitive catches which class of "unseeable" target.

For the higher-level entity API see [entities.md](./entities.md). For the
click pipeline see [api.md](./api.md).

---

## What "the player can see" actually breaks down to

| Class of occlusion          | Engine primitive                                                   | Filter step             |
|-----------------------------|--------------------------------------------------------------------|--------------------------|
| Different floor / plane     | `WorldPoint.getPlane()`                                            | Plane match              |
| Solid wall (full block)     | `WorldArea.hasLineOfSightTo` (projectile-LOS Bresenham)            | LOS                      |
| Half-fence (walk-block only)| BFS over `WorldArea.canTravelInDirection` collision flags          | Reachability             |
| Roof of a different building| `Constants.TILE_FLAG_UNDER_ROOF` + `Scene.getRoofs()`              | Hidden roof              |
| Off-screen / behind camera  | `NPC.getCanvasTilePoly()` returning null                           | On-canvas                |
| Outside playable viewport   | `Client.getViewportXOffset/Width/...`                              | Viewport                 |
| Right-click menu open       | `Client.isMenuOpen()` + `Menu.getMenu*()`                          | Open menu                |
| Inventory / chat / minimap  | Toplevel HUD-container widget tree                                 | HUD widget               |

Order in `TargetVisibility.canSee`:

```
0. plane match
1. projectile LOS
1a. walking reachability (BFS)
1b. roof match (under-roof + roof IDs)
2. on-canvas (tile poly non-null)
3. inside playable viewport
4. open right-click menu
5. persistent HUD widgets (resizable mode)
```

Each step short-circuits on the first miss. Order is by *cost*: cheap
integer / world-space checks first, canvas / widget walks last.

---

## Engine primitives reference

### Tile flags — `Constants.java`

```java
public static final int TILE_FLAG_BRIDGE     = 2;  // tile is the bridge level
public static final int TILE_FLAG_UNDER_ROOF = 4;  // tile has a roof above it
public static final int TILE_FLAG_VIS_BELOW  = 8;  // tile shows what's below (balcony / hole)
```

Read via `WorldView.getTileSettings()` → `byte[plane][sceneX][sceneY]`:

```java
byte[][][] settings = wv.getTileSettings();
boolean underRoof = (settings[plane][sx][sy] & Constants.TILE_FLAG_UNDER_ROOF) != 0;
boolean visBelow  = (settings[plane][sx][sy] & Constants.TILE_FLAG_VIS_BELOW)  != 0;
boolean isBridge  = (settings[1][sx][sy]     & Constants.TILE_FLAG_BRIDGE)     != 0;
```

`VIS_BELOW` is for the rare "balcony / hole in the floor" case (player on
plane 0 sees through plane 1 hole). The current visibility check requires
plane match instead — combat doesn't reach across planes anyway. If you
need to add cross-plane interaction (e.g. clicking down through a hatch),
plumb `VIS_BELOW` through here.

### Roofs — `Scene.java`

```java
int[][][] getRoofs();        // roof ID per [plane][sceneX][sceneY], 0 = no roof
int      getRoofRemovalMode(); // bitmask of currently-removed roofs
```

Roof IDs are **non-zero per building** — two NPCs under the same roof ID
share a roof, two under different roof IDs are in different buildings.
Roof-removal mode is a bitmask of:

```java
Constants.ROOF_FLAG_POSITION    = 1  // remove roof above the player's tile
Constants.ROOF_FLAG_HOVERED     = 2  // remove roof above the cursor's tile
Constants.ROOF_FLAG_DESTINATION = 4  // remove roof above the destination tile
Constants.ROOF_FLAG_BETWEEN     = 8  // remove roofs between camera and player
```

Whether a specific roof is rendered depends on `getRoofRemovalMode()` and
the engine's per-frame culling. Mimicking the exact decision is fragile;
the safer heuristic used in `TargetVisibility.isUnderHiddenRoof` is:

1. NPC isn't under a roof → visible.
2. NPC under roof, player isn't → in different buildings, **cull**.
3. Both under roofs but **different roof IDs** → different buildings, **cull**.
4. Same roof ID → same building, visible.

### LOS / collision — `WorldArea.java`

```java
boolean hasLineOfSightTo(WorldView wv, WorldArea other);  // projectile LOS
boolean canTravelInDirection(WorldView wv, int dx, int dy);  // single-step walk
```

Projectile-LOS is **not** the same as walking reachability. Half-fences in
Lumbridge / chicken pens pass projectile LOS but block walking. We need
both filters: LOS (fast) catches solid walls, BFS over
`canTravelInDirection` catches walking obstructions. See
`TargetVisibility.isReachable`.

### Scene shape — `Constants.java`

```java
SCENE_SIZE          = 104   // standard scene width/height
EXTENDED_SCENE_SIZE = 184   // scene + 40-tile padding
MAX_Z               = 4     // planes 0..3
```

Scene tiles are at `wv.getScene().getTiles()[plane][sceneX][sceneY]`
indexed in `[0, SCENE_SIZE)`. Convert from `WorldPoint` via
`LocalPoint.fromWorld(wv, world).getSceneX()/getSceneY()`.

### Canvas projection — `Perspective.java`

```java
Point   localToCanvas(Client, LocalPoint, plane[, heightOffset]);
Polygon getCanvasTilePoly(Client, LocalPoint);
Shape   getClickbox(Client, WorldView, Model, orientation, x, y, z);
```

`getCanvasTilePoly` returning `null` is the engine's signal that the tile
isn't being projected to canvas (off-screen, behind camera, outside scene
range). Use this as the *on-canvas* gate before any pixel-level check.

### Viewport — `Client.java`

```java
int  getViewportXOffset();   // top-left X of play area on the canvas
int  getViewportYOffset();   // top-left Y
int  getViewportWidth();     // play area width
int  getViewportHeight();    // play area height
boolean isResized();         // true in resizable mode
int  getCanvasWidth();       // total canvas
int  getCanvasHeight();
```

In **fixed mode** (`isResized() == false`) the viewport is a fixed
512×334-ish rectangle inset from the canvas. The chrome around it is the
inventory column, the chatbox, the minimap, and the status orbs — all
*outside* the viewport. A pixel-in-viewport check fully excludes HUD chrome.

In **resizable mode** (`isResized() == true`) the viewport equals the
canvas. The HUD widgets float **on top** of the world render, not outside
it. A pixel-in-viewport check is necessary but not sufficient — you also
need the HUD widget pass below.

### HUD widgets — `WidgetInfo` / `InterfaceID`

The toplevel HUD container hosts every persistent panel (inventory tabs,
chatbox, minimap, status orbs, world map button, …). The container ID
varies by layout:

```java
client.isResized()
  ? (client.getVarbitValue(VarbitID.RESIZABLE_STONE_ARRANGEMENT) == 1
        ? client.getWidget(InterfaceID.ToplevelPreEoc.HUD_CONTAINER_FRONT)
        : client.getWidget(InterfaceID.ToplevelOsrsStretch.HUD_CONTAINER_FRONT))
  : client.getWidget(InterfaceID.Toplevel.OVERLAY_HUD)
```

That widget itself spans the canvas and is the layer the world renders
*through*; treat its **descendants** as occluding chrome. Iterate
`getStaticChildren() + getDynamicChildren() + getNestedChildren()` and
exclude pixels under any visible child whose bounds contain the cursor —
this naturally covers inventory tabs, the side panel, minimap orbs, and
the right-side combat stones without hard-coding rectangles.

The chatbox lives outside this tree in some layouts; check
`InterfaceID.Chatbox.CHATAREA` separately.

### Right-click menu — `Menu.java`

```java
int getMenuX();
int getMenuY();
int getMenuWidth();
int getMenuHeight();
```

Read via `client.getMenu()`. **Only** valid while
`client.isMenuOpen() == true` — when closed (the default hover state) the
menu rectangle is meaningless.

---

## How `TargetVisibility.canSee` uses these

Code lives at
`runelite-client/src/main/java/net/runelite/client/plugins/recorder/combat/TargetVisibility.java`.
The implementation is intentionally one straight-line method, shortest-circuit
first. Each step has a `log.debug("cull npc {} — <reason>")` so failures
show up cleanly when you turn on the logger.

```text
canSee(npc, self, wv):
    if any null                           → false
    if self.plane != npc.plane            → false   (step 0)
    if !LOS                                → false   (step 1)
    if !walking-reachable                  → false   (step 1a)
    if NPC under hidden roof              → false   (step 1b)
    if no canvas tile poly                → false   (step 2)
    if pixel outside viewport             → false   (step 3)
    if pixel under open right-click menu  → false   (step 4)
    if isResized() && pixel under HUD     → false   (step 5)
    return true
```

`alwaysVisible()` (the test stub) skips the entire chain. That's how the
existing combat unit tests bypass canvas / widget reads on a mocked
`Client`.

---

## Adding a new visibility class

The pattern is: identify the engine primitive, add a numbered step in
`canSee`, add a debug log line, update the table at the top of this doc.

Examples:

- **Quest dialog occlusion**: a quest dialogue overlay covers a large
  rect mid-screen. Add a step after step 5 that checks
  `client.getWidget(InterfaceID.Chatbox.MES_TEXT2)` (or the relevant
  dialog widget) and culls pixels inside it. The HUD-widget walk in step
  5 may already cover this if the dialog is a child of the HUD container.

- **Fog distance**: OSRS fades distant tiles. If you want to cull NPCs
  past the fog cutoff, gate on
  `Math.abs(playerPos.distanceTo(npcPos)) > Scene.getDrawDistance() * SCENE_FACTOR`.
  Cheap world-space check, goes near the top.

- **Camera-pitch occlusion (NPC on far side of a tall building)**: needs
  raycasting in screen space. There isn't a clean engine primitive — the
  cheapest approximation is to check `npc.getConvexHull()` for emptiness
  *and* to compare the bounding box height against `Perspective.getCanvasTilePoly`
  for the same tile. If the model hull is much smaller than the tile poly
  the model is mostly clipped by something. Don't add this without a
  failing test case in front of you; it's noisy.

Keep additions cheap-first and never bypass the existing checks just to
ship a quick fix.
