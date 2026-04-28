# Annotator Redesign — Design Spec

**Status:** approved 2026-04-28. Implementation plan to follow.

**Why this exists:** the annotator UI shipped in [Task 6 of the chicken-farm-bot plan](../plans/2026-04-27-chicken-farm-bot.md) was a thin overlay on the existing freeform-textarea route editor. In real use it proved unmanageable: no scrolling, no list view of saved routes, no way to inspect or edit individual waypoints, and the area-rendering call drew across ~4 tiles when the user clicked one. This spec replaces the annotator section of `RecorderPanel` with a tabbed structure, a route+waypoint list editor, a corrected area renderer, and a richer area-selection input model.

---

## Goals

1. Make the side panel manageable: tab-based navigation, scrolling per tab, status pinned.
2. Replace the freeform route textarea with a structured **routes list → waypoint editor** drilldown.
3. Support full CRUD on waypoints: rename, delete, reorder, edit bounds.
4. Support **irregular tile sets** for `WALK_AREA` waypoints, not just rectangles.
5. Replace the buggy 2-click area capture with a click-and-drag rectangle selector that supports add/subtract semantics.
6. Fix the existing `RouteOverlay.areaPolygon` rendering bug.
7. Keep the existing route grammar backwards compatible — old `walkbox:` lines parse and round-trip unchanged.

## Non-goals

- Multi-step transports (fairy rings, boats, dialogue-gated portals). The catch-all `interact:` verb handles single-click cases only; multi-step is a future task.
- Live preview of the *walker's* path between waypoints (it would have to simulate the BFS reachability check). Out of scope.
- Undo across panel restarts. The 3-step undo stack is in-memory per route, lost on plugin reload.

---

## Section 1 — Panel structure

`RecorderPanel` is reorganised into:

- **Pinned header** (always visible): one-row Status block with state, elapsed, events count.
- **`JTabbedPane`** below the header, each tab body wrapped in a `JScrollPane`.

Tabs:

| Tab | Contents |
|---|---|
| **Routes** | New annotator: routes list → drilldown editor (Section 2). Folds in the current "Debug + tile mark" controls (Mark tile, Walk to mark) since they share the click-tile workflow. Includes Walk path / Walk to selected. |
| **Combat** | Start / Stop chicken loop, status, kills. Same controls as today. |
| **Mining** | Start / Stop mining, Add rock here, Clear rocks. Same controls as today. |
| **Record** | Record button, Marker field + Add marker, Recent (last 50) events list. Recording-related controls grouped. |
| **Login** | Login chooser, WidgetDumper. Same as today. |

Login tab keeps its dedicated tab even though it's narrow — it's a high-frequency entry point and merging it elsewhere adds confusion.

The combat-loop integration that ships today (`farmLoop` falls back to the bare `chickenLoop` when no route file is loaded) keeps working. The Combat tab points at whichever is non-null.

## Section 2 — Routes tab

### Routes list view (default)

- On plugin `startUp` and after every save, scan `~/.runelite/sequencer/routes/*.txt` and parse each via `RouteParser`. Routes that parse cleanly populate the list. Routes with errors get logged at WARN; the panel shows a `[!]` indicator and the error message in a tooltip.
- Each row: filename (without `.txt`), waypoint count, optional `FARM` badge if the filename matches `FarmConfig.DEFAULT_ROUTE_FILE`.
- Toolbar above the list:
  - `+ New Route` → small Swing prompt for the filename, creates an empty file, drops the user into its editor.
- Toolbar below the list (operate on the selected row):
  - `Rename` → prompt, renames the file on disk.
  - `Delete` → confirmation dialog, deletes the file.
- Single-click a row → enter the drilldown editor for that route.

### Waypoint editor (drilldown)

- `← Back to routes` link at top.
- Header row: route name + FARM badge (if applicable) + `Undo (n)` button (enabled when stack non-empty, max 3).
- Toolbar:
  - `Mark area` — enters area-selection mode (Section 3) for a new waypoint. Name field above feeds the new waypoint's name.
  - `Mark object` — single-click capture; emits `open:` / `climb-up:` / `climb-down:` / `interact:Verb:` per `TransportResolver.findAnyTransport`.
  - `Add current` — appends the player's current tile as a single-tile WALK waypoint.
  - `Add marked` — appends the tile most recently captured by `TileMarker` as a single-tile WALK waypoint.
  - `Walk path` — runs `RouteWalker` over waypoints 0..end (existing behavior).
  - `Walk to selected` — runs `RouteWalker` from waypoint 0 to the currently-selected waypoint (inclusive), then stops. Used to test partial routes during incremental construction.
- Waypoint list. Each row (MVP icons; Section 7 covers a polish phase that swaps `▲▼` for a drag handle):
  - `#index name verb-summary [▲] [▼] [✎] [×]`
  - `verb-summary` example: `walkbox 7×5` (rectangular area), `walktiles ×23` (irregular set, 23 tiles), `climb-down`, `open`, `interact:Squeeze-through`.
  - `▲` / `▼` reorder one slot up/down (Section 7 Phase A). Buttons disabled at list ends.
  - `✎` edit bounds → re-enters area-selection mode preloaded with this waypoint's set (Section 7).
  - `×` delete → one undo step.
- Click a row to **select** it: highlights its tiles in the canvas overlay (Section 6), and makes it the target for `Walk to selected`.
- **Inline rename**: double-click the name span turns it into a `JTextField`; Enter commits, Esc cancels. Empty name → stored as `null` (renders as `(unnamed)`).

### Auto-save + undo

- Every mutation (mark new waypoint, rename, delete, reorder, edit bounds, rename-route) writes the route file immediately and pushes a snapshot of the route's previous text content onto a per-route undo stack capped at 3 entries (oldest evicted on overflow).
- `Undo (n)` pops a snapshot, writes it back to disk, refreshes the waypoint list. The popped state is **not** retained for redo — undo is one-way.
- Undo stack is keyed by route filename; switching routes does not reset other routes' stacks (each route keeps its own up to 3 snapshots).
- Undo stack is in-memory; reloading the plugin or restarting the client clears it.

## Section 3 — Mark area input model

### Mode entry

Triggered by clicking `Mark area` (new waypoint) or `✎` on an existing area waypoint. The button visually highlights to indicate "recording" state. An on-screen HUD overlay appears at the top of the canvas:

```
Drag · add tiles
Shift+Drag · remove
Click · toggle one tile
Enter · save  ·  Esc · cancel
```

For edit-bounds, the HUD prepends `Editing: <name>` so the user knows they're modifying, not creating.

The user's current canvas mouse listeners are temporarily intercepted by a new `AreaSelector` input handler. The existing `TileMarker` keeps its single-click-callback contract for `Mark object` and the legacy `Mark tile` button — `AreaSelector` is a separate component.

### During the mode

- Working tile set is rendered live in the existing `RouteOverlay`. In-set tiles fill translucent (cyan), bounding-box outline drawn for emphasis.
- **Press-drag** anywhere on the canvas → live rectangle preview of the dragged region (a green outline that updates with mouse moves). Release adds every tile inside the rect to the set.
- **Shift+press-drag** → preview rendered in red; release subtracts the tiles.
- **Single click** on a tile → toggles that tile in the set (add if absent, remove if present).
- Mouse moves with no button down → no effect.
- The set is always immediately reflected in the live overlay; no preview-vs-committed distinction during the session.

### Mode exit

- **Enter** key, or "Done" button in the HUD → commits.
  - For new waypoint: appends a new `WALK_AREA` waypoint with the captured set, name from the panel's Name field (`null` if blank), at the end of the route. Auto-save writes the file. One undo step pushed.
  - For edit-bounds: replaces the target waypoint's tile set in place, preserving its name and index. Auto-save writes the file. One undo step pushed.
- **Esc** key, or "Cancel" button → abandons. Route file unchanged. No undo step.
- HUD disappears; button un-highlights; canvas mouse listeners restored to the panel's normal handlers.

### `AreaSelector` component

```java
public final class AreaSelector
{
    public interface Listener
    {
        void onCommit(Set<WorldPoint> tiles);
        void onCancel();
    }

    public void start(Set<WorldPoint> initialTiles, Listener listener);
    public boolean isActive();
    public void cancel(); // programmatic cancel (e.g., on plugin shutdown)
}
```

Internally:
- Subscribes to canvas mouse events while active. Translates pixel coords to `WorldPoint` via the existing scene-tile lookup helpers used by `TileMarker`.
- Tracks press-down position + modifier state. On release, computes the rectangle of tiles between press and release.
- Single-click is detected as press-and-release on the same tile within a small movement threshold (e.g., 4px).
- The HUD overlay is rendered by a small companion `Overlay` registered when `start` is called and removed when committed/cancelled.

## Section 4 — Data model + route file format

### `Waypoint` changes

```java
// Before:
public static Waypoint walkArea(@Nullable String name, WorldArea area);
@Nullable public WorldArea area();

// After (additive):
public static Waypoint walkArea(@Nullable String name, Set<WorldPoint> tiles);
public static Waypoint walkArea(@Nullable String name, WorldArea rect);  // shim, fills the rectangle
@Nullable public WorldArea area();      // returns BOUNDING BOX of tiles (cached)
public Set<WorldPoint> tiles();         // immutable, source of truth
```

- For `Kind.WALK_AREA`, `tiles` is the source of truth; `area()` returns its bounding box.
- For `Kind.WALK`, `tiles()` returns a 1-element `Set` containing the single tile; `area()` returns the existing 1×1 synthesised `WorldArea`.
- For `Kind.TRANSPORT`, both return `null` / empty.

### `RouteWalker.sampleTile` changes

```java
// Before:
static WorldPoint sampleTile(WorldArea a, Random rng, Predicate<WorldPoint> accept);

// After:
static WorldPoint sampleTile(WorldArea bbox, Set<WorldPoint> allowed, Random rng,
                             Predicate<WorldPoint> projectsAndPlane);
```

The implementation random-rolls inside `bbox`, accepts a tile only if `allowed.contains(p)` AND `projectsAndPlane.test(p)`. For rectangular areas (where `allowed` covers every bbox tile), behaviour is identical to today's sampler — three existing unit tests still pass.

### Route file format

Adaptive serialisation per `WALK_AREA` waypoint:

| Tile set shape | Line format |
|---|---|
| Perfect rectangle (every tile inside bbox is in the set) | `walkbox:sw_x,sw_y - ne_x,ne_y[,plane]` (unchanged) |
| Irregular set | `walktiles:x1,y1;x2,y2;x3,y3;...[,plane]` |

Both forms accept an optional leading `name:` prefix and trailing `# inline comment`, consistent with the existing grammar.

`RouteParser`:
- Adds a `walktiles:` branch that splits on `;`, parses each `x,y` pair, and accepts a trailing `,plane`. Bounds-validates that all tiles share the declared plane.
- The existing `walkbox:` branch is preserved verbatim.
- `Waypoint.toString()` chooses the form: if `tiles == bbox tile count`, emits `walkbox:`; otherwise emits `walktiles:`.

### New tests

- `WaypointTest.walkAreaWithIrregularSetExposesBoundingBox` — irregular set's `area()` returns the correct bbox.
- `WaypointTest.walkAreaSerialisesAsWalktilesWhenIrregular` — `toString` round-trips.
- `WaypointTest.walkAreaSerialisesAsWalkboxWhenPerfectRectangle` — backwards-compat verification.
- `RouteParserAreaTest.parsesWalktilesLine`.
- `RouteParserAreaTest.walktilesRoundTripsThroughToString`.
- `RouteParserAreaTest.walktilesRejectsBadCoord`.
- `RouteWalkerTest.sampleTileSkipsBoundingBoxTilesNotInAllowedSet`.

## Section 5 — Render bug fix

`RouteOverlay.areaPolygon` is rewritten:

- **Cause of current bug:** the perimeter walk calls `LocalPoint.fromWorld(wv, new WorldPoint(x+1, y, plane))`, which returns the LocalPoint of the *next tile's center*, not the corner. Connecting four tile centers draws a diamond extending ~half a tile beyond the actual bounds in every direction — for a 1×1 area, a visible 4-tile region.
- **Replacement:** drop the perimeter walk. Render every in-set tile by filling its `Perspective.getCanvasTilePoly` (the same call used for single-tile rendering today, known correct).
- For an N-tile waypoint: N fill polygons + N thin outline polys. Performance is fine for any sane waypoint (<200 tiles).
- A polish item — proper union-outline (only drawing edges that touch exactly one filled tile) — is deferred. MVP per-tile outlines are visually busy but unambiguous.

`RouteOverlay.drawArea` accepts the `Set<WorldPoint>` directly (one fill+outline per tile) instead of computing a polygon from the bbox. The label is anchored at the bbox SW corner as today.

## Section 6 — Selection feedback in overlay

When a waypoint is selected in the panel's list:
- Its tiles render with higher fill alpha and a thicker outline. Outline colour: cyan, matching the existing `ChickenOverlay` "closest eligible" highlight for visual consistency.
- Other waypoints render at their default translucent style.
- The label is prefixed with `▶`: e.g. `▶ #1 lumbridge_bank`.

The overlay does **not** scroll the player view to the selection — the overlay paints regardless of camera position. If a selected waypoint is off-screen, the panel's name is enough to know what's selected.

## Section 7 — Reorder and edit-bounds details

### Reorder — phased

**Phase A (MVP):** `▲` and `▼` buttons per row. One click moves the row up or down by one slot, writes the file, pushes one undo step. Buttons disabled at the list ends.

**Phase B (polish, deferred):** drag-handle (`⇅`) reorder. Mouse-press on the handle starts a drag with a ghost row + insertion line; release commits. Esc during drag cancels. If the Swing implementation is gnarly we keep Phase A indefinitely.

The spec ships with Phase A. Phase B becomes a follow-up task.

### Edit-bounds

`✎` on an area-waypoint row → enters Mark-area mode pre-loaded with that waypoint's tile set. The HUD shows `Editing: <name>` so the user can't confuse it with creating a new waypoint. Saving replaces the existing waypoint in place, preserving name + index. Esc abandons.

`✎` on a transport-waypoint row → opens a small dialog with the verb and tile coords as editable fields (single-tile, no need for the area selector). Saving updates the waypoint in place. Esc cancels.

`✎` on a single-tile WALK row → opens the same single-tile dialog (just the tile coords, no verb).

## Migration

- Routes saved before this lands use only `walkbox:` and the existing single-tile / transport syntaxes. They keep parsing and round-tripping. No format-migration step needed.
- The currently-shipped `RecorderPanel` annotator UI (Name field + Mark area + Mark object) is removed in favour of the new Routes tab. The legacy textarea is removed too — its job is fully replaced by the waypoint list.

## File layout

**Modify:**
- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/RecorderPanel.java` — major refactor: extract sections into per-tab `JPanel` builders.
- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/transport/Waypoint.java` — add `tiles()`, `walkArea(Set<WorldPoint>)`.
- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/transport/RouteParser.java` — `walktiles:` branch + serialisation choice in `toString`.
- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/transport/RouteOverlay.java` — per-tile fill rendering; selection feedback.
- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/farm/RouteWalker.java` — sampler signature change.

**Create:**
- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/annotator/AreaSelector.java` — drag-rect + click-toggle input handler.
- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/annotator/AnnotatorHudOverlay.java` — on-screen control hints.
- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/annotator/RoutesTab.java` — list view.
- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/annotator/WaypointEditor.java` — drilldown view.
- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/annotator/UndoStack.java` — per-route capped snapshot stack.
- New test classes for each new public unit.

## Open follow-ups (intentionally deferred)

- Phase B drag-handle reorder.
- Union-outline for irregular tile sets.
- Multi-step transport capture (fairy rings, boats, NPC dialogue).
- Redo (forward of undo).
- Walk from selected → end.
- A "preview" overlay showing the bot's planned tile sample on the next tick.
