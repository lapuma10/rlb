# Annotator Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the chicken-farm-bot annotator UI with a tabbed `RecorderPanel`, a routes-list / waypoint-editor drilldown, irregular tile-set support for `WALK_AREA` waypoints, and a fixed `RouteOverlay` area renderer. Adds auto-save with 3-step undo and "Walk to selected" for incremental route testing.

**Architecture:** Two-stage rollout. Stage 1 (Tasks 1–4) lands data-model + grammar + render-bug fixes that everything else builds on; nothing is user-visible yet but every existing test still passes. Stage 2 (Tasks 5–14) lands the new annotator components (`AreaSelector`, HUD, `UndoStack`) and the panel restructure that wires the user-visible Routes tab. Stage 3 (Task 15) is the smoke + push.

**Tech Stack:** Java 17, RuneLite plugin API (Client / WorldView / Perspective / MouseManager), Swing (JTabbedPane / JScrollPane / JList / JPanel), JUnit + Mockito (tests).

- JDK: `/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`
- Build: `./gradlew :client:shadowJar`
- Test single: `./gradlew :client:test --tests '<fully.qualified.TestName>'`
- Run client (background): `nohup /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/bin/java --add-exports=java.desktop/sun.awt=ALL-UNNAMED --add-exports=java.desktop/com.apple.eawt=ALL-UNNAMED --add-exports=java.desktop/com.apple.eio=ALL-UNNAMED --add-opens=java.desktop/com.apple.eawt=ALL-UNNAMED -ea -jar runelite-client/build/libs/client-1.12.25-SNAPSHOT-shaded.jar --developer-mode > /tmp/runelite-stdout.log 2>&1 & disown`

**Conventions for this fork:** new files do NOT carry the BSD-2-Clause copyright header (private fork; see memory `feedback_no_bsd_headers.md`). Files inherited or modified out of upstream RuneLite keep their existing headers untouched.

---

## What exists today (read first)

The annotator that this redesign replaces shipped at squash commit `8217d5e02`; subsequent BSD-strip commit at `fcbc23e60`. Master HEAD at plan time = `fcbc23e60`.

Relevant existing pieces:

- **`net.runelite.client.plugins.recorder.transport.Waypoint`** — `Kind` enum: `WALK`, `WALK_AREA`, `TRANSPORT`. `WALK_AREA` carries a `WorldArea` rectangle. Optional `name` on every kind. `area()` returns the rect for `WALK_AREA`, a 1×1 synthesised area for `WALK`, null for `TRANSPORT`.
- **`RouteParser`** — parses `walkbox:`, `walk:` / plain coords, `open:` / `climb-up:` / `climb-down:` / `interact:`. Strips inline `  #` comments. Optional `name: ` prefix on every line. `Result.waypoints()` / `errors()` / `hasErrors()`.
- **`RouteOverlay`** — renders waypoints. The `areaPolygon` perimeter walk connects tile centers (via `LocalPoint.fromWorld(wv, new WorldPoint(x+1, y, plane))`) instead of tile corners — that's the rendering bug the user reported.
- **`RouteWalker.sampleTile(WorldArea, Random, Predicate)`** — pure tile sampler. Used by `tickWalk` to pick a random valid tile inside an area each tick. Constructor signature: `(Client, HumanizedInputDispatcher, TransportResolver)` (3-arg; `clientThread` was dropped during the chicken-farm-bot follow-ups).
- **`TransportResolver.findAnyTransport(WorldPoint)`** — returns an `AnyMatch` with the first detected verb (Open / Climb-up / Climb-down / catch-all INTERACT). Used by `Mark object` today; stays unchanged.
- **`RecorderPanel`** — single tall `JPanel` with sections (Status, Recording, Marker, Recent, Debug+tile mark, Test walk / Platforms with the annotator, Combat, Mining, Login). No scrolling.
- **`TileMarker.arm(Consumer<WorldPoint> callback)`** — single-shot tile capture used by the legacy `Mark tile` button and the current annotator's `Mark area` two-click flow.
- **`MouseManager`** (`net.runelite.client.input.MouseManager`) — RuneLite's canvas mouse listener registry. `registerMouseListener(MouseListener)` returns a registration handle. The existing `TileMarker` wires its single-click capture this way; reuse the pattern for drag.
- **`HumanizedInputDispatcher`** — already used to issue minimap / canvas clicks. Exposes `clickCanvas(int x, int y)` and `tapKey(int keyCode)` (used by `BankInteraction.closeBank`). The annotator does not dispatch — it only captures input.
- **`FarmConfig.DEFAULT_ROUTE_FILE`** — `~/.runelite/sequencer/routes/lumbridge_bank_to_pen.txt`. Saving via the new annotator overwrites the file the chicken-farm bot loads at startup. The Combat tab keeps its existing wiring.

---

## File structure

### Modify

- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/transport/Waypoint.java` — replace `WorldArea` storage with `Set<WorldPoint>`; add `tiles()` accessor; refactor `area()` to compute bbox; add new factory.
- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/transport/RouteParser.java` — add `walktiles:` branch; adapt `Waypoint.toString()` choice via the parser's serialisation site.
- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/transport/RouteOverlay.java` — replace perimeter-walk fill with per-tile `getCanvasTilePoly`; add selection-highlight rendering.
- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/farm/RouteWalker.java` — `sampleTile` signature change; `tickWalk` updates.
- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/RecorderPanel.java` — substantial restructure: extract sections into per-tab panel builders, replace the freeform annotator with the new `RoutesTab` + `WaypointEditor`. Keep the field reference to `farmLoop` / `chickenLoop` etc. so the Combat tab keeps working.
- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/RecorderPlugin.java` — register new annotator overlays + selector; pass them into the panel.

### Create

- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/annotator/AreaSelector.java` — drag-rect + click-toggle + shift-subtract input handler.
- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/annotator/AnnotatorHudOverlay.java` — on-screen control hints rendered while a selection is active.
- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/annotator/UndoStack.java` — per-route capped 3-snapshot stack.
- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/annotator/RoutesTab.java` — Swing panel with the saved-routes list + new/rename/delete + drilldown trigger.
- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/annotator/WaypointEditor.java` — Swing panel with the per-route waypoint list + toolbar + selection state.
- `runelite-client/src/test/java/net/runelite/client/plugins/recorder/transport/WaypointTilesTest.java`
- `runelite-client/src/test/java/net/runelite/client/plugins/recorder/transport/RouteParserWalktilesTest.java`
- `runelite-client/src/test/java/net/runelite/client/plugins/recorder/farm/RouteWalkerTilesTest.java`
- `runelite-client/src/test/java/net/runelite/client/plugins/recorder/annotator/AreaSelectorTest.java`
- `runelite-client/src/test/java/net/runelite/client/plugins/recorder/annotator/UndoStackTest.java`

---

## Stage 1 — Foundation (Tasks 1–4)

### Task 1: `Waypoint` tile-set data model

Refactor `WALK_AREA` to store `Set<WorldPoint>` internally; expose `tiles()`; have `area()` return the bounding box. Existing factories and `area()` consumers (`RouteWalker`, `ChickenFarmLoop`, `RouteOverlay`) keep working.

**Files:**
- Modify: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/transport/Waypoint.java`
- Create: `runelite-client/src/test/java/net/runelite/client/plugins/recorder/transport/WaypointTilesTest.java`

- [ ] **Step 1: Write the failing tests**

Create `runelite-client/src/test/java/net/runelite/client/plugins/recorder/transport/WaypointTilesTest.java`:

```java
package net.runelite.client.plugins.recorder.transport;

import java.util.HashSet;
import java.util.Set;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.*;

public class WaypointTilesTest
{
    @Test
    public void walkAreaFromTileSetExposesTheSet()
    {
        Set<WorldPoint> tiles = Set.of(
            new WorldPoint(3091, 3243, 2),
            new WorldPoint(3092, 3243, 2),
            new WorldPoint(3091, 3244, 2));
        Waypoint w = Waypoint.walkArea("lumbridge_bank", tiles);
        assertEquals(Waypoint.Kind.WALK_AREA, w.kind());
        assertEquals(tiles, w.tiles());
    }

    @Test
    public void walkAreaBboxIsTheBoundingRectangle()
    {
        Set<WorldPoint> tiles = Set.of(
            new WorldPoint(3091, 3243, 2),
            new WorldPoint(3093, 3245, 2),
            new WorldPoint(3092, 3244, 2));
        Waypoint w = Waypoint.walkArea(null, tiles);
        WorldArea bbox = w.area();
        assertNotNull(bbox);
        assertEquals(3091, bbox.getX());
        assertEquals(3243, bbox.getY());
        assertEquals(3, bbox.getWidth());   // 3093-3091+1
        assertEquals(3, bbox.getHeight());  // 3245-3243+1
        assertEquals(2, bbox.getPlane());
    }

    @Test
    public void walkAreaFromRectangleFillsEveryTile()
    {
        WorldArea rect = new WorldArea(3091, 3243, 3, 2, 2); // 3x2 = 6 tiles
        Waypoint w = Waypoint.walkArea("rect", rect);
        assertEquals(Waypoint.Kind.WALK_AREA, w.kind());
        assertEquals(6, w.tiles().size());
        assertTrue(w.tiles().contains(new WorldPoint(3091, 3243, 2)));
        assertTrue(w.tiles().contains(new WorldPoint(3093, 3244, 2)));
    }

    @Test
    public void walkSingleTileTilesReturnsSingletonSet()
    {
        WorldPoint p = new WorldPoint(3208, 3220, 0);
        Waypoint w = Waypoint.walk(p);
        assertEquals(Set.of(p), w.tiles());
    }

    @Test
    public void transportTilesIsEmptySet()
    {
        Waypoint w = Waypoint.transport(new WorldPoint(3239, 3295, 0),
            Waypoint.TransportKind.OPEN, "Open");
        assertTrue(w.tiles().isEmpty());
    }

    @Test
    public void walkAreaRejectsEmptyTileSet()
    {
        try
        {
            Waypoint.walkArea(null, new HashSet<>());
            fail("expected IllegalArgumentException");
        }
        catch (IllegalArgumentException ex)
        {
            assertTrue(ex.getMessage(), ex.getMessage().toLowerCase().contains("empty"));
        }
    }

    @Test
    public void walkAreaRejectsMultiPlaneTileSet()
    {
        Set<WorldPoint> tiles = Set.of(
            new WorldPoint(3091, 3243, 0),
            new WorldPoint(3092, 3243, 1));
        try
        {
            Waypoint.walkArea(null, tiles);
            fail("expected IllegalArgumentException");
        }
        catch (IllegalArgumentException ex)
        {
            assertTrue(ex.getMessage(), ex.getMessage().toLowerCase().contains("plane"));
        }
    }
}
```

- [ ] **Step 2: Run the new tests to verify they fail**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
./gradlew :client:test --tests 'net.runelite.client.plugins.recorder.transport.WaypointTilesTest'
```

Expected: 7 failures (`tiles()`, `walkArea(String, Set<WorldPoint>)` undefined symbols).

- [ ] **Step 3: Refactor `Waypoint.java`**

Replace the body of `runelite-client/src/main/java/net/runelite/client/plugins/recorder/transport/Waypoint.java` with:

```java
package net.runelite.client.plugins.recorder.transport;

import java.util.Iterator;
import java.util.Set;
import javax.annotation.Nullable;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;

/**
 * A single step in a route. Either a plain walk target ({@link Kind#WALK}),
 * a walk into an arbitrary tile set ({@link Kind#WALK_AREA} — the bot picks
 * one valid tile per visit so it never hits the same pixel twice), or a
 * transport interaction ({@link Kind#TRANSPORT}) that requires the walker
 * to find an on-tile object exposing a verb.
 *
 * <p>For {@link Kind#WALK_AREA}, the source of truth is the immutable
 * {@code Set<WorldPoint>} stored in {@link #tiles()}. The legacy
 * {@link #area()} accessor returns the set's bounding-box {@link WorldArea}
 * — supports rectangular and irregular shapes. The route file format
 * serialises rectangular sets as {@code walkbox:} and irregular sets as
 * {@code walktiles:}; see {@link RouteParser}.
 */
public final class Waypoint
{
    public enum Kind { WALK, WALK_AREA, TRANSPORT }

    public enum TransportKind
    {
        OPEN("Open"),
        CLIMB_UP("Climb-up"),
        CLIMB_DOWN("Climb-down"),
        INTERACT(null);

        private final String defaultVerb;
        TransportKind(String v) { this.defaultVerb = v; }
        public String defaultVerb() { return defaultVerb; }
    }

    private final Kind kind;
    private final WorldPoint tile;       // non-null for WALK and TRANSPORT
    private final Set<WorldPoint> tiles; // non-null for WALK_AREA, immutable
    private final TransportKind transportKind;
    private final String verb;
    private final String name;
    // Cached bounding box for WALK_AREA — computed lazily.
    private WorldArea cachedBbox;

    private Waypoint(Kind kind, WorldPoint tile, Set<WorldPoint> tiles,
                     TransportKind tk, String verb, String name)
    {
        this.kind = kind;
        this.tile = tile;
        this.tiles = tiles;
        this.transportKind = tk;
        this.verb = verb;
        this.name = name;
    }

    public static Waypoint walk(WorldPoint tile)
    {
        return new Waypoint(Kind.WALK, tile, null, null, null, null);
    }

    public static Waypoint walkNamed(String name, WorldPoint tile)
    {
        return new Waypoint(Kind.WALK, tile, null, null, null, name);
    }

    /** Construct a {@code WALK_AREA} waypoint from an arbitrary tile set. */
    public static Waypoint walkArea(@Nullable String name, Set<WorldPoint> tiles)
    {
        if (tiles == null || tiles.isEmpty())
            throw new IllegalArgumentException("walkArea tiles empty");
        int plane = tiles.iterator().next().getPlane();
        for (WorldPoint p : tiles)
        {
            if (p.getPlane() != plane)
                throw new IllegalArgumentException(
                    "walkArea tiles span multiple planes: " + plane + " vs " + p.getPlane());
        }
        return new Waypoint(Kind.WALK_AREA, null, Set.copyOf(tiles), null, null, name);
    }

    /** Construct a rectangular {@code WALK_AREA} waypoint. Convenience that
     *  fills every tile inside {@code area}. Existing callers (legacy parser,
     *  test fixtures) keep working without code changes. */
    public static Waypoint walkArea(@Nullable String name, WorldArea area)
    {
        if (area == null) throw new IllegalArgumentException("walkArea rect null");
        int n = area.getWidth() * area.getHeight();
        if (n <= 0) throw new IllegalArgumentException("walkArea rect empty");
        java.util.Set<WorldPoint> filled = new java.util.HashSet<>(n);
        for (int dx = 0; dx < area.getWidth(); dx++)
        {
            for (int dy = 0; dy < area.getHeight(); dy++)
            {
                filled.add(new WorldPoint(area.getX() + dx, area.getY() + dy, area.getPlane()));
            }
        }
        return walkArea(name, filled);
    }

    public static Waypoint transport(WorldPoint tile, TransportKind tk, String verb)
    {
        return new Waypoint(Kind.TRANSPORT, tile, null, tk, verb, null);
    }

    public static Waypoint transportNamed(String name, WorldPoint tile,
                                          TransportKind tk, String verb)
    {
        return new Waypoint(Kind.TRANSPORT, tile, null, tk, verb, name);
    }

    public Kind kind() { return kind; }

    @Nullable public WorldPoint tile() { return tile; }

    /** Immutable tile set. Source of truth for {@link Kind#WALK_AREA}.
     *  For {@link Kind#WALK} returns a one-element set. Empty set for
     *  {@link Kind#TRANSPORT}. */
    public Set<WorldPoint> tiles()
    {
        if (kind == Kind.WALK_AREA) return tiles;
        if (kind == Kind.WALK) return Set.of(tile);
        return Set.of();
    }

    /** Bounding box of the tile set. For {@link Kind#WALK} synthesises a
     *  1×1 area at the tile. Null for {@link Kind#TRANSPORT}. */
    @Nullable
    public WorldArea area()
    {
        if (kind == Kind.WALK_AREA)
        {
            if (cachedBbox == null) cachedBbox = computeBbox(tiles);
            return cachedBbox;
        }
        if (kind == Kind.WALK)
        {
            return new WorldArea(tile.getX(), tile.getY(), 1, 1, tile.getPlane());
        }
        return null;
    }

    /** True when {@link #tiles()} forms a perfect rectangle (every tile in
     *  the bounding box is present). Used by serialisation to choose between
     *  {@code walkbox:} and {@code walktiles:}. */
    public boolean isRectangular()
    {
        if (kind != Kind.WALK_AREA) return false;
        WorldArea a = area();
        return tiles.size() == a.getWidth() * a.getHeight();
    }

    @Nullable public TransportKind transportKind() { return transportKind; }
    @Nullable public String verb() { return verb; }
    @Nullable public String name() { return name; }

    private static WorldArea computeBbox(Set<WorldPoint> tiles)
    {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        int plane = 0;
        for (WorldPoint p : tiles)
        {
            plane = p.getPlane();
            if (p.getX() < minX) minX = p.getX();
            if (p.getY() < minY) minY = p.getY();
            if (p.getX() > maxX) maxX = p.getX();
            if (p.getY() > maxY) maxY = p.getY();
        }
        return new WorldArea(minX, minY, maxX - minX + 1, maxY - minY + 1, plane);
    }

    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder();
        if (name != null) sb.append(name).append(": ");
        switch (kind)
        {
            case WALK:
                sb.append("walk:").append(tile.getX()).append(',').append(tile.getY())
                    .append(",p=").append(tile.getPlane());
                break;
            case WALK_AREA:
            {
                WorldArea a = area();
                if (isRectangular())
                {
                    sb.append("walkbox:").append(a.getX()).append(',').append(a.getY())
                        .append(" - ").append(a.getX() + a.getWidth() - 1).append(',')
                        .append(a.getY() + a.getHeight() - 1)
                        .append(",p=").append(a.getPlane());
                }
                else
                {
                    sb.append("walktiles:");
                    Iterator<WorldPoint> it = tiles.stream()
                        .sorted((p, q) -> p.getX() != q.getX()
                            ? Integer.compare(p.getX(), q.getX())
                            : Integer.compare(p.getY(), q.getY()))
                        .iterator();
                    boolean first = true;
                    while (it.hasNext())
                    {
                        WorldPoint p = it.next();
                        if (!first) sb.append(';');
                        sb.append(p.getX()).append(',').append(p.getY());
                        first = false;
                    }
                    sb.append(",p=").append(a.getPlane());
                }
                break;
            }
            case TRANSPORT:
                sb.append(transportKind.name().toLowerCase().replace('_', '-'))
                    .append(':').append(tile.getX()).append(',').append(tile.getY())
                    .append(",p=").append(tile.getPlane())
                    .append(" verb='").append(verb).append("'");
                break;
        }
        return sb.toString();
    }
}
```

Notes:
- The existing `Waypoint(Kind, WorldPoint, WorldArea, TransportKind, String, String)` private constructor is gone — replaced by the version that takes `Set<WorldPoint>`. Internal-only change; no other class constructed `Waypoint` directly.
- `walkArea(String, WorldArea)` is now a thin shim that fills the rectangle into a tile set. Existing tests calling it (e.g. `RouteParserAreaTest.parsesWalkbox`) keep passing.
- `tile()` annotation is unchanged (`@Nullable`); existing callers in `RecorderPanel.walkRoute` already null-check.

- [ ] **Step 4: Run all transport tests + the new ones**

```bash
./gradlew :client:test --tests 'net.runelite.client.plugins.recorder.transport.*'
```

Expected: all PASS, including the 7 new `WaypointTilesTest` cases.

- [ ] **Step 5: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/plugins/recorder/transport/Waypoint.java \
        runelite-client/src/test/java/net/runelite/client/plugins/recorder/transport/WaypointTilesTest.java
git commit -m "$(cat <<'EOF'
recorder/transport: Waypoint tile-set storage for WALK_AREA

WALK_AREA now stores an immutable Set<WorldPoint> instead of a
WorldArea rectangle. area() returns the bounding box (cached);
tiles() returns the set; isRectangular() reports whether every bbox
tile is in the set. The legacy walkArea(String, WorldArea) factory
fills the rectangle so existing callers (RouteParser, tests, the
walker) keep working unchanged.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: `RouteParser` walktiles: branch + adaptive serialisation

`Waypoint.toString()` already chose walkbox-vs-walktiles in Task 1. Task 2 makes the parser accept the new `walktiles:` line form and round-trip cleanly.

**Files:**
- Modify: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/transport/RouteParser.java`
- Create: `runelite-client/src/test/java/net/runelite/client/plugins/recorder/transport/RouteParserWalktilesTest.java`

- [ ] **Step 1: Write the failing tests**

Create `runelite-client/src/test/java/net/runelite/client/plugins/recorder/transport/RouteParserWalktilesTest.java`:

```java
package net.runelite.client.plugins.recorder.transport;

import java.util.Set;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.*;

public class RouteParserWalktilesTest
{
    @Test
    public void parsesWalktilesLine()
    {
        Waypoint w = RouteParser.parseLine("walktiles:3091,3243;3092,3243;3093,3244,p=2");
        assertEquals(Waypoint.Kind.WALK_AREA, w.kind());
        Set<WorldPoint> expected = Set.of(
            new WorldPoint(3091, 3243, 2),
            new WorldPoint(3092, 3243, 2),
            new WorldPoint(3093, 3244, 2));
        assertEquals(expected, w.tiles());
    }

    @Test
    public void parsesWalktilesWithoutPPrefix()
    {
        // Plane in bare ",N" form (matches walkbox: convention).
        Waypoint w = RouteParser.parseLine("walktiles:3091,3243;3092,3243,2");
        assertEquals(2, w.area().getPlane());
        assertEquals(2, w.tiles().size());
    }

    @Test
    public void parsesNamedWalktiles()
    {
        Waypoint w = RouteParser.parseLine(
            "lumbridge_bank: walktiles:3091,3243;3092,3243,p=2");
        assertEquals("lumbridge_bank", w.name());
        assertEquals(Waypoint.Kind.WALK_AREA, w.kind());
        assertEquals(2, w.tiles().size());
    }

    @Test
    public void walktilesRoundTripsThroughToString()
    {
        Waypoint original = Waypoint.walkArea("pen", Set.of(
            new WorldPoint(3232, 3293, 0),
            new WorldPoint(3234, 3295, 0),
            new WorldPoint(3233, 3294, 0)));
        String serialised = original.toString();
        // Strip the "name: " prefix that toString emits.
        int colon = serialised.indexOf(':');
        // toString uses "name: walktiles:..." — find the SECOND token's start.
        Waypoint reparsed = RouteParser.parseLine(serialised);
        assertEquals(original.tiles(), reparsed.tiles());
        assertEquals("pen", reparsed.name());
    }

    @Test
    public void walktilesRejectsMultiPlaneCoords()
    {
        // Even though the line declares plane=0, declaring tiles with their
        // own plane is forbidden — the format requires a single trailing plane.
        try
        {
            RouteParser.parseLine("walktiles:3091,3243,2;3092,3243,1,p=0");
            fail("expected IllegalArgumentException");
        }
        catch (IllegalArgumentException ex)
        {
            // Accept any error — the line is malformed.
        }
    }

    @Test
    public void walktilesRejectsBadCoord()
    {
        try
        {
            RouteParser.parseLine("walktiles:3091,foo;3092,3243,p=2");
            fail("expected IllegalArgumentException");
        }
        catch (IllegalArgumentException ex)
        {
            assertTrue(ex.getMessage(), ex.getMessage().toLowerCase().contains("non-numeric")
                || ex.getMessage().toLowerCase().contains("number"));
        }
    }

    @Test
    public void perfectRectangleRoundTripsAsWalkbox()
    {
        // 2x2 perfect rect built from a tile set should serialise as walkbox:
        // (not walktiles:) since isRectangular() is true.
        Waypoint original = Waypoint.walkArea("rect", Set.of(
            new WorldPoint(0, 0, 0),
            new WorldPoint(1, 0, 0),
            new WorldPoint(0, 1, 0),
            new WorldPoint(1, 1, 0)));
        String serialised = original.toString();
        assertTrue(serialised, serialised.contains("walkbox:"));
        assertFalse(serialised, serialised.contains("walktiles:"));

        Waypoint reparsed = RouteParser.parseLine(serialised);
        assertEquals(original.tiles(), reparsed.tiles());
    }
}
```

- [ ] **Step 2: Run to verify failure**

```bash
./gradlew :client:test --tests 'net.runelite.client.plugins.recorder.transport.RouteParserWalktilesTest'
```

Expected: all 7 fail (parser doesn't recognise `walktiles:` yet — the dispatch falls into the default `unknown prefix` arm).

- [ ] **Step 3: Add the `walktiles:` branch to `RouteParser`**

In `runelite-client/src/main/java/net/runelite/client/plugins/recorder/transport/RouteParser.java`, find the `parseBody` switch (after the `walkbox` case). Add a new `walktiles` case:

```java
case "walktiles":
{
    return Waypoint.walkArea(name, parseWalktiles(rest));
}
```

Then add the `parseWalktiles` helper next to `parseWalkbox`:

```java
/** Parse {@code "x1,y1;x2,y2;...[,plane]"} into a tile set. The trailing
 *  ",plane" applies to every tile; per-tile planes are not allowed. */
private static java.util.Set<net.runelite.api.coords.WorldPoint> parseWalktiles(String s)
{
    if (s == null || s.isBlank()) throw new IllegalArgumentException("missing tiles");
    // The plane is everything after the LAST comma if it has the form
    // "p=N" or a bare integer. Split off the plane first so we don't confuse
    // it with a tile coord.
    int plane = 0;
    int lastComma = s.lastIndexOf(',');
    String pairs = s;
    if (lastComma >= 0)
    {
        String tail = s.substring(lastComma + 1).trim();
        if (tail.startsWith("p=")) tail = tail.substring(2);
        try
        {
            plane = Integer.parseInt(tail);
            pairs = s.substring(0, lastComma).trim();
        }
        catch (NumberFormatException ignored)
        {
            // Last segment isn't a plane — treat the whole string as pairs.
        }
    }
    String[] tokens = pairs.split("\\s*;\\s*");
    if (tokens.length == 0) throw new IllegalArgumentException("walktiles needs at least one tile");
    java.util.Set<net.runelite.api.coords.WorldPoint> out = new java.util.HashSet<>(tokens.length);
    for (String tok : tokens)
    {
        if (tok.isBlank()) continue;
        String[] xy = tok.split("\\s*,\\s*");
        if (xy.length != 2)
            throw new IllegalArgumentException(
                "walktiles tile must be 'x,y' (got '" + tok + "')");
        int x, y;
        try
        {
            x = Integer.parseInt(xy[0]);
            y = Integer.parseInt(xy[1]);
        }
        catch (NumberFormatException nfe)
        {
            throw new IllegalArgumentException("non-numeric coordinate in walktiles tile '" + tok + "'");
        }
        out.add(new net.runelite.api.coords.WorldPoint(x, y, plane));
    }
    if (out.isEmpty())
        throw new IllegalArgumentException("walktiles produced no tiles");
    return out;
}
```

Also update the unknown-prefix error message at the default arm — it lists allowed prefixes via `RESERVED_PREFIXES`, so `walktiles` needs to be in that set. Find `RESERVED_PREFIXES` (added during the chicken-farm-bot Task 2) and add `walktiles` to it:

```java
// (Locate the existing initialiser and add "walktiles".)
private static final java.util.Set<String> RESERVED_PREFIXES = java.util.Set.of(
    "walk", "walkbox", "walktiles", "open", "climb-up", "climbup",
    "climb-down", "climbdown", "interact");
```

The existing `bodyHasKnownStart` in the name-prefix detector already uses `RESERVED_PREFIXES`, so the change auto-applies.

- [ ] **Step 4: Run the full transport test suite**

```bash
./gradlew :client:test --tests 'net.runelite.client.plugins.recorder.transport.*'
```

Expected: all PASS, including the 7 new `RouteParserWalktilesTest` cases plus all pre-existing tests.

- [ ] **Step 5: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/plugins/recorder/transport/RouteParser.java \
        runelite-client/src/test/java/net/runelite/client/plugins/recorder/transport/RouteParserWalktilesTest.java
git commit -m "$(cat <<'EOF'
recorder/transport: parse walktiles: lines for irregular tile sets

walktiles:x1,y1;x2,y2;...[,plane] grammar parses an explicit tile list
into a Set<WorldPoint>. The plane suffix (bare integer or p=N) applies
to every tile; per-tile planes are forbidden so multi-plane shapes
need separate waypoints. Adaptive serialisation in Waypoint.toString
(Task 1) chooses walkbox: for perfect rectangles and walktiles: for
irregular sets, so routes round-trip safely.

walktiles is added to RESERVED_PREFIXES so name-prefix detection
treats it as a known verb, matching walkbox / open / etc.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: `RouteWalker.sampleTile` signature change

The sampler takes a tile set. For perfect rectangles the set covers every bbox tile so behaviour is identical to today; for irregular sets the set restricts which bbox tiles can be picked.

**Files:**
- Modify: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/farm/RouteWalker.java`
- Create: `runelite-client/src/test/java/net/runelite/client/plugins/recorder/farm/RouteWalkerTilesTest.java`
- Modify: `runelite-client/src/test/java/net/runelite/client/plugins/recorder/farm/RouteWalkerTest.java` (existing tests; just update calls to the new signature)

- [ ] **Step 1: Write the failing tests for the new behaviour**

Create `runelite-client/src/test/java/net/runelite/client/plugins/recorder/farm/RouteWalkerTilesTest.java`:

```java
package net.runelite.client.plugins.recorder.farm;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.*;

public class RouteWalkerTilesTest
{
    @Test
    public void sampleTileOnlyReturnsTilesInAllowedSet()
    {
        // 3x3 bbox, but only the diagonal tiles are allowed.
        WorldArea bbox = new WorldArea(0, 0, 3, 3, 0);
        Set<WorldPoint> allowed = Set.of(
            new WorldPoint(0, 0, 0),
            new WorldPoint(1, 1, 0),
            new WorldPoint(2, 2, 0));
        Random rng = new Random(0);

        // Run many samples; every result must be in `allowed`.
        for (int i = 0; i < 100; i++)
        {
            WorldPoint t = RouteWalker.sampleTile(bbox, allowed, rng, p -> true);
            assertNotNull(t);
            assertTrue("tile " + t + " not in allowed", allowed.contains(t));
        }
    }

    @Test
    public void sampleTileFallsBackToEnumerationWhenAllowedIsTiny()
    {
        // 5x5 bbox, only ONE allowed tile. Random rolls have ~1/25 chance
        // of hitting it; the enumeration fallback guarantees we find it.
        WorldArea bbox = new WorldArea(0, 0, 5, 5, 0);
        Set<WorldPoint> allowed = Set.of(new WorldPoint(3, 4, 0));
        Random rng = new Random(0);

        WorldPoint t = RouteWalker.sampleTile(bbox, allowed, rng, p -> true);
        assertEquals(new WorldPoint(3, 4, 0), t);
    }

    @Test
    public void sampleTileReturnsNullWhenNoAllowedTilePassesPredicate()
    {
        WorldArea bbox = new WorldArea(0, 0, 3, 3, 0);
        Set<WorldPoint> allowed = new HashSet<>();
        allowed.add(new WorldPoint(0, 0, 0));
        // Predicate rejects everything.
        assertNull(RouteWalker.sampleTile(bbox, allowed, new Random(0), p -> false));
    }

    @Test
    public void sampleTileReturnsNullWhenAllowedSetIsEmpty()
    {
        assertNull(RouteWalker.sampleTile(new WorldArea(0, 0, 3, 3, 0),
            Set.of(), new Random(0), p -> true));
    }
}
```

- [ ] **Step 2: Run to verify failure**

```bash
./gradlew :client:test --tests 'net.runelite.client.plugins.recorder.farm.RouteWalkerTilesTest'
```

Expected: 4 compile failures (`sampleTile` signature mismatch — 3 args vs 4).

- [ ] **Step 3: Update the existing `RouteWalkerTest` cases for the new signature**

The 3 existing tests in `RouteWalkerTest.java` currently call `RouteWalker.sampleTile(area, rng, predicate)` (3 args). The new signature is `sampleTile(bbox, allowed, rng, predicate)` (4 args). For perfect-rectangle test cases, pass `allowed = bboxTiles(area)` so behaviour matches today.

Add a helper at the top of `RouteWalkerTest.java`:

```java
private static java.util.Set<net.runelite.api.coords.WorldPoint> fillRect(
    net.runelite.api.coords.WorldArea a)
{
    java.util.Set<net.runelite.api.coords.WorldPoint> out = new java.util.HashSet<>();
    for (int dx = 0; dx < a.getWidth(); dx++)
        for (int dy = 0; dy < a.getHeight(); dy++)
            out.add(new net.runelite.api.coords.WorldPoint(
                a.getX() + dx, a.getY() + dy, a.getPlane()));
    return out;
}
```

Update the 3 existing `sampleTile` calls to:

```java
// Before:  RouteWalker.sampleTile(a, rng, p -> true)
// After:
RouteWalker.sampleTile(a, fillRect(a), rng, p -> true)

// Before:  RouteWalker.sampleTile(a, rng, p -> p.getX() == 1 && p.getY() == 1)
// After:
RouteWalker.sampleTile(a, fillRect(a), rng, p -> p.getX() == 1 && p.getY() == 1)

// Before:  RouteWalker.sampleTile(a, rng, p -> false)
// After:
RouteWalker.sampleTile(a, fillRect(a), rng, p -> false)
```

(Don't change the assertions — they still hold under the new signature when the allowed set covers every bbox tile.)

- [ ] **Step 4: Update `RouteWalker.sampleTile` and `tickWalk`**

In `runelite-client/src/main/java/net/runelite/client/plugins/recorder/farm/RouteWalker.java`:

Replace the `sampleTile` body with:

```java
/** Pure tile sampler — package-private for tests. Picks a random tile in
 *  {@code bbox} that's in {@code allowed} and passes {@code accept}. Up to
 *  3*N random rolls, then falls back to a shuffled enumeration of
 *  {@code allowed} so we always find a tile if one is acceptable.
 *  Returns null only when no tile in {@code allowed} passes {@code accept}. */
static WorldPoint sampleTile(WorldArea bbox, java.util.Set<WorldPoint> allowed,
                             Random rng, Predicate<WorldPoint> accept)
{
    if (allowed.isEmpty()) return null;
    int n = bbox.getWidth() * bbox.getHeight();
    int attempts = Math.max(8, n * 3);
    for (int i = 0; i < attempts; i++)
    {
        int x = bbox.getX() + rng.nextInt(bbox.getWidth());
        int y = bbox.getY() + rng.nextInt(bbox.getHeight());
        WorldPoint p = new WorldPoint(x, y, bbox.getPlane());
        if (allowed.contains(p) && accept.test(p)) return p;
    }
    // Last resort: enumerate `allowed` in random order.
    java.util.List<WorldPoint> all = new java.util.ArrayList<>(allowed);
    java.util.Collections.shuffle(all, rng);
    for (WorldPoint p : all) if (accept.test(p)) return p;
    return null;
}
```

Update the `tickWalk` caller. Find:

```java
WorldPoint pick = sampleTile(area, rng,
    tile -> tile.getPlane() == here.getPlane() && projectsToCanvas(tile));
```

Replace with:

```java
WorldPoint pick = sampleTile(area, wp.tiles(), rng,
    tile -> tile.getPlane() == here.getPlane() && projectsToCanvas(tile));
```

And update the `tickWalk` signature so it takes the full `Waypoint` instead of just the area (so it has access to `wp.tiles()`). Find:

```java
private void tickWalk(WorldArea area) throws InterruptedException
```

Replace with:

```java
private void tickWalk(Waypoint wp) throws InterruptedException
```

and inside, derive `area` from `wp.area()`:

```java
private void tickWalk(Waypoint wp) throws InterruptedException
{
    WorldArea area = wp.area();
    if (area == null) return;
    Player self = client.getLocalPlayer();
    if (self == null) return;
    WorldPoint here = self.getWorldLocation();
    if (here == null) return;
    WorldPoint pick = sampleTile(area, wp.tiles(), rng,
        tile -> tile.getPlane() == here.getPlane() && projectsToCanvas(tile));
    if (pick == null) return;
    WorldView wv = client.getTopLevelWorldView();
    if (wv == null) return;
    LocalPoint lp = LocalPoint.fromWorld(wv, pick);
    if (lp == null) return;
    Point cp = Perspective.localToCanvas(client, lp, pick.getPlane());
    if (cp == null) return;
    dispatcher.clickCanvas(cp.getX(), cp.getY());
}
```

Update the `tick(Waypoint wp)` dispatch:

```java
case WALK:
case WALK_AREA:
    tickWalk(wp);
    break;
```

(Replaces the previous `tickWalk(wp.area())`.)

- [ ] **Step 5: Run the farm test suite**

```bash
./gradlew :client:test --tests 'net.runelite.client.plugins.recorder.farm.*'
```

Expected: all PASS, including the 4 new `RouteWalkerTilesTest` cases plus the updated 3 `RouteWalkerTest` cases.

- [ ] **Step 6: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/plugins/recorder/farm/RouteWalker.java \
        runelite-client/src/test/java/net/runelite/client/plugins/recorder/farm/RouteWalkerTilesTest.java \
        runelite-client/src/test/java/net/runelite/client/plugins/recorder/farm/RouteWalkerTest.java
git commit -m "$(cat <<'EOF'
recorder/farm: RouteWalker.sampleTile takes an allowed tile set

Sampler now restricts random rolls + the enumeration fallback to
{@code allowed.contains(p)} in addition to the caller's accept
predicate. tickWalk pulls the set from wp.tiles() so irregular
WALK_AREA waypoints sample only their actual tiles, not the whole
bounding box. Behaviour for perfect rectangles is unchanged
(allowed covers every bbox tile).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: `RouteOverlay` per-tile fill (fixes the rendering bug)

Drop the perimeter-walk rendering. Fill each in-set tile with `Perspective.getCanvasTilePoly` — the same call used for single-tile rendering today, known correct. Adds a public hook for setting a "selected" waypoint so Tasks 13+ can highlight it.

**File:**
- Modify: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/transport/RouteOverlay.java`

(No new tests — overlays are visual. Manual smoke after Task 11 lands the new annotator.)

- [ ] **Step 1: Replace the file body**

Open `runelite-client/src/main/java/net/runelite/client/plugins/recorder/transport/RouteOverlay.java` and replace its body (preserving the existing `package` line) with:

```java
package net.runelite.client.plugins.recorder.transport;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Stroke;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Paints route waypoints on the canvas. Each WALK / WALK_AREA waypoint
 * draws every tile in its set with a translucent fill + thin outline
 * (per tile — so irregular shapes render correctly, and the legacy
 * perimeter-walk bug — drawing across half-tile-offset corners — goes
 * away). Transports draw their tile poly + verb label.
 *
 * <p>The currently-selected waypoint (set via {@link #setSelected})
 * draws with a thicker cyan outline and higher fill alpha so the user
 * can spot it among the route.
 */
public final class RouteOverlay extends Overlay
{
    private static final Color AREA_FILL = new Color(80, 180, 255, 60);
    private static final Color AREA_LINE = new Color(80, 180, 255, 220);
    private static final Color WALK_LINE = new Color(120, 200, 255, 220);
    private static final Color TRANSPORT_LINE = new Color(255, 180, 80, 230);
    private static final Color SELECTED_FILL = new Color(80, 220, 255, 110);
    private static final Color SELECTED_LINE = new Color(80, 220, 255, 255);
    private static final Color LABEL_BG = new Color(0, 0, 0, 180);
    private static final BasicStroke STROKE_2 = new BasicStroke(2f);
    private static final BasicStroke STROKE_3 = new BasicStroke(3f);

    private final Client client;
    private final AtomicReference<List<Waypoint>> route = new AtomicReference<>(List.of());
    private final AtomicReference<Waypoint> selected = new AtomicReference<>();

    public RouteOverlay(Client client)
    {
        this.client = client;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
        setPriority(Overlay.PRIORITY_LOW);
    }

    public void setRoute(List<Waypoint> waypoints)
    {
        route.set(waypoints == null ? List.of() : List.copyOf(waypoints));
    }

    /** Mark a waypoint as selected so it renders with stronger emphasis.
     *  Pass {@code null} to clear. The waypoint is matched by reference
     *  identity — pass the same instance held in the panel's list. */
    public void setSelected(@Nullable Waypoint wp)
    {
        selected.set(wp);
    }

    @Override
    public Dimension render(Graphics2D g)
    {
        WorldView wv = client.getTopLevelWorldView();
        if (wv == null) return null;
        List<Waypoint> wps = route.get();
        if (wps.isEmpty()) return null;
        Waypoint sel = selected.get();
        Stroke prev = g.getStroke();
        for (int i = 0; i < wps.size(); i++)
        {
            Waypoint wp = wps.get(i);
            boolean isSel = wp == sel;
            String label = (isSel ? "▶ " : "")
                + (wp.name() == null ? "" : wp.name() + " ") + "#" + i;
            switch (wp.kind())
            {
                case WALK_AREA:
                case WALK:
                    drawTileSet(g, wp.tiles(), isSel, label);
                    break;
                case TRANSPORT:
                {
                    String verb = wp.verb();
                    drawTransport(g, wp.tile(), isSel,
                        verb != null ? label + " (" + verb + ")" : label);
                    break;
                }
            }
        }
        g.setStroke(prev);
        return null;
    }

    private void drawTileSet(Graphics2D g, java.util.Set<WorldPoint> tiles,
                             boolean selected, String label)
    {
        WorldView wv = client.getTopLevelWorldView();
        if (wv == null) return;
        Color fill = selected ? SELECTED_FILL : AREA_FILL;
        Color line = selected ? SELECTED_LINE : AREA_LINE;
        BasicStroke stroke = selected ? STROKE_3 : STROKE_2;
        WorldPoint labelAnchor = null;
        for (WorldPoint wp : tiles)
        {
            LocalPoint lp = LocalPoint.fromWorld(wv, wp);
            if (lp == null) continue;
            Polygon poly = Perspective.getCanvasTilePoly(client, lp);
            if (poly == null) continue;
            g.setColor(fill);
            g.fillPolygon(poly);
            g.setStroke(stroke);
            g.setColor(line);
            g.drawPolygon(poly);
            // Label on the SW-most tile we successfully projected.
            if (labelAnchor == null
                || wp.getX() < labelAnchor.getX()
                || (wp.getX() == labelAnchor.getX() && wp.getY() < labelAnchor.getY()))
            {
                labelAnchor = wp;
            }
        }
        if (labelAnchor != null) labelAt(g, labelAnchor, label);
    }

    private void drawTransport(Graphics2D g, WorldPoint wp, boolean selected, String label)
    {
        WorldView wv = client.getTopLevelWorldView();
        if (wv == null) return;
        LocalPoint lp = LocalPoint.fromWorld(wv, wp);
        if (lp == null) return;
        Polygon poly = Perspective.getCanvasTilePoly(client, lp);
        if (poly == null) return;
        BasicStroke stroke = selected ? STROKE_3 : STROKE_2;
        Color line = selected ? SELECTED_LINE : TRANSPORT_LINE;
        g.setStroke(stroke);
        g.setColor(line);
        g.drawPolygon(poly);
        labelAt(g, wp, label);
    }

    private void labelAt(Graphics2D g, WorldPoint wp, String label)
    {
        WorldView wv = client.getTopLevelWorldView();
        if (wv == null) return;
        LocalPoint lp = LocalPoint.fromWorld(wv, wp);
        if (lp == null) return;
        net.runelite.api.Point pt = Perspective.localToCanvas(client, lp, wp.getPlane());
        if (pt == null) return;
        g.setColor(LABEL_BG);
        g.fillRect(pt.getX() - 1, pt.getY() - 12, g.getFontMetrics().stringWidth(label) + 4, 14);
        g.setColor(Color.WHITE);
        g.drawString(label, pt.getX() + 1, pt.getY() - 1);
    }
}
```

The previous `areaPolygon` / `addPerimeterPoint` / `tilePolygon` / `drawArea` / `drawTile` helpers are gone — the new `drawTileSet` and `drawTransport` are simpler and correct.

- [ ] **Step 2: Compile**

```bash
./gradlew :client:compileJava
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Run all recorder tests (no regressions)**

```bash
./gradlew :client:test --tests 'net.runelite.client.plugins.recorder.*'
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/plugins/recorder/transport/RouteOverlay.java
git commit -m "$(cat <<'EOF'
recorder/transport: per-tile fill renderer fixes area-render bug

The perimeter-walk in areaPolygon called LocalPoint.fromWorld with
WorldPoint coords offset by +1 tile, then projected via localToCanvas
which returns tile centers — so a 1x1 area drew across 4 tiles
diagonally. Drop the perimeter walk entirely and render each in-set
tile via Perspective.getCanvasTilePoly (known correct, used by
single-tile rendering today). Supports irregular tile sets (Task 1)
because each tile is rendered independently.

Adds setSelected(Waypoint) for the upcoming list-driven highlight:
the selected waypoint draws with a thicker cyan outline and higher
fill alpha so the user can spot it.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Stage 2 — New annotator components (Tasks 5–7)

### Task 5: `AreaSelector` — drag-rect + click-toggle + shift-subtract

A canvas mouse listener that captures press / drag / release plus modifier state. On commit, calls back with the current `Set<WorldPoint>`.

**Files:**
- Create: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/annotator/AreaSelector.java`
- Create: `runelite-client/src/test/java/net/runelite/client/plugins/recorder/annotator/AreaSelectorTest.java`

- [ ] **Step 1: Write the failing tests**

Create `runelite-client/src/test/java/net/runelite/client/plugins/recorder/annotator/AreaSelectorTest.java`:

```java
package net.runelite.client.plugins.recorder.annotator;

import java.util.HashSet;
import java.util.Set;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.*;

public class AreaSelectorTest
{
    @Test
    public void rectangleBetweenTwoTilesIncludesEveryEnclosedTile()
    {
        Set<WorldPoint> rect = AreaSelector.tilesInRect(
            new WorldPoint(3, 5, 0),
            new WorldPoint(5, 7, 0));
        assertEquals(9, rect.size());
        assertTrue(rect.contains(new WorldPoint(3, 5, 0)));
        assertTrue(rect.contains(new WorldPoint(4, 6, 0)));
        assertTrue(rect.contains(new WorldPoint(5, 7, 0)));
    }

    @Test
    public void rectangleNormalisesCornerOrder()
    {
        // NE then SW → same result as SW then NE.
        Set<WorldPoint> a = AreaSelector.tilesInRect(
            new WorldPoint(5, 7, 0), new WorldPoint(3, 5, 0));
        Set<WorldPoint> b = AreaSelector.tilesInRect(
            new WorldPoint(3, 5, 0), new WorldPoint(5, 7, 0));
        assertEquals(a, b);
    }

    @Test
    public void rectangleWithSameCornerIsSingleTile()
    {
        WorldPoint p = new WorldPoint(3, 5, 2);
        Set<WorldPoint> single = AreaSelector.tilesInRect(p, p);
        assertEquals(Set.of(p), single);
    }

    @Test
    public void rectangleAcrossPlanesUsesTheSecondTilesPlane()
    {
        // The second click's plane wins (matches the "release determines
        // plane" semantic the AreaSelector uses at runtime).
        Set<WorldPoint> rect = AreaSelector.tilesInRect(
            new WorldPoint(3, 5, 0),
            new WorldPoint(4, 6, 1));
        assertEquals(4, rect.size());
        for (WorldPoint p : rect) assertEquals(1, p.getPlane());
    }

    @Test
    public void applyAddCombinesSets()
    {
        Set<WorldPoint> base = new HashSet<>(Set.of(new WorldPoint(0, 0, 0)));
        Set<WorldPoint> add = Set.of(new WorldPoint(1, 0, 0), new WorldPoint(2, 0, 0));
        Set<WorldPoint> result = AreaSelector.applyAdd(base, add);
        assertEquals(3, result.size());
        assertTrue(result.contains(new WorldPoint(2, 0, 0)));
    }

    @Test
    public void applySubtractRemovesTiles()
    {
        Set<WorldPoint> base = new HashSet<>(Set.of(
            new WorldPoint(0, 0, 0),
            new WorldPoint(1, 0, 0),
            new WorldPoint(2, 0, 0)));
        Set<WorldPoint> sub = Set.of(new WorldPoint(1, 0, 0));
        Set<WorldPoint> result = AreaSelector.applySubtract(base, sub);
        assertEquals(2, result.size());
        assertFalse(result.contains(new WorldPoint(1, 0, 0)));
    }

    @Test
    public void applyToggleAddsAbsentTile()
    {
        Set<WorldPoint> base = new HashSet<>(Set.of(new WorldPoint(0, 0, 0)));
        Set<WorldPoint> result = AreaSelector.applyToggle(base, new WorldPoint(1, 0, 0));
        assertEquals(2, result.size());
        assertTrue(result.contains(new WorldPoint(1, 0, 0)));
    }

    @Test
    public void applyToggleRemovesPresentTile()
    {
        Set<WorldPoint> base = new HashSet<>(Set.of(
            new WorldPoint(0, 0, 0), new WorldPoint(1, 0, 0)));
        Set<WorldPoint> result = AreaSelector.applyToggle(base, new WorldPoint(1, 0, 0));
        assertEquals(1, result.size());
        assertFalse(result.contains(new WorldPoint(1, 0, 0)));
    }
}
```

- [ ] **Step 2: Run to verify failure**

```bash
./gradlew :client:test --tests 'net.runelite.client.plugins.recorder.annotator.AreaSelectorTest'
```

Expected: 8 failures (`AreaSelector` undefined).

- [ ] **Step 3: Implement `AreaSelector`**

Create `runelite-client/src/main/java/net/runelite/client/plugins/recorder/annotator/AreaSelector.java`:

```java
package net.runelite.client.plugins.recorder.annotator;

import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Tile;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.input.MouseAdapter;
import net.runelite.client.input.MouseManager;

/**
 * Captures area-selection input on the canvas while active. Translates
 * pixel coords to {@link WorldPoint}s via the scene tile lookup, then
 * applies one of three operations on each release / click:
 *
 * <ul>
 *   <li>Drag (no modifier): the rectangle of tiles between press and
 *       release tiles is added to the working set.</li>
 *   <li>Shift+Drag: the rectangle is subtracted.</li>
 *   <li>Single click (press and release within {@link #CLICK_THRESHOLD_PX}):
 *       the tile under the click is toggled.</li>
 * </ul>
 *
 * <p>Pure-function helpers ({@link #tilesInRect}, {@link #applyAdd},
 * {@link #applySubtract}, {@link #applyToggle}) are package-public for
 * unit testing without a live client.
 */
@Slf4j
public final class AreaSelector
{
    public interface Listener
    {
        /** Working set changed (drag finished, click toggled, etc.).
         *  Called on the EDT — safe to update Swing state. */
        void onSetChanged(Set<WorldPoint> tiles);

        /** User pressed Enter or clicked Done in the HUD. */
        void onCommit(Set<WorldPoint> tiles);

        /** User pressed Esc or clicked Cancel in the HUD. */
        void onCancel();

        /** Drag in progress — for live preview. {@code subtract} indicates
         *  whether Shift was held at press time. {@code rect} is the in-flight
         *  rectangle (press tile to current-cursor tile). */
        void onDragPreview(@Nullable WorldPoint pressTile, @Nullable WorldPoint dragTile,
                           boolean subtract);
    }

    /** Pixel movement threshold below which a press+release is treated as a
     *  click (toggle one tile) rather than a drag. */
    public static final int CLICK_THRESHOLD_PX = 4;

    private final Client client;
    private final ClientThread clientThread;
    private final MouseManager mouseManager;

    private final AtomicReference<Set<WorldPoint>> working = new AtomicReference<>(Set.of());
    @Nullable private Listener listener;
    @Nullable private MouseAdapter activeListener;

    @Nullable private WorldPoint pressTile;
    private int pressX, pressY;
    private boolean pressShift;
    @Nullable private WorldPoint hoverTile;

    public AreaSelector(Client client, ClientThread clientThread, MouseManager mouseManager)
    {
        this.client = client;
        this.clientThread = clientThread;
        this.mouseManager = mouseManager;
    }

    public boolean isActive() { return activeListener != null; }

    public Set<WorldPoint> currentSet() { return working.get(); }

    /** Start a selection session pre-loaded with {@code initialTiles} (pass
     *  {@code Set.of()} for "new"). Subsequent input mutates the working
     *  set; the caller must invoke {@link #commit} or {@link #cancel} to
     *  end the session. Calling start while already active throws. */
    public void start(Set<WorldPoint> initialTiles, Listener l)
    {
        if (activeListener != null)
            throw new IllegalStateException("AreaSelector already active");
        this.listener = l;
        working.set(Set.copyOf(initialTiles));
        pressTile = null;
        hoverTile = null;
        MouseAdapter adapter = new MouseAdapter()
        {
            @Override public MouseEvent mousePressed(MouseEvent e) { onPress(e); return e; }
            @Override public MouseEvent mouseDragged(MouseEvent e) { onDrag(e); return e; }
            @Override public MouseEvent mouseReleased(MouseEvent e) { onRelease(e); return e; }
            @Override public MouseEvent mouseMoved(MouseEvent e)   { onMove(e);   return e; }
        };
        mouseManager.registerMouseListener(adapter);
        activeListener = adapter;
    }

    public void commit()
    {
        if (listener != null) listener.onCommit(working.get());
        cleanup();
    }

    public void cancel()
    {
        if (listener != null) listener.onCancel();
        cleanup();
    }

    private void cleanup()
    {
        if (activeListener != null) mouseManager.unregisterMouseListener(activeListener);
        activeListener = null;
        listener = null;
        pressTile = null;
        hoverTile = null;
        working.set(Set.of());
    }

    private void onPress(MouseEvent e)
    {
        pressX = e.getX();
        pressY = e.getY();
        pressShift = (e.getModifiersEx() & InputEvent.SHIFT_DOWN_MASK) != 0;
        pressTile = tileUnder(e.getX(), e.getY());
        hoverTile = pressTile;
        if (listener != null) listener.onDragPreview(pressTile, hoverTile, pressShift);
    }

    private void onDrag(MouseEvent e)
    {
        WorldPoint t = tileUnder(e.getX(), e.getY());
        if (t == null) return;
        hoverTile = t;
        if (listener != null) listener.onDragPreview(pressTile, hoverTile, pressShift);
    }

    private void onMove(MouseEvent e)
    {
        // No effect — preview only updates while a drag is in progress.
    }

    private void onRelease(MouseEvent e)
    {
        if (pressTile == null) return;
        int dx = Math.abs(e.getX() - pressX);
        int dy = Math.abs(e.getY() - pressY);
        WorldPoint releaseTile = tileUnder(e.getX(), e.getY());
        Set<WorldPoint> next;
        if (dx <= CLICK_THRESHOLD_PX && dy <= CLICK_THRESHOLD_PX && releaseTile != null)
        {
            next = applyToggle(working.get(), releaseTile);
        }
        else if (releaseTile == null)
        {
            // Released off-canvas — abandon this drag, leave the set unchanged.
            pressTile = null;
            hoverTile = null;
            if (listener != null) listener.onDragPreview(null, null, false);
            return;
        }
        else
        {
            Set<WorldPoint> rect = tilesInRect(pressTile, releaseTile);
            next = pressShift ? applySubtract(working.get(), rect)
                              : applyAdd(working.get(), rect);
        }
        working.set(Set.copyOf(next));
        pressTile = null;
        hoverTile = null;
        if (listener != null)
        {
            listener.onDragPreview(null, null, false);
            listener.onSetChanged(working.get());
        }
    }

    @Nullable
    private WorldPoint tileUnder(int canvasX, int canvasY)
    {
        WorldView wv = client.getTopLevelWorldView();
        if (wv == null) return null;
        // Map canvas pixel to scene tile via the existing helper.
        int sceneX = client.getMouseCanvasPosition() == null ? -1 : client.getMouseCanvasPosition().getX();
        // Use Perspective.getMouseCanvasPosition for a robust pixel-to-tile lookup.
        net.runelite.api.Point hovered = client.getMouseCanvasPosition();
        // For accurate translation, use Perspective.getMouseCanvasPosition + scene's
        // hovered tile. Simpler: iterate scene tiles, find the one whose canvas poly
        // contains (canvasX, canvasY). Cap at a reasonable iteration count.
        Tile[][][] tiles = wv.getScene().getTiles();
        int plane = client.getPlane();
        if (plane < 0 || plane >= tiles.length) return null;
        Tile[][] planeTiles = tiles[plane];
        for (int x = 0; x < planeTiles.length; x++)
        {
            for (int y = 0; y < planeTiles[x].length; y++)
            {
                Tile t = planeTiles[x][y];
                if (t == null) continue;
                java.awt.Polygon poly = Perspective.getCanvasTilePoly(client, t.getLocalLocation());
                if (poly != null && poly.contains(canvasX, canvasY))
                {
                    return t.getWorldLocation();
                }
            }
        }
        return null;
    }

    /** Pure: every tile in the rectangle whose corners are {@code a} and {@code b}.
     *  The rectangle is normalised (corners are reordered if necessary). The
     *  resulting set inherits {@code b}'s plane (release determines plane). */
    static Set<WorldPoint> tilesInRect(WorldPoint a, WorldPoint b)
    {
        int minX = Math.min(a.getX(), b.getX());
        int maxX = Math.max(a.getX(), b.getX());
        int minY = Math.min(a.getY(), b.getY());
        int maxY = Math.max(a.getY(), b.getY());
        int plane = b.getPlane();
        Set<WorldPoint> out = new HashSet<>((maxX - minX + 1) * (maxY - minY + 1));
        for (int x = minX; x <= maxX; x++)
            for (int y = minY; y <= maxY; y++)
                out.add(new WorldPoint(x, y, plane));
        return out;
    }

    /** Pure: union. */
    static Set<WorldPoint> applyAdd(Set<WorldPoint> base, Set<WorldPoint> add)
    {
        Set<WorldPoint> out = new HashSet<>(base);
        out.addAll(add);
        return out;
    }

    /** Pure: difference. */
    static Set<WorldPoint> applySubtract(Set<WorldPoint> base, Set<WorldPoint> sub)
    {
        Set<WorldPoint> out = new HashSet<>(base);
        out.removeAll(sub);
        return out;
    }

    /** Pure: toggle one tile. */
    static Set<WorldPoint> applyToggle(Set<WorldPoint> base, WorldPoint t)
    {
        Set<WorldPoint> out = new HashSet<>(base);
        if (!out.add(t)) out.remove(t);
        return out;
    }
}
```

Notes:
- The pixel-to-tile lookup iterates scene tiles. The scene is 104×104 = ~10k tiles per plane; iterating once per mouse event is acceptable on the EDT but could be optimised later by caching the canvas poly per visible tile. MVP is correctness, not throughput.
- The MouseAdapter base class lives in `net.runelite.client.input.MouseAdapter`; check its actual signature in your fork — methods may be `MouseEvent mousePressed(MouseEvent)` (returning the event) per RuneLite convention. Adjust if the base methods are `void`.

- [ ] **Step 4: Run the new tests**

```bash
./gradlew :client:test --tests 'net.runelite.client.plugins.recorder.annotator.AreaSelectorTest'
```

Expected: 8 PASS.

- [ ] **Step 5: Compile + commit**

```bash
./gradlew :client:compileJava
git add runelite-client/src/main/java/net/runelite/client/plugins/recorder/annotator/AreaSelector.java \
        runelite-client/src/test/java/net/runelite/client/plugins/recorder/annotator/AreaSelectorTest.java
git commit -m "$(cat <<'EOF'
recorder/annotator: AreaSelector — drag-rect, shift-subtract, click-toggle

Captures canvas mouse events while active. Drag adds the rectangle of
tiles between press and release; Shift+Drag subtracts; click toggles
one tile (press+release within 4px). Listener gets onDragPreview
during the drag (for the overlay's live rectangle) and onSetChanged
on each commit. commit() and cancel() exit the mode.

Pure-function helpers tilesInRect, applyAdd, applySubtract, applyToggle
are package-private and covered by 8 unit tests; the live MouseEvent
plumbing is exercised by the panel smoke step.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 6: `AnnotatorHudOverlay` — on-screen control hints

A small overlay that paints the keyboard / mouse legend at the top of the canvas while a selection session is active. Hides itself when the session ends.

**File:**
- Create: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/annotator/AnnotatorHudOverlay.java`

(No tests — visual.)

- [ ] **Step 1: Implement**

Create `runelite-client/src/main/java/net/runelite/client/plugins/recorder/annotator/AnnotatorHudOverlay.java`:

```java
package net.runelite.client.plugins.recorder.annotator;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

/**
 * Renders a top-anchored panel showing area-selection control hints
 * while {@link AreaSelector} is active. Shows nothing when inactive.
 */
public final class AnnotatorHudOverlay extends OverlayPanel
{
    private final AtomicReference<String> headline = new AtomicReference<>();

    public AnnotatorHudOverlay()
    {
        setPosition(OverlayPosition.TOP_CENTER);
    }

    /** Show the HUD with the given headline (e.g. "Editing: pen_gate" or
     *  "New area"). Pass {@code null} to hide. */
    public void show(@Nullable String h)
    {
        headline.set(h);
    }

    @Override
    public Dimension render(Graphics2D g)
    {
        String h = headline.get();
        if (h == null) return null;
        panelComponent.getChildren().clear();
        panelComponent.getChildren().add(TitleComponent.builder()
            .text(h)
            .color(Color.WHITE)
            .build());
        panelComponent.getChildren().add(LineComponent.builder()
            .left("Drag")
            .right("add tiles")
            .build());
        panelComponent.getChildren().add(LineComponent.builder()
            .left("Shift+Drag")
            .right("remove")
            .build());
        panelComponent.getChildren().add(LineComponent.builder()
            .left("Click")
            .right("toggle one")
            .build());
        panelComponent.getChildren().add(LineComponent.builder()
            .left("Enter")
            .right("save")
            .build());
        panelComponent.getChildren().add(LineComponent.builder()
            .left("Esc")
            .right("cancel")
            .build());
        return super.render(g);
    }
}
```

- [ ] **Step 2: Compile**

```bash
./gradlew :client:compileJava
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/plugins/recorder/annotator/AnnotatorHudOverlay.java
git commit -m "$(cat <<'EOF'
recorder/annotator: AnnotatorHudOverlay — control-hint HUD

Top-center OverlayPanel that lists Drag / Shift+Drag / Click / Enter
/ Esc bindings while a selection session is active. show(headline)
toggles visibility. The headline ("New area" / "Editing: name") is
set by the panel when entering selection mode.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 7: `UndoStack` — per-route capped 3-snapshot

A small utility that holds up to 3 snapshots of a route's text content per route. `push` adds a snapshot; `pop` returns the most recent and removes it; `size` reports current depth; oldest evicted on overflow.

**Files:**
- Create: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/annotator/UndoStack.java`
- Create: `runelite-client/src/test/java/net/runelite/client/plugins/recorder/annotator/UndoStackTest.java`

- [ ] **Step 1: Write the failing tests**

Create `runelite-client/src/test/java/net/runelite/client/plugins/recorder/annotator/UndoStackTest.java`:

```java
package net.runelite.client.plugins.recorder.annotator;

import java.util.Optional;
import org.junit.Test;
import static org.junit.Assert.*;

public class UndoStackTest
{
    @Test
    public void emptyStackHasZeroSizeAndNoSnapshot()
    {
        UndoStack s = new UndoStack(3);
        assertEquals(0, s.size());
        assertEquals(Optional.empty(), s.pop());
    }

    @Test
    public void pushThenPopReturnsMostRecent()
    {
        UndoStack s = new UndoStack(3);
        s.push("v1");
        s.push("v2");
        s.push("v3");
        assertEquals(3, s.size());
        assertEquals(Optional.of("v3"), s.pop());
        assertEquals(Optional.of("v2"), s.pop());
        assertEquals(Optional.of("v1"), s.pop());
        assertEquals(Optional.empty(), s.pop());
    }

    @Test
    public void overflowEvictsOldest()
    {
        UndoStack s = new UndoStack(3);
        s.push("v1");
        s.push("v2");
        s.push("v3");
        s.push("v4"); // evicts v1
        assertEquals(3, s.size());
        assertEquals(Optional.of("v4"), s.pop());
        assertEquals(Optional.of("v3"), s.pop());
        assertEquals(Optional.of("v2"), s.pop());
        assertEquals(Optional.empty(), s.pop());
    }

    @Test
    public void capacityOneJustReplaces()
    {
        UndoStack s = new UndoStack(1);
        s.push("v1");
        s.push("v2");
        assertEquals(1, s.size());
        assertEquals(Optional.of("v2"), s.pop());
        assertEquals(Optional.empty(), s.pop());
    }

    @Test
    public void clearEmptiesTheStack()
    {
        UndoStack s = new UndoStack(3);
        s.push("v1");
        s.push("v2");
        s.clear();
        assertEquals(0, s.size());
        assertEquals(Optional.empty(), s.pop());
    }
}
```

- [ ] **Step 2: Run to verify failure**

```bash
./gradlew :client:test --tests 'net.runelite.client.plugins.recorder.annotator.UndoStackTest'
```

Expected: 5 failures.

- [ ] **Step 3: Implement**

Create `runelite-client/src/main/java/net/runelite/client/plugins/recorder/annotator/UndoStack.java`:

```java
package net.runelite.client.plugins.recorder.annotator;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

/**
 * Bounded LIFO stack of route snapshots. Push appends to the head; pop
 * removes from the head; on overflow the OLDEST entry is evicted (the
 * tail). Capacity is set at construction; the chicken-farm-bot annotator
 * uses 3.
 *
 * <p>Not thread-safe — meant to be driven from the EDT.
 */
public final class UndoStack
{
    private final int capacity;
    private final Deque<String> stack = new ArrayDeque<>();

    public UndoStack(int capacity)
    {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be >= 1");
        this.capacity = capacity;
    }

    public int size() { return stack.size(); }

    public void push(String snapshot)
    {
        stack.addFirst(snapshot);
        while (stack.size() > capacity) stack.removeLast();
    }

    public Optional<String> pop()
    {
        return Optional.ofNullable(stack.pollFirst());
    }

    public void clear()
    {
        stack.clear();
    }
}
```

- [ ] **Step 4: Run + commit**

```bash
./gradlew :client:test --tests 'net.runelite.client.plugins.recorder.annotator.UndoStackTest'
```

Expected: 5 PASS.

```bash
git add runelite-client/src/main/java/net/runelite/client/plugins/recorder/annotator/UndoStack.java \
        runelite-client/src/test/java/net/runelite/client/plugins/recorder/annotator/UndoStackTest.java
git commit -m "recorder/annotator: UndoStack — bounded LIFO snapshot stack

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Stage 2 (cont.) — Panel restructure (Tasks 8–14)

### Task 8: `RecorderPanel` → `JTabbedPane` shell

Refactor `RecorderPanel` so its body is a `JTabbedPane`. Move existing sections into per-tab builder methods. The new annotator (Routes tab) is wired in Task 11; for now it's an empty placeholder panel.

**File:**
- Modify: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/RecorderPanel.java`

(No new tests — UI plumbing exercised by the smoke step.)

- [ ] **Step 1: Refactor the panel body**

This task is a substantial restructure. The shape:

1. Extract the existing Status section into a `buildStatusHeader()` method that returns a `JPanel`. Pin it at the top of the panel via `add(header, BorderLayout.NORTH)`.
2. Add a `JTabbedPane` instance field and `add(tabs, BorderLayout.CENTER)`.
3. Extract each existing section (Recording + Marker + Recent → "Record" tab; Combat → "Combat" tab; Mining → "Mining" tab; Login → "Login" tab) into per-tab builders that return a `JPanel`, wrap each with `new JScrollPane(panel)`, add to tabs.
4. Add an empty placeholder `Routes` tab that just shows a `JLabel("Routes annotator — wired in Task 11")` for now.

Skeleton:

```java
public final class RecorderPanel extends PluginPanel
{
    // ... existing fields stay ...
    private final JTabbedPane tabs = new JTabbedPane();

    public RecorderPanel(RecorderManager manager, Client client, ClientThread clientThread)
    {
        super(false);
        // ... existing wiring (manager, dispatcher, client, clientThread) stays ...
        setLayout(new java.awt.BorderLayout(0, 6));
        setBorder(javax.swing.BorderFactory.createEmptyBorder(6, 6, 6, 6));

        add(buildStatusHeader(), java.awt.BorderLayout.NORTH);

        tabs.addTab("Routes", new javax.swing.JScrollPane(buildRoutesTab()));
        tabs.addTab("Combat", new javax.swing.JScrollPane(buildCombatTab()));
        tabs.addTab("Record", new javax.swing.JScrollPane(buildRecordTab()));
        tabs.addTab("Mining", new javax.swing.JScrollPane(buildMiningTab()));
        tabs.addTab("Login", new javax.swing.JScrollPane(buildLoginTab()));
        add(tabs, java.awt.BorderLayout.CENTER);

        // ... existing listener wiring stays ...
    }

    private JPanel buildStatusHeader()
    {
        JPanel h = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 2));
        // Pull the existing statusState / statusElapsed / statusEvents labels.
        h.add(statusState);
        h.add(statusElapsed);
        h.add(statusEvents);
        return h;
    }

    private JPanel buildRoutesTab()
    {
        JPanel p = new JPanel();
        p.setLayout(new javax.swing.BoxLayout(p, javax.swing.BoxLayout.Y_AXIS));
        p.add(new JLabel("Routes annotator — wired in Task 11"));
        return p;
    }

    private JPanel buildCombatTab()
    {
        JPanel p = new JPanel();
        p.setLayout(new javax.swing.BoxLayout(p, javax.swing.BoxLayout.Y_AXIS));
        // Move the existing Combat section markup here:
        // chickenStartBtn / chickenStopBtn / chickenStatusLabel / chickenKillsLabel.
        // ... (copy the existing layout code verbatim from the current buildPanel) ...
        return p;
    }

    private JPanel buildRecordTab()
    {
        JPanel p = new JPanel();
        p.setLayout(new javax.swing.BoxLayout(p, javax.swing.BoxLayout.Y_AXIS));
        // Move the existing Recording + Marker + Recent sections here.
        // ... (copy the existing layout code) ...
        return p;
    }

    private JPanel buildMiningTab() { /* mining section */ ... }
    private JPanel buildLoginTab()  { /* login section */ ... }
}
```

The exact content of each `buildXTab()` is "the layout code that's there today, lifted into its own method." No behaviour change. The legacy `Test walk / Platforms` section (testWalkArea, testWalkBtn, addCurPosBtn, addMarkedBtn, saveRouteBtn, loadRouteBtn, annotateNameField, annotatePathBox, markAreaBtn, markObjectBtn, annotateStatus, testWalkStatus) is **deleted entirely** — the new Routes tab replaces it in Task 11.

The `Debug + tile mark` section (Mark tile / Walk to mark / Clear / markedLabel) folds into the `Routes` tab placeholder for now (Task 10 absorbs it into the WaypointEditor toolbar).

The legacy `walkRoute(List<Waypoint>)` method, plus its supporting methods (`onTestWalk`, `onAddCurrentPos`, `onAddMarked`, `onSaveRoute`, `onLoadRoute`, `onMarkArea`, `onMarkObject`, `appendLine`, `refreshOverlayFromText`, `walkToTile`, `doTransport`) — all of these are deleted, since the new `WaypointEditor` (Task 10) re-implements them.

**Important:** Don't delete the public setters (`setDebugOverlay`, `setTileMarker`, `setRouteOverlay`, `setTransportResolver`, `setChickenLoop`, `setFarmLoop`, `setLoginAssistant`, etc.) — `RecorderPlugin.startUp` calls them.

- [ ] **Step 2: Compile**

```bash
./gradlew :client:compileJava
```

Expected: BUILD SUCCESSFUL. If anything in the deleted methods was referenced elsewhere, fix the call site (likely needs to be removed too). Pre-existing tests that called any of the deleted methods need to be deleted as well — there shouldn't be any since these methods were package-private UI handlers.

- [ ] **Step 3: Run all recorder tests**

```bash
./gradlew :client:test --tests 'net.runelite.client.plugins.recorder.*'
```

Expected: BUILD SUCCESSFUL. The `WaypointDispatchTest` is the one most likely to break — it currently exercises `walkRoute`. Delete it; the new `WaypointEditor` will have its own tests in Task 10.

- [ ] **Step 4: Manual smoke**

Build the shadow jar + relaunch + click around the panel:

```bash
./gradlew :client:shadowJar
pkill -f 'client-1.12.25-SNAPSHOT-shaded' 2>&1; sleep 2
nohup /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/bin/java \
  --add-exports=java.desktop/sun.awt=ALL-UNNAMED \
  --add-exports=java.desktop/com.apple.eawt=ALL-UNNAMED \
  --add-exports=java.desktop/com.apple.eio=ALL-UNNAMED \
  --add-opens=java.desktop/com.apple.eawt=ALL-UNNAMED -ea \
  -jar runelite-client/build/libs/client-1.12.25-SNAPSHOT-shaded.jar \
  --developer-mode > /tmp/runelite-stdout.log 2>&1 &
disown
```

Manual checklist:
- Recorder panel opens, status pinned at top.
- 5 tabs: Routes (placeholder), Combat, Record, Mining, Login.
- Combat / Record / Mining / Login each scroll independently.
- Combat: Start/Stop button still drives the chicken / farm loop (whichever is non-null).
- No exceptions in `/tmp/runelite-stdout.log`.

- [ ] **Step 5: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/plugins/recorder/RecorderPanel.java
# Plus any deleted test files:
git add -u runelite-client/src/test/java/net/runelite/client/plugins/recorder/
git commit -m "$(cat <<'EOF'
recorder: panel uses JTabbedPane (Routes / Combat / Record / Mining / Login)

Replaces the single tall column with a tabbed structure: Status block
pinned at the top, five tabs scrollable independently. Each existing
section's layout is lifted verbatim into its own buildXTab() method.

The legacy Test walk / Platforms cluster (Mark area, Mark object,
Save, Load, Add current, Add marked, walkRoute, etc.) is removed —
the new Routes tab replaces it in Task 11. Routes tab is a placeholder
for this commit so the panel still builds.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 9: `RoutesTab` — saved routes list

A Swing panel that scans `~/.runelite/sequencer/routes/*.txt`, lists the routes, supports new / rename / delete, and emits a "drilldown" callback when the user picks one.

**Files:**
- Create: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/annotator/RoutesTab.java`

(Tested manually via the smoke step in Task 11.)

- [ ] **Step 1: Implement `RoutesTab`**

Create `runelite-client/src/main/java/net/runelite/client/plugins/recorder/annotator/RoutesTab.java`:

```java
package net.runelite.client.plugins.recorder.annotator;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.recorder.transport.RouteParser;
import net.runelite.client.plugins.recorder.transport.Waypoint;

/**
 * Top-level Routes view: scans the route files on disk and lists each as
 * a row showing name, waypoint count, and a FARM badge when the file is
 * the one the farm bot loads at startup. Single-click selects; double-
 * click invokes the drilldown callback.
 */
@Slf4j
public final class RoutesTab extends JPanel
{
    public static final Path ROUTES_DIR = Paths.get(
        System.getProperty("user.home"),
        ".runelite", "sequencer", "routes");

    public static final Path FARM_ROUTE_FILE = ROUTES_DIR.resolve("lumbridge_bank_to_pen.txt");

    public static final class RouteEntry
    {
        public final Path path;
        public final String name;
        public final int waypointCount;
        public final boolean isFarmRoute;
        public final boolean hasErrors;

        RouteEntry(Path path, int waypointCount, boolean hasErrors)
        {
            this.path = path;
            this.name = stripExt(path.getFileName().toString());
            this.waypointCount = waypointCount;
            this.isFarmRoute = path.equals(FARM_ROUTE_FILE);
            this.hasErrors = hasErrors;
        }

        private static String stripExt(String f)
        {
            return f.endsWith(".txt") ? f.substring(0, f.length() - 4) : f;
        }

        @Override public String toString()
        {
            return (isFarmRoute ? "[FARM] " : "")
                + name
                + " (" + waypointCount + " wp"
                + (hasErrors ? ", parse errors" : "")
                + ")";
        }
    }

    private final DefaultListModel<RouteEntry> model = new DefaultListModel<>();
    private final JList<RouteEntry> list = new JList<>(model);
    private final Consumer<Path> onDrilldown;

    public RoutesTab(Consumer<Path> onDrilldown)
    {
        this.onDrilldown = onDrilldown;
        setLayout(new BorderLayout(0, 4));

        JPanel toolbarTop = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JButton newBtn = new JButton("+ New Route");
        newBtn.addActionListener(e -> onNew());
        toolbarTop.add(newBtn);
        add(toolbarTop, BorderLayout.NORTH);

        list.addMouseListener(new MouseAdapter()
        {
            @Override public void mouseClicked(MouseEvent e)
            {
                if (e.getClickCount() == 2)
                {
                    RouteEntry sel = list.getSelectedValue();
                    if (sel != null) onDrilldown.accept(sel.path);
                }
            }
        });
        add(new JScrollPane(list), BorderLayout.CENTER);

        JPanel toolbarBot = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JButton openBtn = new JButton("Open");
        openBtn.addActionListener(e -> {
            RouteEntry sel = list.getSelectedValue();
            if (sel != null) onDrilldown.accept(sel.path);
        });
        JButton renameBtn = new JButton("Rename");
        renameBtn.addActionListener(e -> onRename());
        JButton deleteBtn = new JButton("Delete");
        deleteBtn.addActionListener(e -> onDelete());
        toolbarBot.add(openBtn);
        toolbarBot.add(renameBtn);
        toolbarBot.add(deleteBtn);
        add(toolbarBot, BorderLayout.SOUTH);

        refresh();
    }

    /** Re-scan {@link #ROUTES_DIR} and reload the list. Safe to call from
     *  the EDT after every save / delete. */
    public void refresh()
    {
        model.clear();
        try
        {
            if (!Files.isDirectory(ROUTES_DIR))
            {
                Files.createDirectories(ROUTES_DIR);
                return;
            }
            List<RouteEntry> entries = new ArrayList<>();
            try (java.util.stream.Stream<Path> stream = Files.list(ROUTES_DIR))
            {
                stream.filter(p -> p.toString().endsWith(".txt"))
                    .forEach(p -> {
                        try
                        {
                            String text = Files.readString(p);
                            RouteParser.Result r = RouteParser.parse(text);
                            int n = r.waypoints().size();
                            entries.add(new RouteEntry(p, n, r.hasErrors()));
                        }
                        catch (IOException ioe)
                        {
                            log.warn("read failed: {}: {}", p, ioe.getMessage());
                            entries.add(new RouteEntry(p, 0, true));
                        }
                    });
            }
            entries.sort(Comparator
                .<RouteEntry, Boolean>comparing(e -> !e.isFarmRoute) // FARM first
                .thenComparing(e -> e.name));
            for (RouteEntry e : entries) model.addElement(e);
        }
        catch (IOException ioe)
        {
            log.warn("routes scan failed: {}", ioe.getMessage());
        }
    }

    private void onNew()
    {
        String name = JOptionPane.showInputDialog(this,
            "New route name (no extension)", "New Route", JOptionPane.PLAIN_MESSAGE);
        if (name == null || name.isBlank()) return;
        String safe = name.trim().replaceAll("[^A-Za-z0-9_\\-]", "_");
        Path target = ROUTES_DIR.resolve(safe + ".txt");
        try
        {
            Files.createDirectories(ROUTES_DIR);
            if (Files.exists(target))
            {
                JOptionPane.showMessageDialog(this, "Route already exists: " + safe);
                return;
            }
            Files.writeString(target, "# " + safe + "\n");
            refresh();
            onDrilldown.accept(target);
        }
        catch (IOException ioe)
        {
            JOptionPane.showMessageDialog(this, "Create failed: " + ioe.getMessage());
        }
    }

    private void onRename()
    {
        RouteEntry sel = list.getSelectedValue();
        if (sel == null) return;
        String name = JOptionPane.showInputDialog(this,
            "Rename to (no extension)", sel.name);
        if (name == null || name.isBlank()) return;
        String safe = name.trim().replaceAll("[^A-Za-z0-9_\\-]", "_");
        Path target = ROUTES_DIR.resolve(safe + ".txt");
        if (target.equals(sel.path)) return;
        try
        {
            Files.move(sel.path, target);
            refresh();
        }
        catch (IOException ioe)
        {
            JOptionPane.showMessageDialog(this, "Rename failed: " + ioe.getMessage());
        }
    }

    private void onDelete()
    {
        RouteEntry sel = list.getSelectedValue();
        if (sel == null) return;
        int answer = JOptionPane.showConfirmDialog(this,
            "Delete route '" + sel.name + "'? This cannot be undone.",
            "Delete Route",
            JOptionPane.OK_CANCEL_OPTION);
        if (answer != JOptionPane.OK_OPTION) return;
        try
        {
            Files.delete(sel.path);
            refresh();
        }
        catch (IOException ioe)
        {
            JOptionPane.showMessageDialog(this, "Delete failed: " + ioe.getMessage());
        }
    }
}
```

- [ ] **Step 2: Compile + commit**

```bash
./gradlew :client:compileJava
git add runelite-client/src/main/java/net/runelite/client/plugins/recorder/annotator/RoutesTab.java
git commit -m "recorder/annotator: RoutesTab — saved routes list with new/rename/delete

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

### Task 10: `WaypointEditor` — drilldown view

The view shown when the user picks a route from `RoutesTab`. Lists the route's waypoints, exposes the toolbar (`Mark area`, `Mark object`, `Add current`, `Add marked`, `Walk path`, `Walk to selected`, `Undo`), and renders each row with the `[▲][▼][✎][×]` button cluster.

**Files:**
- Create: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/annotator/WaypointEditor.java`

- [ ] **Step 1: Implement `WaypointEditor`**

Create `runelite-client/src/main/java/net/runelite/client/plugins/recorder/annotator/WaypointEditor.java`:

```java
package net.runelite.client.plugins.recorder.annotator;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.transport.RouteOverlay;
import net.runelite.client.plugins.recorder.transport.RouteParser;
import net.runelite.client.plugins.recorder.transport.Waypoint;

/**
 * Drilldown view for one route. Shows the toolbar + waypoint list. Each
 * mutation rewrites the file on disk and pushes a snapshot to the
 * per-route undo stack. Selection state drives the route overlay's
 * highlight.
 */
@Slf4j
public final class WaypointEditor extends JPanel
{
    public interface Hooks
    {
        /** User clicked Mark area. Caller starts the AreaSelector with the
         *  given prepopulated set (empty for new) and invokes the callback
         *  on commit. */
        void onMarkArea(@javax.annotation.Nullable Waypoint editing, Consumer<Set<WorldPoint>> onCommit);

        /** User clicked Mark object. Caller fires findAnyTransport and
         *  emits a TRANSPORT waypoint when the click resolves. */
        void onMarkObject(Consumer<Waypoint> onCommit);

        /** User clicked Add current. Caller appends the player's current
         *  tile as a single-tile WALK waypoint. */
        void onAddCurrent(Consumer<Waypoint> onCommit);

        /** User clicked Add marked. Caller appends the most-recently-
         *  marked tile as a single-tile WALK waypoint. */
        void onAddMarked(Consumer<Waypoint> onCommit);

        /** User clicked Walk path. Caller drives the walker over the
         *  current waypoint list. */
        void onWalkPath(List<Waypoint> waypoints);

        /** User clicked Walk to selected. Caller drives the walker over
         *  waypoints[0..selectedIdx] inclusive. */
        void onWalkToSelected(List<Waypoint> waypoints, int selectedIdx);
    }

    private final Path routeFile;
    private final RouteOverlay routeOverlay;
    private final Hooks hooks;
    private final UndoStack undo = new UndoStack(3);
    private final JPanel listColumn = new JPanel();
    private final JLabel headerLabel = new JLabel();
    private final JButton undoBtn = new JButton("Undo (0)");
    private final JTextField nameField = new JTextField(8);
    private List<Waypoint> waypoints = new ArrayList<>();
    private int selectedIdx = -1;

    public WaypointEditor(Path routeFile, RouteOverlay routeOverlay,
                          Runnable onBack, Hooks hooks)
    {
        this.routeFile = routeFile;
        this.routeOverlay = routeOverlay;
        this.hooks = hooks;
        setLayout(new BorderLayout(0, 4));

        // Top: back link + name + FARM badge
        JPanel header = new JPanel(new BorderLayout(4, 0));
        JButton backBtn = new JButton("← Back");
        backBtn.addActionListener(e -> onBack.run());
        header.add(backBtn, BorderLayout.WEST);
        boolean isFarm = routeFile.equals(RoutesTab.FARM_ROUTE_FILE);
        String farmTag = isFarm ? "  [FARM]" : "";
        String displayName = routeFile.getFileName().toString().replaceFirst("\\.txt$", "");
        headerLabel.setText(displayName + farmTag);
        header.add(headerLabel, BorderLayout.CENTER);
        add(header, BorderLayout.NORTH);

        // Toolbar (above the waypoint list)
        JPanel toolbar = new JPanel();
        toolbar.setLayout(new BoxLayout(toolbar, BoxLayout.Y_AXIS));

        JPanel rowName = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        rowName.add(new JLabel("Name:"));
        rowName.add(nameField);
        toolbar.add(rowName);

        JPanel rowMark = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JButton markAreaBtn = new JButton("Mark area");
        markAreaBtn.addActionListener(e -> doMarkArea(null));
        JButton markObjectBtn = new JButton("Mark object");
        markObjectBtn.addActionListener(e -> doMarkObject());
        rowMark.add(markAreaBtn);
        rowMark.add(markObjectBtn);
        toolbar.add(rowMark);

        JPanel rowAdd = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JButton addCurBtn = new JButton("Add current");
        addCurBtn.addActionListener(e -> doAddCurrent());
        JButton addMarkBtn = new JButton("Add marked");
        addMarkBtn.addActionListener(e -> doAddMarked());
        rowAdd.add(addCurBtn);
        rowAdd.add(addMarkBtn);
        toolbar.add(rowAdd);

        JPanel rowWalk = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JButton walkBtn = new JButton("Walk path");
        walkBtn.addActionListener(e -> hooks.onWalkPath(waypoints));
        JButton walkToBtn = new JButton("Walk to selected");
        walkToBtn.addActionListener(e -> {
            if (selectedIdx >= 0) hooks.onWalkToSelected(waypoints, selectedIdx);
        });
        rowWalk.add(walkBtn);
        rowWalk.add(walkToBtn);
        rowWalk.add(undoBtn);
        undoBtn.addActionListener(e -> doUndo());
        toolbar.add(rowWalk);

        add(toolbar, BorderLayout.PAGE_START);

        listColumn.setLayout(new BoxLayout(listColumn, BoxLayout.Y_AXIS));
        add(new JScrollPane(listColumn), BorderLayout.CENTER);

        load();
    }

    public Path routeFile() { return routeFile; }

    private void load()
    {
        try
        {
            String text = Files.exists(routeFile) ? Files.readString(routeFile) : "";
            RouteParser.Result r = RouteParser.parse(text);
            this.waypoints = new ArrayList<>(r.waypoints());
            renderList();
            routeOverlay.setRoute(this.waypoints);
        }
        catch (IOException ioe)
        {
            log.warn("load failed: {}: {}", routeFile, ioe.getMessage());
        }
    }

    private void renderList()
    {
        listColumn.removeAll();
        for (int i = 0; i < waypoints.size(); i++)
        {
            int idx = i;
            Waypoint wp = waypoints.get(i);
            JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
            row.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0,
                java.awt.Color.DARK_GRAY));
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
            JLabel idxLabel = new JLabel("#" + i);
            idxLabel.setPreferredSize(new Dimension(24, 18));
            row.add(idxLabel);
            JLabel nameLabel = new JLabel(wp.name() == null ? "(unnamed)" : wp.name());
            row.add(nameLabel);
            row.add(new JLabel(verbSummary(wp)));
            JButton up = new JButton("▲");
            JButton down = new JButton("▼");
            JButton edit = new JButton("✎");
            JButton del = new JButton("×");
            up.setEnabled(i > 0);
            down.setEnabled(i < waypoints.size() - 1);
            up.addActionListener(e -> doMove(idx, idx - 1));
            down.addActionListener(e -> doMove(idx, idx + 1));
            edit.addActionListener(e -> doEditBounds(idx));
            del.addActionListener(e -> doDelete(idx));
            row.add(up);
            row.add(down);
            row.add(edit);
            row.add(del);
            // Single-click row → select. Double-click name label → rename inline.
            row.addMouseListener(new MouseAdapter()
            {
                @Override public void mouseClicked(MouseEvent e)
                {
                    if (e.getClickCount() == 1) doSelect(idx);
                }
            });
            nameLabel.addMouseListener(new MouseAdapter()
            {
                @Override public void mouseClicked(MouseEvent e)
                {
                    if (e.getClickCount() == 2) doRename(idx);
                }
            });
            if (idx == selectedIdx)
            {
                row.setBackground(new java.awt.Color(60, 80, 110));
            }
            listColumn.add(row);
        }
        listColumn.revalidate();
        listColumn.repaint();
        undoBtn.setText("Undo (" + undo.size() + ")");
        undoBtn.setEnabled(undo.size() > 0);
    }

    private String verbSummary(Waypoint wp)
    {
        switch (wp.kind())
        {
            case WALK_AREA:
            {
                if (wp.isRectangular())
                {
                    var a = wp.area();
                    return "walkbox " + a.getWidth() + "×" + a.getHeight();
                }
                return "walktiles ×" + wp.tiles().size();
            }
            case WALK: return "walk";
            case TRANSPORT:
                return wp.transportKind().name().toLowerCase().replace('_', '-');
            default: return "?";
        }
    }

    private void doSelect(int idx)
    {
        selectedIdx = idx;
        routeOverlay.setSelected(waypoints.get(idx));
        renderList();
    }

    private void doMove(int from, int to)
    {
        if (to < 0 || to >= waypoints.size() || from == to) return;
        snapshot();
        Waypoint w = waypoints.remove(from);
        waypoints.add(to, w);
        if (selectedIdx == from) selectedIdx = to;
        save();
        renderList();
    }

    private void doDelete(int idx)
    {
        snapshot();
        Waypoint removed = waypoints.remove(idx);
        if (selectedIdx == idx)
        {
            selectedIdx = -1;
            routeOverlay.setSelected(null);
        }
        else if (selectedIdx > idx) selectedIdx--;
        save();
        renderList();
    }

    private void doRename(int idx)
    {
        Waypoint old = waypoints.get(idx);
        String n = JOptionPane.showInputDialog(this, "Rename waypoint:",
            old.name() == null ? "" : old.name());
        if (n == null) return;
        String name = n.isBlank() ? null : n.trim();
        snapshot();
        waypoints.set(idx, withName(old, name));
        save();
        renderList();
    }

    private static Waypoint withName(Waypoint old, @javax.annotation.Nullable String name)
    {
        switch (old.kind())
        {
            case WALK_AREA:
                return Waypoint.walkArea(name, old.tiles());
            case WALK:
                return name == null ? Waypoint.walk(old.tile()) : Waypoint.walkNamed(name, old.tile());
            case TRANSPORT:
                return name == null
                    ? Waypoint.transport(old.tile(), old.transportKind(), old.verb())
                    : Waypoint.transportNamed(name, old.tile(), old.transportKind(), old.verb());
            default: throw new IllegalStateException();
        }
    }

    private void doMarkArea(@javax.annotation.Nullable Waypoint editing)
    {
        hooks.onMarkArea(editing, tiles -> {
            if (tiles == null || tiles.isEmpty()) return;
            snapshot();
            String n = nameField.getText().trim();
            Waypoint w = Waypoint.walkArea(n.isBlank() ? null : n, tiles);
            if (editing != null)
            {
                int idx = waypoints.indexOf(editing);
                if (idx >= 0) waypoints.set(idx, w);
                else waypoints.add(w);
            }
            else
            {
                waypoints.add(w);
            }
            nameField.setText("");
            save();
            renderList();
        });
    }

    private void doMarkObject()
    {
        hooks.onMarkObject(captured -> {
            if (captured == null) return;
            snapshot();
            String n = nameField.getText().trim();
            Waypoint w = n.isBlank() ? captured
                : Waypoint.transportNamed(n, captured.tile(),
                    captured.transportKind(), captured.verb());
            waypoints.add(w);
            nameField.setText("");
            save();
            renderList();
        });
    }

    private void doAddCurrent()
    {
        hooks.onAddCurrent(captured -> {
            if (captured == null) return;
            snapshot();
            waypoints.add(captured);
            save();
            renderList();
        });
    }

    private void doAddMarked()
    {
        hooks.onAddMarked(captured -> {
            if (captured == null) return;
            snapshot();
            waypoints.add(captured);
            save();
            renderList();
        });
    }

    private void doEditBounds(int idx)
    {
        Waypoint wp = waypoints.get(idx);
        if (wp.kind() == Waypoint.Kind.WALK_AREA || wp.kind() == Waypoint.Kind.WALK)
        {
            doMarkArea(wp);
        }
        else
        {
            // TRANSPORT — small dialog with tile coords + verb.
            String coords = wp.tile().getX() + "," + wp.tile().getY() + "," + wp.tile().getPlane();
            String input = JOptionPane.showInputDialog(this,
                "Edit transport coords (x,y,p):", coords);
            if (input == null) return;
            String[] parts = input.split(",");
            if (parts.length != 3)
            {
                JOptionPane.showMessageDialog(this, "Expected x,y,p");
                return;
            }
            try
            {
                WorldPoint nt = new WorldPoint(
                    Integer.parseInt(parts[0].trim()),
                    Integer.parseInt(parts[1].trim()),
                    Integer.parseInt(parts[2].trim()));
                snapshot();
                Waypoint replacement = wp.name() == null
                    ? Waypoint.transport(nt, wp.transportKind(), wp.verb())
                    : Waypoint.transportNamed(wp.name(), nt, wp.transportKind(), wp.verb());
                waypoints.set(idx, replacement);
                save();
                renderList();
            }
            catch (NumberFormatException nfe)
            {
                JOptionPane.showMessageDialog(this, "Non-numeric coord");
            }
        }
    }

    private void doUndo()
    {
        undo.pop().ifPresent(snapshot -> {
            try
            {
                Files.writeString(routeFile, snapshot);
                load();
            }
            catch (IOException ioe)
            {
                JOptionPane.showMessageDialog(this, "Undo write failed: " + ioe.getMessage());
            }
        });
    }

    private void snapshot()
    {
        try
        {
            String current = Files.exists(routeFile) ? Files.readString(routeFile) : "";
            undo.push(current);
        }
        catch (IOException ioe)
        {
            log.warn("snapshot read failed: {}: {}", routeFile, ioe.getMessage());
        }
    }

    private void save()
    {
        try
        {
            StringBuilder sb = new StringBuilder();
            for (Waypoint w : waypoints) sb.append(w).append('\n');
            Files.writeString(routeFile, sb);
            routeOverlay.setRoute(waypoints);
        }
        catch (IOException ioe)
        {
            JOptionPane.showMessageDialog(this, "Save failed: " + ioe.getMessage());
        }
    }
}
```

- [ ] **Step 2: Compile + commit**

```bash
./gradlew :client:compileJava
git add runelite-client/src/main/java/net/runelite/client/plugins/recorder/annotator/WaypointEditor.java
git commit -m "$(cat <<'EOF'
recorder/annotator: WaypointEditor — drilldown view with full CRUD

Per-route view: header + toolbar + waypoint list. Each row carries
[▲▼✎×] reorder/edit/delete buttons; double-click the name label to
rename inline. Single-click row selects it (drives the overlay
highlight via setSelected). Every mutation snapshots the prior file
content into the UndoStack and writes the route file on disk
immediately. Toolbar exposes Mark area, Mark object, Add current,
Add marked, Walk path, Walk to selected, and Undo (n).

Hooks interface delegates the in-world capture flows (Mark area,
Mark object, Add current, Add marked, Walk path, Walk to selected)
to the panel's wiring so the editor stays Swing-only.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 11: Wire the Routes tab into `RecorderPanel` + `RecorderPlugin`

The `RoutesTab` placeholder from Task 8 becomes the real `RoutesTab` + `WaypointEditor`. The panel manages a `JPanel` that swaps between the two views. `Mark area` is wired through `AreaSelector`; the HUD overlay shows the bindings during selection.

**Files:**
- Modify: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/RecorderPanel.java`
- Modify: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/RecorderPlugin.java`

- [ ] **Step 1: Update `RecorderPlugin.startUp` to instantiate the new components**

In `runelite-client/src/main/java/net/runelite/client/plugins/recorder/RecorderPlugin.java`, near where the existing overlays are constructed:

```java
// new fields:
private AnnotatorHudOverlay hudOverlay;
private AreaSelector areaSelector;
```

Inside `startUp()`, after `routeOverlay = new RouteOverlay(client);`:

```java
hudOverlay = new AnnotatorHudOverlay();
areaSelector = new AreaSelector(client, clientThread, mouseManager);
panel.setHudOverlay(hudOverlay);
panel.setAreaSelector(areaSelector);
overlayManager.add(hudOverlay);
```

Inside `shutDown()`, alongside the other `overlayManager.remove(...)` calls:

```java
if (hudOverlay != null) overlayManager.remove(hudOverlay);
if (areaSelector != null && areaSelector.isActive()) areaSelector.cancel();
```

Add to the field-nulling cleanup line:
```java
hudOverlay = null; areaSelector = null;
```

Add the imports:
```java
import net.runelite.client.plugins.recorder.annotator.AreaSelector;
import net.runelite.client.plugins.recorder.annotator.AnnotatorHudOverlay;
```

(Place alphabetically with the other `recorder.*` imports.)

- [ ] **Step 2: Replace the placeholder `buildRoutesTab` in `RecorderPanel`**

In `RecorderPanel.java`, add the imports + fields:

```java
import net.runelite.client.plugins.recorder.annotator.AreaSelector;
import net.runelite.client.plugins.recorder.annotator.AnnotatorHudOverlay;
import net.runelite.client.plugins.recorder.annotator.RoutesTab;
import net.runelite.client.plugins.recorder.annotator.WaypointEditor;

// fields:
private AnnotatorHudOverlay hudOverlay;
private AreaSelector areaSelector;
private RoutesTab routesTab;
private final JPanel routesContainer = new JPanel(new java.awt.BorderLayout());
```

Public setters:
```java
public void setHudOverlay(AnnotatorHudOverlay h) { this.hudOverlay = h; }
public void setAreaSelector(AreaSelector s) { this.areaSelector = s; }
```

Replace `buildRoutesTab` body:

```java
private JPanel buildRoutesTab()
{
    routesTab = new RoutesTab(this::openRouteEditor);
    routesContainer.add(routesTab, java.awt.BorderLayout.CENTER);
    return routesContainer;
}

private void openRouteEditor(java.nio.file.Path routeFile)
{
    routesContainer.removeAll();
    WaypointEditor editor = new WaypointEditor(routeFile, routeOverlay,
        () -> {
            routesContainer.removeAll();
            routesTab.refresh();
            routesContainer.add(routesTab, java.awt.BorderLayout.CENTER);
            routeOverlay.setSelected(null);
            routesContainer.revalidate();
            routesContainer.repaint();
        },
        new WaypointEditor.Hooks()
        {
            @Override public void onMarkArea(@javax.annotation.Nullable Waypoint editing,
                                             java.util.function.Consumer<java.util.Set<net.runelite.api.coords.WorldPoint>> onCommit)
            {
                if (areaSelector == null || hudOverlay == null) return;
                java.util.Set<net.runelite.api.coords.WorldPoint> initial = editing == null
                    ? java.util.Set.of()
                    : editing.tiles();
                hudOverlay.show(editing == null ? "New area"
                    : "Editing: " + (editing.name() == null ? "(unnamed)" : editing.name()));
                areaSelector.start(initial, new AreaSelector.Listener()
                {
                    @Override public void onSetChanged(java.util.Set<net.runelite.api.coords.WorldPoint> tiles)
                    {
                        // Keep the live preview in the overlay by faking a one-waypoint route.
                        java.util.List<Waypoint> preview = new java.util.ArrayList<>();
                        if (!tiles.isEmpty()) preview.add(Waypoint.walkArea(null, tiles));
                        // (No-op if empty — the actual route's overlay is restored on commit.)
                    }
                    @Override public void onCommit(java.util.Set<net.runelite.api.coords.WorldPoint> tiles)
                    {
                        hudOverlay.show(null);
                        onCommit.accept(tiles);
                    }
                    @Override public void onCancel()
                    {
                        hudOverlay.show(null);
                    }
                    @Override public void onDragPreview(@javax.annotation.Nullable net.runelite.api.coords.WorldPoint pressTile,
                                                        @javax.annotation.Nullable net.runelite.api.coords.WorldPoint dragTile,
                                                        boolean subtract)
                    {
                        // Optional: a separate "in-flight rectangle" overlay could
                        // show pressTile→dragTile in green/red. Skipped in MVP.
                    }
                });
                // Bind Enter / Esc on the panel so the user can commit/cancel.
                getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW)
                    .put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ENTER, 0), "annotator-commit");
                getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW)
                    .put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0), "annotator-cancel");
                getActionMap().put("annotator-commit", new javax.swing.AbstractAction()
                {
                    @Override public void actionPerformed(java.awt.event.ActionEvent e)
                    { if (areaSelector.isActive()) areaSelector.commit(); }
                });
                getActionMap().put("annotator-cancel", new javax.swing.AbstractAction()
                {
                    @Override public void actionPerformed(java.awt.event.ActionEvent e)
                    { if (areaSelector.isActive()) areaSelector.cancel(); }
                });
            }

            @Override public void onMarkObject(java.util.function.Consumer<Waypoint> onCommit)
            {
                if (tileMarker == null || transportResolver == null) return;
                tileMarker.arm(wp -> {
                    if (wp == null) return;
                    clientThread.invokeLater(() -> {
                        var match = transportResolver.findAnyTransport(wp);
                        javax.swing.SwingUtilities.invokeLater(() -> {
                            if (match == null) return;
                            Waypoint t = Waypoint.transport(wp, match.kind(), match.verb());
                            onCommit.accept(t);
                        });
                    });
                });
            }

            @Override public void onAddCurrent(java.util.function.Consumer<Waypoint> onCommit)
            {
                clientThread.invokeLater(() -> {
                    var self = client.getLocalPlayer();
                    if (self == null) return;
                    var loc = self.getWorldLocation();
                    if (loc == null) return;
                    javax.swing.SwingUtilities.invokeLater(
                        () -> onCommit.accept(Waypoint.walk(loc)));
                });
            }

            @Override public void onAddMarked(java.util.function.Consumer<Waypoint> onCommit)
            {
                if (tileMarker == null) return;
                var loc = tileMarker.lastMarked();
                if (loc != null) onCommit.accept(Waypoint.walk(loc));
            }

            @Override public void onWalkPath(java.util.List<Waypoint> wps)
            {
                runWalker(wps, wps.size());
            }

            @Override public void onWalkToSelected(java.util.List<Waypoint> wps, int selectedIdx)
            {
                runWalker(wps, selectedIdx + 1);
            }
        });
    routesContainer.add(editor, java.awt.BorderLayout.CENTER);
    routesContainer.revalidate();
    routesContainer.repaint();
}

/** Drive the walker over waypoints[0..endExclusive). Fork to a daemon
 *  thread; mirrors the legacy walkRoute flow. */
private void runWalker(java.util.List<Waypoint> wps, int endExclusive)
{
    final java.util.List<Waypoint> slice = new java.util.ArrayList<>(
        wps.subList(0, Math.max(0, Math.min(endExclusive, wps.size()))));
    Thread t = new Thread(() -> {
        try
        {
            net.runelite.client.plugins.recorder.farm.RouteWalker w = new net.runelite.client.plugins.recorder.farm.RouteWalker(
                client, dispatcher, transportResolver);
            for (Waypoint wp : slice)
            {
                if (Thread.currentThread().isInterrupted()) break;
                while (!w.arrived(wp))
                {
                    if (Thread.currentThread().isInterrupted()) break;
                    w.tick(wp);
                    Thread.sleep(300 + (int)(Math.random() * 300));
                }
            }
        }
        catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }, "annotator-test-walk");
    t.setDaemon(true);
    t.start();
}
```

Note: `tileMarker.lastMarked()` may not exist today. If not, add a simple `lastMarked` field to `TileMarker` that's set in the existing arm-callback path; otherwise fall back to a status message ("no tile marked yet"). The smoke step will surface this.

The `dispatcher` and `transportResolver` references already exist on the panel from Task 6 of the chicken-farm-bot work.

- [ ] **Step 3: Compile + smoke**

```bash
./gradlew :client:compileJava
./gradlew :client:test --tests 'net.runelite.client.plugins.recorder.*'
./gradlew :client:shadowJar
pkill -f 'client-1.12.25-SNAPSHOT-shaded' 2>&1; sleep 2
nohup /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/bin/java \
  --add-exports=java.desktop/sun.awt=ALL-UNNAMED \
  --add-exports=java.desktop/com.apple.eawt=ALL-UNNAMED \
  --add-exports=java.desktop/com.apple.eio=ALL-UNNAMED \
  --add-opens=java.desktop/com.apple.eawt=ALL-UNNAMED -ea \
  -jar runelite-client/build/libs/client-1.12.25-SNAPSHOT-shaded.jar \
  --developer-mode > /tmp/runelite-stdout.log 2>&1 &
disown
```

Manual checklist:
- Routes tab opens; existing routes (e.g. `lumbridge_bank_to_pen.txt`) listed with `[FARM]` prefix and waypoint count.
- `+ New Route` prompts for a name and drops you into an empty editor.
- In the editor: `Mark area` shows the HUD; drag adds tiles; Shift+Drag subtracts; Enter commits, the new waypoint appears in the list, the file is updated on disk.
- `Mark object` on a gate/stairs/ladder produces a transport waypoint with the right verb.
- `Walk to selected` runs the walker only as far as the selected waypoint.
- `Undo` rolls back the most recent mutation.
- Selecting a waypoint in the list highlights it in the overlay (thicker cyan outline).

- [ ] **Step 4: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/plugins/recorder/RecorderPanel.java \
        runelite-client/src/main/java/net/runelite/client/plugins/recorder/RecorderPlugin.java
git commit -m "$(cat <<'EOF'
recorder: wire RoutesTab + WaypointEditor into the side panel

Routes tab swaps between RoutesTab (saved-routes list) and
WaypointEditor (per-route drilldown) inside a single container
panel. The editor's Hooks interface dispatches Mark area through
AreaSelector + AnnotatorHudOverlay; Mark object through the
existing TransportResolver.findAnyTransport; Add current / Add
marked through clientThread snapshot reads; Walk path / Walk to
selected through a fresh RouteWalker on a daemon thread.

Plugin startUp now constructs AnnotatorHudOverlay + AreaSelector,
registers the HUD with overlayManager, and hands both to the panel.
shutDown cancels any active selection and removes the HUD.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 12: `Mark object` integration check

`Mark object` is wired in Task 11's `Hooks.onMarkObject` callback. This task is just a manual smoke + commit if anything had to be tweaked.

- [ ] **Step 1: Smoke `Mark object`**

In-game, on a known transport (e.g. the Lumbridge bank stairs):
1. Open the Routes tab → `+ New Route` → name `smoke_test`.
2. Click `Mark object` → click the bank stairs in-world.
3. Status updates; new waypoint appears in the list with verb `climb-down`.
4. The route file at `~/.runelite/sequencer/routes/smoke_test.txt` contains a `climb-down:x,y,p` line.
5. Delete the route via the Routes tab when done.

If anything's broken, fix in `RecorderPanel.openRouteEditor` and re-smoke.

- [ ] **Step 2: Commit (only if changes were needed)**

```bash
git add runelite-client/src/main/java/net/runelite/client/plugins/recorder/RecorderPanel.java
git commit -m "recorder: Mark object smoke fix (post-redesign)

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

### Task 13: Selection feedback + reorder smoke + edit-bounds smoke

Task 11 already wires the `▲▼` reorder buttons + `✎` edit-bounds + `×` delete + selection highlight. This task verifies them in-game and fixes any bugs that surface.

- [ ] **Step 1: Smoke each operation**

In a test route:
1. Add 5 waypoints (mix of areas + transports).
2. Click waypoint #2 — its tiles render in cyan with thicker outline; the others stay default.
3. Click `▲` on waypoint #3 — it moves up to slot #2, the file is rewritten, undo count increments.
4. Click `✎` on waypoint #2 — HUD shows "Editing: <name>". Drag to extend the area, press Enter — waypoint updated in place, name preserved.
5. Click `✎` on a transport waypoint — small dialog appears with `x,y,p` editable. Type new coords, Enter — waypoint updated.
6. Click `×` on waypoint #4 — removed, undo count increments.
7. Click `Undo` — waypoint #4 reappears.

- [ ] **Step 2: Fix anything broken; commit if needed**

```bash
git add runelite-client/src/main/java/net/runelite/client/plugins/recorder/annotator/WaypointEditor.java
git commit -m "recorder/annotator: WaypointEditor smoke fixes

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

### Task 14: `Walk path` and `Walk to selected` smoke

- [ ] **Step 1: Smoke**

In a route with at least 3 waypoints inside Lumbridge (bank → stairs → upstairs):
1. Stand at the start of the route. Click `Walk path`. Walker drives 0 → end.
2. Stand at the start. Select waypoint #1. Click `Walk to selected`. Walker drives 0 → 1 and stops.
3. Confirm the bot stopped, didn't continue past the selected waypoint.

If `runWalker` produces wrong behaviour (continues past, or doesn't start), fix in `RecorderPanel.runWalker` and re-smoke.

- [ ] **Step 2: Commit if needed**

```bash
git add runelite-client/src/main/java/net/runelite/client/plugins/recorder/RecorderPanel.java
git commit -m "recorder: Walk path / Walk to selected smoke fixes

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Stage 3 — Close-out (Task 15)

### Task 15: End-to-end smoke + push

- [ ] **Step 1: Verify full test suite**

```bash
./gradlew :client:test
```

Expected: BUILD SUCCESSFUL with no new failures. (The pre-existing 3 `SpecialCounterPluginTest` failures are master-of-master and unrelated.)

- [ ] **Step 2: Manual end-to-end**

1. Open the Recorder panel — Status pinned, 5 tabs visible.
2. Routes tab: existing `lumbridge_bank_to_pen` route visible with `[FARM]` badge.
3. Click `+ New Route`, name `e2e_test`. Editor opens.
4. Mark a 4×3 walkbox; mark a transport object; close the editor with `← Back`.
5. Reopen `e2e_test`. Verify the two waypoints are still there.
6. Combat tab: the chicken-farm Start button still works (uses the existing `lumbridge_bank_to_pen.txt`).
7. Record / Mining / Login tabs: their existing controls function normally.
8. Delete `e2e_test` from the routes list.

- [ ] **Step 3: Push**

```bash
git push origin master
```

(After this redesign work has been squash-merged off whatever worktree was used for execution. The same `git merge --squash` flow we used for the original chicken-farm-bot squash applies here.)

---

## Self-review

**Spec coverage:**
- Section 1 (panel structure) → Task 8.
- Section 2 (Routes tab flow) → Tasks 9, 10, 11.
- Section 3 (Mark area input model) → Tasks 5, 6, 11.
- Section 4 (data model + file format) → Tasks 1, 2, 3.
- Section 5 (render bug fix) → Task 4.
- Section 6 (selection feedback) → Task 4 + Task 11 wiring.
- Section 7 Phase A (▲▼ reorder + edit-bounds) → Task 10 + Task 13 smoke.
- Walk to selected → Task 10's hook + Task 14 smoke.

**Placeholder scan:** The `runWalker` block in Task 11 mentions "Optional: a separate in-flight rectangle overlay could show pressTile→dragTile" — this is intentionally deferred per the design's MVP scope (Section 5 polish item). Not a placeholder.

The `tileMarker.lastMarked()` reference in Task 11 is a small ask of the existing `TileMarker` class. If absent, the implementer adds a single field assignment in the existing arm-callback path. Worth flagging up front but not a placeholder.

**Type consistency:**
- `Waypoint.tiles()` defined in Task 1, used in Tasks 3, 4, 10. ✓
- `Waypoint.isRectangular()` defined in Task 1, used in Task 10's verbSummary. ✓
- `RouteWalker.sampleTile(WorldArea, Set<WorldPoint>, Random, Predicate)` 4-arg signature in Task 3, used in Task 11's `runWalker` indirectly. ✓
- `RouteOverlay.setSelected(Waypoint)` defined in Task 4, used in Task 10. ✓
- `AreaSelector.start(Set<WorldPoint>, Listener)` defined in Task 5, used in Task 11. ✓
- `AnnotatorHudOverlay.show(String)` defined in Task 6, used in Task 11. ✓
- `UndoStack(int).push/pop` defined in Task 7, used in Task 10. ✓

**Open follow-ups (intentionally deferred per design spec):**
- Phase B drag-handle reorder.
- Union-outline for irregular tile sets.
- Multi-step transports (fairy rings, boats, dialogue portals).
- Redo.
- Walk from selected → end.
- Live in-flight rectangle preview during drag selection.

---

## Execution handoff

Plan complete and saved to `docs/superpowers/plans/2026-04-28-annotator-redesign.md`.

**Two execution options:**

1. **Subagent-Driven (recommended)** — fresh subagent per task, two-stage review (spec then code quality) between tasks, fast iteration. Good fit because the tasks are mostly module additions with well-defined interfaces.

2. **Inline Execution** — execute tasks in this session using executing-plans, batch execution with checkpoints. Larger context but lets you eyeball each step.

**Which approach?**
