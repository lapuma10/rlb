# Chicken Farm Bot Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a self-sustaining chicken-killing bot for the Lumbridge chicken pen. The bot walks bank ↔ pen, kills only chickens that sit inside the pen area, deposits the loot at the upstairs Lumbridge bank booth, and repeats indefinitely.

**Architecture:** Two phases.
- **Phase 1 — Route annotator.** A side-panel tool that lets the user click in-world to capture named tile *areas* (rectangles) and *transport objects* (gates / stairs / ladders) into a route file the existing `RouteParser` can read. Areas are sampled at runtime so the bot never clicks the exact same pixel twice.
- **Phase 2 — Farm loop.** An outer state machine (`ChickenFarmLoop`) that locates the player on `start()`, decides KILLING / WALKING_TO_BANK / BANKING / WALKING_TO_PEN, and drives a new area-aware `RouteWalker` plus the existing `ChickenCombatLoop`.

**Tech Stack:** Java 17, RuneLite plugin API, Swing (side panel), JUnit + Mockito (tests), Gradle.
- JDK: `/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`
- Build: `./gradlew :client:shadowJar`
- Test single: `./gradlew :client:test --tests '<fully.qualified.TestName>'`
- Run client (background): see `docs/superpowers/plans/2026-04-27-session-handoff.md` if it documents the relaunch flow; otherwise use the `nohup java --add-exports=… -jar runelite-client/build/libs/client-1.12.25-SNAPSHOT-shaded.jar --developer-mode &` recipe.

---

## What exists today (read first)

The previous session committed `3e51d299f` on `master`. State of relevant code:

- **`net.runelite.client.plugins.recorder.combat.ChickenCombatLoop`** — kills chickens in a state machine (IDLE → SELECTING → ENGAGING → IN_COMBAT → KILLED → LOOTING → SELECTING). Has `start()`, `stop()`, `state()`, `killCount()`, `latestStatus()`. Does NOT walk; it picks the closest reachable chicken from the player's current position. Used directly by the side panel today.
- **`NpcSelector`** — has a public `classify(npc, self, playerPos, excludedIndex, wv, visibility)` returning a `Rejection` enum (or null = eligible). `pick(...)` delegates to `classify`. Default range = 6 tiles, name filter = "Chicken".
- **`TargetVisibility`** — visibility checker used by both selector and the `ChickenOverlay`. Stages: plane → reachability BFS (depth 8) → on-canvas → in-viewport → not-under-open-menu → not-under-HUD-widget-with-content. Returns `Reason` enum from `whyHidden(...)` so callers can break the count down by stage. Projectile-LOS and roof checks were intentionally removed.
- **`ChickenOverlay`** — debug overlay rendering convex hulls coloured by `NpcSelector.Rejection`; closest eligible gets thick cyan. Counts panel breaks not-visible into sub-stages.
- **`transport/Waypoint`** — `WALK(WorldPoint)` or `TRANSPORT(WorldPoint, TransportKind, verb)`. Single-tile only today.
- **`transport/RouteParser`** — parses one waypoint per line: `x,y[,p]` / `walk:` / `open:` / `climb-up:` / `climb-down:` / `interact:x,y[,p]:Verb`. `Result(waypoints, errors)`.
- **`transport/TransportResolver`** — given (tile, verb), finds the wall/game/decorative/ground object on that tile whose composition advertises a matching action. Returns `Match` with the object reference, exact verb string, and `matchedObjectId`. `tileAt(WorldPoint)` returns the live `Tile`.
- **`RecorderPanel`** — already has a Save/Load route + Walk path block (`testWalkArea` `JTextArea`, `saveRouteBtn`/`loadRouteBtn`, `testWalkBtn` calling `walkRoute(List<Waypoint>)`). Routes live at `~/.runelite/sequencer/routes/<name>.txt`. The panel also has a "Mark tile (next click)" button wired to `TileMarker`. Combat section: `chickenStartBtn` / `chickenStopBtn` / `chickenStatusLabel` / `chickenKillsLabel`.
- **`debug/TileMarker`** — `arm(Consumer<WorldPoint> callback)` consumes the next canvas left-click and invokes the callback with the tile. `disarm()` cancels.
- **`debug/DebugOverlay`** — already paints the marked tile + selected scene tile + a hover-info panel with `tile: x,y p=N` under the cursor.
- **`HumanizedInputDispatcher`** — `clickCanvas(int x, int y)` and `clickCanvas(double xProp, double yProp)`. The panel's existing `walkRoute(List<Waypoint>)` knows how to drive it for `walk:` / `open:` / `climb-up:` / `climb-down:`. Single minimap click per call; range ≈ 15-25 tiles.

---

## File structure

### Phase 1 — Route annotator

**Modify**
- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/transport/Waypoint.java` — add `Kind.WALK_AREA` with `WorldArea` payload + `name` field on every kind.
- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/transport/RouteParser.java` — parse `walkbox:sw_x,sw_y - ne_x,ne_y, plane` and `name=` prefix on any line.
- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/transport/TransportResolver.java` — add `findAnyTransport(WorldPoint)` returning the first object on the tile that advertises a transport-shaped verb (Open / Climb-up / Climb-down / Use / Push / Pull), with the chosen `TransportKind` and `verb`.
- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/RecorderPanel.java` — add an "Annotator" sub-panel inside the existing route block: name field, "Mark area" (two clicks), "Mark object" (one click), "Mark tile" (one click; degenerate area), with each captured mark appended to `testWalkArea`.
- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/RecorderPlugin.java` — register a new `RouteOverlay`.

**Create**
- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/transport/RouteOverlay.java` — translucent overlay that paints every saved waypoint (areas as filled rectangles with name labels, transports as outlined object hulls with the verb).
- `runelite-client/src/test/java/net/runelite/client/plugins/recorder/transport/WaypointTest.java`
- `runelite-client/src/test/java/net/runelite/client/plugins/recorder/transport/RouteParserAreaTest.java`

### Phase 2 — Chicken farm loop

**Create**
- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/farm/FarmConfig.java` — hardcoded constants: `BANK_AREA`, `PEN_AREA`, `ROUTE_BANK_TO_PEN: List<Waypoint>`, plus tolerances. Coords are placeholders the user will fill in.
- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/farm/InventoryUtil.java` — `freeSlotCount(Client)` and `isInventoryFull(Client)` wrapping `Inventory` widget reads.
- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/farm/RouteWalker.java` — per-waypoint tick: pick a random valid tile in a `WALK_AREA`, click it; for transports, pre-check the verb; advance on arrival predicate.
- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/farm/BankInteraction.java` — open bank → wait widget → click Deposit-inventory orb → close.
- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/farm/ChickenFarmLoop.java` — outer state machine `IDLE / KILLING / WALKING_TO_BANK / BANKING / WALKING_TO_PEN / ABORTED`. Wraps `ChickenCombatLoop` + `RouteWalker` + `BankInteraction`.
- `runelite-client/src/test/java/net/runelite/client/plugins/recorder/farm/RouteWalkerTest.java`
- `runelite-client/src/test/java/net/runelite/client/plugins/recorder/farm/ChickenFarmLoopTest.java`

**Modify**
- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/combat/NpcSelector.java` — add a constructor variant that takes an optional `WorldArea` confinement; chickens whose tile is outside the area are rejected with `Rejection.OUT_OF_AREA` (new enum value). `ChickenCombatLoop` accepts this so the farm loop can pin combat to `PEN_AREA`.
- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/RecorderPanel.java` — replace the existing chicken-combat buttons with farm-loop buttons (Start / Stop / status). Same screen real estate.
- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/RecorderPlugin.java` — instantiate `ChickenFarmLoop` instead of (or alongside) the bare `ChickenCombatLoop`; pass it to the panel.

---

## Phase 1 — Route annotator

### Task 1: Extend Waypoint with area + name

**Files:**
- Modify: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/transport/Waypoint.java`
- Create: `runelite-client/src/test/java/net/runelite/client/plugins/recorder/transport/WaypointTest.java`

- [ ] **Step 1: Write the failing tests**

```java
package net.runelite.client.plugins.recorder.transport;

import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.*;

public class WaypointTest
{
    @Test
    public void walkAreaCarriesAreaAndOptionalName()
    {
        WorldArea a = new WorldArea(3091, 3243, 7, 5, 2);
        Waypoint w = Waypoint.walkArea("lumbridge_bank", a);
        assertEquals(Waypoint.Kind.WALK_AREA, w.kind());
        assertEquals(a, w.area());
        assertEquals("lumbridge_bank", w.name());
    }

    @Test
    public void walkSingleTileExposesAreaAsOneByOne()
    {
        Waypoint w = Waypoint.walk(new WorldPoint(3208, 3220, 2));
        assertEquals(Waypoint.Kind.WALK, w.kind());
        // Single-tile walk still exposes a 1x1 area for unified consumers.
        WorldArea a = w.area();
        assertNotNull(a);
        assertEquals(1, a.getWidth());
        assertEquals(1, a.getHeight());
        assertEquals(3208, a.getX());
        assertEquals(3220, a.getY());
        assertEquals(2, a.getPlane());
    }

    @Test
    public void transportRetainsTileAndVerb()
    {
        Waypoint w = Waypoint.transport(
            new WorldPoint(3239, 3295, 0),
            Waypoint.TransportKind.OPEN, "Open");
        assertEquals(Waypoint.Kind.TRANSPORT, w.kind());
        assertEquals(3239, w.tile().getX());
        assertEquals("Open", w.verb());
        // Transport waypoints have no name unless caller supplied one.
        assertNull(w.name());
    }

    @Test
    public void namedTransportFactoryAttachesName()
    {
        Waypoint w = Waypoint.transportNamed(
            "pen_gate",
            new WorldPoint(3239, 3295, 0),
            Waypoint.TransportKind.OPEN, "Open");
        assertEquals("pen_gate", w.name());
        assertEquals("Open", w.verb());
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
./gradlew :client:test --tests 'net.runelite.client.plugins.recorder.transport.WaypointTest'
```

Expected: 4 failures (`walkArea`, `walkArea on Waypoint`, `area`, `name`, `transportNamed` are all undefined symbols).

- [ ] **Step 3: Add WALK_AREA + name + area accessor**

Replace the body of `Waypoint.java` with:

```java
package net.runelite.client.plugins.recorder.transport;

import javax.annotation.Nullable;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;

/**
 * A single step in a route. Either a plain walk target ({@link Kind#WALK}),
 * a walk into an arbitrary tile area ({@link Kind#WALK_AREA}) — runtime
 * samples one valid tile per visit so the bot never hits the same pixel
 * twice — or a transport interaction ({@link Kind#TRANSPORT}) that requires
 * the walker to find an on-tile object exposing a verb. Modeled as a
 * discriminated union with optional human-readable {@code name} for
 * status messages and route-overlay labels.
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
    private final WorldArea area;        // non-null for WALK_AREA
    private final TransportKind transportKind;
    private final String verb;
    private final String name;

    private Waypoint(Kind kind, WorldPoint tile, WorldArea area,
                     TransportKind tk, String verb, String name)
    {
        this.kind = kind;
        this.tile = tile;
        this.area = area;
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

    public static Waypoint walkArea(@Nullable String name, WorldArea area)
    {
        if (area == null) throw new IllegalArgumentException("area null");
        return new Waypoint(Kind.WALK_AREA, null, area, null, null, name);
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

    /** Single-tile target. Null for {@link Kind#WALK_AREA}. */
    @Nullable public WorldPoint tile() { return tile; }

    /** Tile area. For {@link Kind#WALK} this returns a 1x1 area at
     *  {@link #tile()} so consumers can treat WALK and WALK_AREA uniformly.
     *  Null for {@link Kind#TRANSPORT}. */
    @Nullable
    public WorldArea area()
    {
        if (kind == Kind.WALK_AREA) return area;
        if (kind == Kind.WALK) return new WorldArea(tile.getX(), tile.getY(), 1, 1, tile.getPlane());
        return null;
    }

    @Nullable public TransportKind transportKind() { return transportKind; }
    @Nullable public String verb() { return verb; }
    @Nullable public String name() { return name; }

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
                sb.append("walkbox:").append(area.getX()).append(',').append(area.getY())
                    .append(" - ").append(area.getX() + area.getWidth() - 1).append(',')
                    .append(area.getY() + area.getHeight() - 1)
                    .append(",p=").append(area.getPlane());
                break;
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

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew :client:test --tests 'net.runelite.client.plugins.recorder.transport.WaypointTest'
```

Expected: 4 PASS. Then run the existing transport tests to verify nothing else broke:

```bash
./gradlew :client:test --tests 'net.runelite.client.plugins.recorder.transport.*'
```

Expected: all PASS.

- [ ] **Step 5: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/plugins/recorder/transport/Waypoint.java \
        runelite-client/src/test/java/net/runelite/client/plugins/recorder/transport/WaypointTest.java
git commit -m "$(cat <<'EOF'
recorder/transport: add WALK_AREA waypoint kind and optional name

Routes now distinguish single-tile walks (Kind.WALK) from area walks
(Kind.WALK_AREA carrying a WorldArea). Single-tile walks still expose a
1x1 area via Waypoint.area() so RouteWalker can sample uniformly. Every
waypoint now carries an optional name for status messages / overlay
labels.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

### Task 2: Extend RouteParser for `walkbox:` and named lines

**Files:**
- Modify: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/transport/RouteParser.java`
- Create: `runelite-client/src/test/java/net/runelite/client/plugins/recorder/transport/RouteParserAreaTest.java`

- [ ] **Step 1: Write the failing tests**

```java
package net.runelite.client.plugins.recorder.transport;

import org.junit.Test;
import static org.junit.Assert.*;

public class RouteParserAreaTest
{
    @Test
    public void parsesWalkbox()
    {
        Waypoint w = RouteParser.parseLine("walkbox:3091,3243 - 3097,3247,2");
        assertEquals(Waypoint.Kind.WALK_AREA, w.kind());
        assertEquals(3091, w.area().getX());
        assertEquals(3243, w.area().getY());
        assertEquals(7, w.area().getWidth());   // 3097 - 3091 + 1
        assertEquals(5, w.area().getHeight());  // 3247 - 3243 + 1
        assertEquals(2, w.area().getPlane());
        assertNull(w.name());
    }

    @Test
    public void parsesNamedWalkbox()
    {
        Waypoint w = RouteParser.parseLine(
            "lumbridge_bank: walkbox:3091,3243 - 3097,3247,2");
        assertEquals("lumbridge_bank", w.name());
        assertEquals(Waypoint.Kind.WALK_AREA, w.kind());
        assertEquals(7, w.area().getWidth());
    }

    @Test
    public void parsesNamedTransport()
    {
        Waypoint w = RouteParser.parseLine("pen_gate: open:3239,3295,0");
        assertEquals("pen_gate", w.name());
        assertEquals(Waypoint.Kind.TRANSPORT, w.kind());
        assertEquals(Waypoint.TransportKind.OPEN, w.transportKind());
        assertEquals("Open", w.verb());
    }

    @Test
    public void parsesNamedPlainTile()
    {
        Waypoint w = RouteParser.parseLine("waypoint_1: 3208,3220,2");
        assertEquals("waypoint_1", w.name());
        assertEquals(Waypoint.Kind.WALK, w.kind());
        assertEquals(3208, w.tile().getX());
    }

    @Test
    public void walkboxRejectsBadCorners()
    {
        try
        {
            RouteParser.parseLine("walkbox:3097,3247 - 3091,3243,2");
            fail("expected IllegalArgumentException for ne < sw");
        }
        catch (IllegalArgumentException ex)
        {
            assertTrue(ex.getMessage(), ex.getMessage().toLowerCase().contains("corner"));
        }
    }

    @Test
    public void planeMustMatchOnSingleLine()
    {
        Waypoint w = RouteParser.parseLine("walkbox:3091,3243 - 3097,3247,0");
        assertEquals(0, w.area().getPlane());
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew :client:test --tests 'net.runelite.client.plugins.recorder.transport.RouteParserAreaTest'
```

Expected: 6 failures.

- [ ] **Step 3: Implement parser changes**

In `RouteParser.java`, replace the body of `parseLine(String line)` with:

```java
public static Waypoint parseLine(String line)
{
    if (line == null || line.isBlank())
        throw new IllegalArgumentException("empty line");
    String name = null;
    String body = line;
    // Optional "name: <body>" prefix. We detect a name as a token before
    // ':' that contains no digit and no comma, so coordinates are never
    // mistaken for names.
    int firstColon = line.indexOf(':');
    if (firstColon > 0)
    {
        String head = line.substring(0, firstColon);
        if (looksLikeName(head))
        {
            name = head.trim();
            body = line.substring(firstColon + 1).trim();
        }
    }
    return parseBody(body, name);
}

private static boolean looksLikeName(String head)
{
    if (head.isBlank()) return false;
    for (int i = 0; i < head.length(); i++)
    {
        char c = head.charAt(i);
        if (Character.isDigit(c) || c == ',' || c == ' ' || c == '-') return false;
    }
    return true;
}

private static Waypoint parseBody(String body, String name)
{
    int colon = body.indexOf(':');
    if (colon < 0 || hasDigitBefore(body, colon))
    {
        // Plain "x,y[,p]"
        WorldPoint tile = parseTile(body);
        return name == null ? Waypoint.walk(tile) : Waypoint.walkNamed(name, tile);
    }
    String prefix = body.substring(0, colon).trim().toLowerCase();
    String rest = body.substring(colon + 1).trim();
    switch (prefix)
    {
        case "walk":
        {
            WorldPoint tile = parseTile(rest);
            return name == null ? Waypoint.walk(tile) : Waypoint.walkNamed(name, tile);
        }
        case "walkbox":
        {
            return Waypoint.walkArea(name, parseWalkbox(rest));
        }
        case "open":
        {
            WorldPoint t = parseTile(rest);
            return name == null
                ? Waypoint.transport(t, Waypoint.TransportKind.OPEN, "Open")
                : Waypoint.transportNamed(name, t, Waypoint.TransportKind.OPEN, "Open");
        }
        case "climb-up":
        case "climbup":
        {
            WorldPoint t = parseTile(rest);
            return name == null
                ? Waypoint.transport(t, Waypoint.TransportKind.CLIMB_UP, "Climb-up")
                : Waypoint.transportNamed(name, t, Waypoint.TransportKind.CLIMB_UP, "Climb-up");
        }
        case "climb-down":
        case "climbdown":
        {
            WorldPoint t = parseTile(rest);
            return name == null
                ? Waypoint.transport(t, Waypoint.TransportKind.CLIMB_DOWN, "Climb-down")
                : Waypoint.transportNamed(name, t, Waypoint.TransportKind.CLIMB_DOWN, "Climb-down");
        }
        case "interact":
        {
            int sep = rest.indexOf(':');
            if (sep < 0)
                throw new IllegalArgumentException(
                    "interact requires a verb after the tile (interact:x,y[,p]:Verb)");
            WorldPoint t = parseTile(rest.substring(0, sep).trim());
            String verb = rest.substring(sep + 1).trim();
            if (verb.isEmpty())
                throw new IllegalArgumentException("interact verb cannot be empty");
            return name == null
                ? Waypoint.transport(t, Waypoint.TransportKind.INTERACT, verb)
                : Waypoint.transportNamed(name, t, Waypoint.TransportKind.INTERACT, verb);
        }
        default:
            throw new IllegalArgumentException("unknown prefix '" + prefix
                + "' (expected open / climb-up / climb-down / interact / walk / walkbox)");
    }
}

/** Parse {@code "sw_x,sw_y - ne_x,ne_y[,plane]"} into a {@link WorldArea}. */
private static net.runelite.api.coords.WorldArea parseWalkbox(String s)
{
    if (s == null || s.isBlank()) throw new IllegalArgumentException("missing area");
    int sep = s.indexOf('-');
    if (sep < 0) throw new IllegalArgumentException(
        "walkbox needs sw - ne corners separated by '-'");
    String swPart = s.substring(0, sep).trim();
    String nePart = s.substring(sep + 1).trim();
    String[] sw = swPart.split("\\s*,\\s*");
    String[] ne = nePart.split("\\s*,\\s*");
    if (sw.length < 2) throw new IllegalArgumentException("sw corner needs x,y");
    if (ne.length < 2) throw new IllegalArgumentException("ne corner needs x,y");
    try
    {
        int sx = Integer.parseInt(sw[0]);
        int sy = Integer.parseInt(sw[1]);
        int nx = Integer.parseInt(ne[0]);
        int ny = Integer.parseInt(ne[1]);
        if (nx < sx || ny < sy)
            throw new IllegalArgumentException(
                "walkbox ne corner must be >= sw corner (got sw=" + sx + "," + sy
                    + " ne=" + nx + "," + ny + ")");
        // plane lives on the ne side: "sw - ne, plane"
        int plane = 0;
        if (ne.length >= 3) plane = Integer.parseInt(ne[2]);
        else if (sw.length >= 3) plane = Integer.parseInt(sw[2]);
        return new net.runelite.api.coords.WorldArea(sx, sy, nx - sx + 1, ny - sy + 1, plane);
    }
    catch (NumberFormatException nfe)
    {
        throw new IllegalArgumentException("non-numeric coordinate in walkbox '" + s + "'");
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew :client:test --tests 'net.runelite.client.plugins.recorder.transport.*'
```

Expected: all PASS (existing parser tests + the 6 new ones).

- [ ] **Step 5: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/plugins/recorder/transport/RouteParser.java \
        runelite-client/src/test/java/net/runelite/client/plugins/recorder/transport/RouteParserAreaTest.java
git commit -m "$(cat <<'EOF'
recorder/transport: parse walkbox: and optional name prefix in routes

Adds 'walkbox:sw_x,sw_y - ne_x,ne_y[,plane]' to the route grammar and
allows every line to carry a leading 'name: ' prefix that surfaces in
status messages and overlay labels. Single-tile and transport
syntaxes are unchanged.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

### Task 3: Add `findAnyTransport` to `TransportResolver`

The annotator needs to detect what's on a tile when the user clicks on an object — without the user pre-selecting a verb. The resolver currently requires a verb. Add a method that scans for the first verb the object exposes from a known transport list.

**Files:**
- Modify: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/transport/TransportResolver.java`
- Modify: `runelite-client/src/test/java/net/runelite/client/plugins/recorder/transport/TransportResolverTest.java` (or create if missing — check first)

- [ ] **Step 1: Check for an existing test file**

```bash
ls runelite-client/src/test/java/net/runelite/client/plugins/recorder/transport/
```

If `TransportResolverTest.java` exists, append to it; otherwise create with the standard license header.

- [ ] **Step 2: Write the failing test**

```java
@Test
public void findAnyTransportPicksFirstKnownVerb()
{
    // Mock a tile holding a WallObject whose composition advertises "Open".
    Client client = mock(Client.class);
    Tile tile = mock(Tile.class);
    WorldView wv = mock(WorldView.class);
    Scene scene = mock(Scene.class);
    when(client.getTopLevelWorldView()).thenReturn(wv);
    when(wv.getScene()).thenReturn(scene);
    when(wv.getBaseX()).thenReturn(3000);
    when(wv.getBaseY()).thenReturn(3000);
    Tile[][][] tiles = new Tile[4][104][104];
    int sx = 3239 - 3000, sy = 3295 - 3000;
    tiles[0][sx][sy] = tile;
    when(scene.getTiles()).thenReturn(tiles);
    WallObject wall = mock(WallObject.class);
    when(wall.getId()).thenReturn(1551);
    ObjectComposition comp = mock(ObjectComposition.class);
    when(comp.getActions()).thenReturn(new String[]{"Open", null, null, "Examine", null});
    when(client.getObjectDefinition(1551)).thenReturn(comp);
    when(tile.getWallObject()).thenReturn(wall);

    TransportResolver tr = new TransportResolver(client);
    TransportResolver.AnyMatch any = tr.findAnyTransport(new WorldPoint(3239, 3295, 0));
    assertNotNull(any);
    assertEquals(Waypoint.TransportKind.OPEN, any.kind());
    assertEquals("Open", any.verb());
    assertEquals(1551, any.objectId());
}
```

- [ ] **Step 3: Run test to verify it fails**

```bash
./gradlew :client:test --tests 'net.runelite.client.plugins.recorder.transport.TransportResolverTest.findAnyTransportPicksFirstKnownVerb'
```

Expected: fail (`AnyMatch`, `findAnyTransport`, etc. undefined).

- [ ] **Step 4: Implement `findAnyTransport`**

Append to `TransportResolver.java` inside the class:

```java
/** Result of an annotator-side "what kind of transport is on this tile"
 *  lookup. Kind + verb are chosen from the first action the object
 *  exposes that maps to a known {@link Waypoint.TransportKind}. */
public static final class AnyMatch
{
    private final Waypoint.TransportKind kind;
    private final String verb;
    private final int objectId;
    private AnyMatch(Waypoint.TransportKind kind, String verb, int objectId)
    {
        this.kind = kind; this.verb = verb; this.objectId = objectId;
    }
    public Waypoint.TransportKind kind() { return kind; }
    public String verb() { return verb; }
    public int objectId() { return objectId; }
}

/** Verbs we recognise as transports for the annotator, in priority order.
 *  "Open" wins over "Close" so we always emit a closed-state waypoint that
 *  the runtime can skip when already open. INTERACT is last and acts as a
 *  catch-all for "Use" / "Push" / "Pull" / shortcut verbs. */
private static final String[][] TRANSPORT_VERBS_IN_ORDER = {
    {"Open"},
    {"Climb-up"},
    {"Climb-down"},
    {"Use"}, {"Push"}, {"Pull"}, {"Squeeze-through"}, {"Climb-over"},
    {"Search"}
};

/** Look at every object on a tile and return the first known
 *  transport-shaped verb it advertises, or null if nothing matches. */
@Nullable
public AnyMatch findAnyTransport(WorldPoint world)
{
    if (world == null) return null;
    Tile tile = tileAt(world);
    if (tile == null) return null;
    for (String[] verbs : TRANSPORT_VERBS_IN_ORDER)
    {
        for (String verb : verbs)
        {
            Match m = findTransport(world, verb);
            if (m.isSuccess())
            {
                Waypoint.TransportKind k =
                    "Open".equalsIgnoreCase(verb) ? Waypoint.TransportKind.OPEN
                  : "Climb-up".equalsIgnoreCase(verb) ? Waypoint.TransportKind.CLIMB_UP
                  : "Climb-down".equalsIgnoreCase(verb) ? Waypoint.TransportKind.CLIMB_DOWN
                  : Waypoint.TransportKind.INTERACT;
                return new AnyMatch(k, m.matchedVerb(), m.matchedObjectId());
            }
        }
    }
    return null;
}
```

- [ ] **Step 5: Run tests + smoke**

```bash
./gradlew :client:test --tests 'net.runelite.client.plugins.recorder.transport.*'
```

Expected: all PASS.

- [ ] **Step 6: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/plugins/recorder/transport/TransportResolver.java \
        runelite-client/src/test/java/net/runelite/client/plugins/recorder/transport/TransportResolverTest.java
git commit -m "$(cat <<'EOF'
recorder/transport: add findAnyTransport for the annotator UI

Annotator clicks an object without specifying a verb. findAnyTransport
walks a priority-ordered verb list (Open / Climb-up / Climb-down / Use
/ Push / Pull / shortcut verbs) against the existing per-verb
findTransport and returns the first hit with the matched
TransportKind, exact verb, and object id.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

### Task 4: Create `RouteOverlay`

**Files:**
- Create: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/transport/RouteOverlay.java`

- [ ] **Step 1: Implement the overlay**

```java
/* RuneLite copyright header (copy from any sibling file in the package). */
package net.runelite.client.plugins.recorder.transport;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Stroke;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Tile;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Paints the in-progress route on the canvas: walk areas as filled
 * rectangles, single-tile walks as outlined tiles, transports as
 * outlined object hulls (or a tile poly when the object isn't loaded).
 * Each waypoint is labelled with its name (when present) and index.
 *
 * <p>Reads from a swappable {@link AtomicReference} so the panel can
 * push updated route lists without locking.
 */
public final class RouteOverlay extends Overlay
{
    private static final Color AREA_FILL = new Color(80, 180, 255, 60);
    private static final Color AREA_LINE = new Color(80, 180, 255, 220);
    private static final Color WALK_LINE = new Color(120, 200, 255, 220);
    private static final Color TRANSPORT_LINE = new Color(255, 180, 80, 230);

    private final Client client;
    private final AtomicReference<List<Waypoint>> route = new AtomicReference<>(List.of());

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

    @Override
    public Dimension render(Graphics2D g)
    {
        WorldView wv = client.getTopLevelWorldView();
        if (wv == null) return null;
        List<Waypoint> wps = route.get();
        if (wps.isEmpty()) return null;
        Stroke prev = g.getStroke();
        for (int i = 0; i < wps.size(); i++)
        {
            Waypoint wp = wps.get(i);
            String label = (wp.name() == null ? "" : wp.name() + " ") + "#" + i;
            switch (wp.kind())
            {
                case WALK_AREA:
                    drawArea(g, wp.area(), label);
                    break;
                case WALK:
                    drawTile(g, wp.tile(), WALK_LINE, label);
                    break;
                case TRANSPORT:
                    drawTile(g, wp.tile(), TRANSPORT_LINE,
                        label + " (" + wp.verb() + ")");
                    break;
            }
        }
        g.setStroke(prev);
        return null;
    }

    private void drawArea(Graphics2D g, WorldArea area, String label)
    {
        Polygon outline = areaPolygon(area);
        if (outline == null) return;
        g.setColor(AREA_FILL);
        g.fillPolygon(outline);
        g.setStroke(new BasicStroke(2f));
        g.setColor(AREA_LINE);
        g.drawPolygon(outline);
        // Label at the rough centroid of the SW tile.
        WorldPoint anchor = new WorldPoint(area.getX(), area.getY(), area.getPlane());
        labelAt(g, anchor, label);
    }

    private void drawTile(Graphics2D g, WorldPoint wp, Color colour, String label)
    {
        Polygon poly = tilePolygon(wp);
        if (poly == null) return;
        g.setStroke(new BasicStroke(2f));
        g.setColor(colour);
        g.drawPolygon(poly);
        labelAt(g, wp, label);
    }

    private Polygon tilePolygon(WorldPoint wp)
    {
        WorldView wv = client.getTopLevelWorldView();
        if (wv == null) return null;
        LocalPoint lp = LocalPoint.fromWorld(wv, wp);
        if (lp == null) return null;
        return Perspective.getCanvasTilePoly(client, lp);
    }

    private Polygon areaPolygon(WorldArea area)
    {
        // Build by unioning the four corner tile polys into a single polygon.
        // For small areas (typical < 20 tiles) just outline the bounding box
        // by walking the perimeter so the label stays inside.
        WorldView wv = client.getTopLevelWorldView();
        if (wv == null) return null;
        int x1 = area.getX();
        int y1 = area.getY();
        int x2 = area.getX() + area.getWidth() - 1;
        int y2 = area.getY() + area.getHeight() - 1;
        int plane = area.getPlane();
        Polygon poly = new Polygon();
        // Walk the perimeter — bottom edge L→R, right edge B→T, top edge R→L,
        // left edge T→B — collecting one canvas point per tile-corner.
        addPerimeterPoint(poly, wv, x1, y1, plane, false, false);
        for (int x = x1; x <= x2; x++) addPerimeterPoint(poly, wv, x, y1, plane, true, false);
        for (int y = y1; y <= y2; y++) addPerimeterPoint(poly, wv, x2, y, plane, true, true);
        for (int x = x2; x >= x1; x--) addPerimeterPoint(poly, wv, x, y2, plane, false, true);
        for (int y = y2; y >= y1; y--) addPerimeterPoint(poly, wv, x1, y, plane, false, false);
        return poly.npoints == 0 ? null : poly;
    }

    private void addPerimeterPoint(Polygon poly, WorldView wv, int x, int y, int plane,
                                   boolean east, boolean north)
    {
        LocalPoint lp = LocalPoint.fromWorld(wv,
            new WorldPoint(x + (east ? 1 : 0), y + (north ? 1 : 0), plane));
        if (lp == null) return;
        net.runelite.api.Point p = Perspective.localToCanvas(client, lp, plane);
        if (p == null) return;
        poly.addPoint(p.getX(), p.getY());
    }

    private void labelAt(Graphics2D g, WorldPoint wp, String label)
    {
        WorldView wv = client.getTopLevelWorldView();
        if (wv == null) return;
        LocalPoint lp = LocalPoint.fromWorld(wv, wp);
        if (lp == null) return;
        net.runelite.api.Point pt = Perspective.localToCanvas(client, lp, wp.getPlane());
        if (pt == null) return;
        g.setColor(new Color(0, 0, 0, 180));
        g.fillRect(pt.getX() - 1, pt.getY() - 12, g.getFontMetrics().stringWidth(label) + 4, 14);
        g.setColor(Color.WHITE);
        g.drawString(label, pt.getX() + 1, pt.getY() - 1);
    }
}
```

- [ ] **Step 2: Compile**

```bash
./gradlew :client:compileJava
```

Expected: BUILD SUCCESSFUL with no new warnings/errors in this file.

- [ ] **Step 3: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/plugins/recorder/transport/RouteOverlay.java
git commit -m "$(cat <<'EOF'
recorder/transport: add RouteOverlay for the annotator UI

Paints walk areas as translucent rectangles, single tiles as outlined
polys, transports as outlined object hulls — every waypoint labelled
with its name and index. Reads from an AtomicReference<List<Waypoint>>
so the panel can publish route updates without locking.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

### Task 5: Wire `RouteOverlay` into `RecorderPlugin`

**Files:**
- Modify: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/RecorderPlugin.java`

- [ ] **Step 1: Add field, instantiation, registration**

In `RecorderPlugin`, mirror the pattern used for `chickenOverlay`. Field declaration alongside the other overlays:

```java
private RouteOverlay routeOverlay;
```

Inside `startUp()` after `chickenOverlay = new ChickenOverlay(client, config);`:

```java
routeOverlay = new RouteOverlay(client);
overlayManager.add(routeOverlay);
panel.setRouteOverlay(routeOverlay);
```

Inside `shutDown()` next to the other overlay removals:

```java
if (routeOverlay != null) overlayManager.remove(routeOverlay);
```

And in the field-nulling line at the bottom, add `routeOverlay = null`.

Add the import:

```java
import net.runelite.client.plugins.recorder.transport.RouteOverlay;
```

(`panel.setRouteOverlay` will be added in Task 6 — temporarily comment it out and uncomment after Task 6 lands, OR add the empty setter to `RecorderPanel` here as a placeholder.)

- [ ] **Step 2: Compile**

```bash
./gradlew :client:compileJava
```

- [ ] **Step 3: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/plugins/recorder/RecorderPlugin.java \
        runelite-client/src/main/java/net/runelite/client/plugins/recorder/RecorderPanel.java
git commit -m "$(cat <<'EOF'
recorder: register RouteOverlay alongside DebugOverlay and ChickenOverlay

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

### Task 6: Annotator UI in `RecorderPanel`

Adds three buttons inside the existing Walk-path block, plus a "Path: ✓" checkbox and a name field. Captures emit lines into the existing `testWalkArea` (so Save/Load already work end-to-end).

**Files:**
- Modify: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/RecorderPanel.java`

- [ ] **Step 1: Add fields**

Near the other Walk-path field declarations:

```java
private final JTextField annotateNameField = new JTextField(8);
private final JCheckBox annotatePathBox = new JCheckBox("Path", true);
private final JButton markAreaBtn = new JButton("Mark area");
private final JButton markObjectBtn = new JButton("Mark object");
private final JLabel annotateStatus = new JLabel(" ");
private RouteOverlay routeOverlay;
```

- [ ] **Step 2: Wire setter for the overlay**

```java
public void setRouteOverlay(RouteOverlay ro) { this.routeOverlay = ro; }
```

- [ ] **Step 3: Add buttons to the panel layout**

Inside the method that builds the Walk-path block (search for `rowAdd.add(addCurPosBtn);`), append:

```java
JPanel rowAnnotate1 = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0));
rowAnnotate1.add(new JLabel("Name:"));
rowAnnotate1.add(annotateNameField);
rowAnnotate1.add(annotatePathBox);
rowAnnotate1.setAlignmentX(0f);
JPanel rowAnnotate2 = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0));
rowAnnotate2.add(markAreaBtn);
rowAnnotate2.add(markObjectBtn);
rowAnnotate2.setAlignmentX(0f);
buttons.add(rowAnnotate1);
buttons.add(rowAnnotate2);
buttons.add(annotateStatus);
```

(Replace `buttons` with whatever the local variable name is in your file — the row is structurally identical to `rowAdd` / `rowFile`.)

- [ ] **Step 4: Wire button handlers**

```java
markAreaBtn.addActionListener(e -> onMarkArea());
markObjectBtn.addActionListener(e -> onMarkObject());
```

`onMarkArea` and `onMarkObject` implementations:

```java
private WorldPoint pendingAreaSw;

private void onMarkArea()
{
    if (tileMarker == null) { annotateStatus.setText("tile marker not wired"); return; }
    pendingAreaSw = null;
    annotateStatus.setText("click SW corner…");
    tileMarker.arm(sw -> {
        if (sw == null) { annotateStatus.setText("first click cancelled"); return; }
        pendingAreaSw = sw;
        annotateStatus.setText("click NE corner… (sw=" + sw.getX() + "," + sw.getY() + ")");
        tileMarker.arm(ne -> {
            if (ne == null || pendingAreaSw == null)
            {
                annotateStatus.setText("second click cancelled");
                pendingAreaSw = null;
                return;
            }
            int sx = Math.min(pendingAreaSw.getX(), ne.getX());
            int sy = Math.min(pendingAreaSw.getY(), ne.getY());
            int nx = Math.max(pendingAreaSw.getX(), ne.getX());
            int ny = Math.max(pendingAreaSw.getY(), ne.getY());
            int plane = ne.getPlane();
            String name = annotateNameField.getText().trim();
            String prefix = name.isEmpty() ? "" : name + ": ";
            String line = prefix + "walkbox:" + sx + "," + sy + " - " + nx + "," + ny
                + "," + plane;
            appendLine(line);
            annotateStatus.setText("added area " + (name.isEmpty() ? "(unnamed)" : name));
            pendingAreaSw = null;
            annotateNameField.setText("");
            refreshOverlayFromText();
        });
    });
}

private void onMarkObject()
{
    if (tileMarker == null || transportResolver == null)
    {
        annotateStatus.setText("not wired (need tile marker + resolver)");
        return;
    }
    annotateStatus.setText("click an object (gate/stairs/ladder)…");
    tileMarker.arm(wp -> {
        if (wp == null) { annotateStatus.setText("click cancelled"); return; }
        clientThread.invokeLater(() -> {
            TransportResolver.AnyMatch m = transportResolver.findAnyTransport(wp);
            javax.swing.SwingUtilities.invokeLater(() -> {
                if (m == null)
                {
                    annotateStatus.setText("no known transport at "
                        + wp.getX() + "," + wp.getY());
                    return;
                }
                String name = annotateNameField.getText().trim();
                String prefix = name.isEmpty() ? "" : name + ": ";
                String body;
                switch (m.kind())
                {
                    case OPEN:        body = "open:";        break;
                    case CLIMB_UP:    body = "climb-up:";    break;
                    case CLIMB_DOWN:  body = "climb-down:";  break;
                    default:          body = "interact:";    break;
                }
                String tail = wp.getX() + "," + wp.getY() + "," + wp.getPlane();
                String line = m.kind() == Waypoint.TransportKind.INTERACT
                    ? prefix + body + tail + ":" + m.verb() + "  # objId=" + m.objectId()
                    : prefix + body + tail + "  # objId=" + m.objectId() + " verb=" + m.verb();
                appendLine(line);
                annotateStatus.setText("added " + body.replace(":", "")
                    + " (" + m.verb() + ", id=" + m.objectId() + ")");
                annotateNameField.setText("");
                refreshOverlayFromText();
            });
        });
    });
}

private void appendLine(String line)
{
    String existing = testWalkArea.getText();
    if (!existing.isEmpty() && !existing.endsWith("\n")) existing += "\n";
    testWalkArea.setText(existing + line + "\n");
}

private void refreshOverlayFromText()
{
    if (routeOverlay == null) return;
    RouteParser.Result r = RouteParser.parse(testWalkArea.getText());
    routeOverlay.setRoute(r.waypoints());
}
```

You'll need a `transportResolver` field on the panel — instantiate it in the panel constructor where `client` becomes available (or pass it from `RecorderPlugin.startUp()` via a setter).

- [ ] **Step 5: Wire the resolver**

In `RecorderPanel`:

```java
private TransportResolver transportResolver;
public void setTransportResolver(TransportResolver tr) { this.transportResolver = tr; }
```

In `RecorderPlugin.startUp()` after `panel = new RecorderPanel(...)`:

```java
panel.setTransportResolver(new TransportResolver(client));
```

- [ ] **Step 6: Compile + manual smoke**

```bash
./gradlew :client:shadowJar
# kill running client (if any) then relaunch:
pkill -f 'client-1.12.25-SNAPSHOT-shaded' 2>&1; sleep 1
nohup /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/bin/java \
  --add-exports=java.desktop/sun.awt=ALL-UNNAMED \
  --add-exports=java.desktop/com.apple.eawt=ALL-UNNAMED \
  --add-exports=java.desktop/com.apple.eio=ALL-UNNAMED \
  --add-opens=java.desktop/com.apple.eawt=ALL-UNNAMED -ea \
  -jar runelite-client/build/libs/client-1.12.25-SNAPSHOT-shaded.jar \
  --developer-mode > /tmp/runelite-stdout.log 2>&1 &
disown
```

Manual checklist (you can't run this for the user — record findings to report back):
1. Recorder side panel opens, "Mark area" / "Mark object" buttons present.
2. Type "lumbridge_bank" → click Mark area → click two corners → status reports "added area lumbridge_bank" and a translucent rectangle appears.
3. Click Mark object on the bank stairs → status reports e.g. "added climb-down (Climb-down, id=16671)".
4. Save the route with a name → file appears at `~/.runelite/sequencer/routes/<name>.txt`.
5. Reload it → text area populates and overlay re-renders.

- [ ] **Step 7: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/plugins/recorder/RecorderPanel.java \
        runelite-client/src/main/java/net/runelite/client/plugins/recorder/RecorderPlugin.java
git commit -m "$(cat <<'EOF'
recorder: route annotator — Mark area / Mark object UI

Adds a name field, "Path" checkbox, "Mark area" (two-click capture of
SW + NE corners), and "Mark object" (one-click capture of the first
known-transport-verb object at the tile, with object id annotation as
a trailing comment). Captured lines are appended to the existing
testWalkArea so the existing Save/Load/Walk plumbing works unchanged.
RouteOverlay re-parses the text on every append so the in-progress
route is visible while building.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

### Task 7: Hand-build a bank↔pen route

This is the user's job — provided here for completeness. The expected output file lives at `~/.runelite/sequencer/routes/lumbridge_bank_to_pen.txt` and is shaped like:

```
# Lumbridge bank to chicken pen
lumbridge_bank: walkbox:3091,3243 - 3097,3247,2
walkbox:3093,3239 - 3097,3243,2
bank_stairs: climb-down:3097,3243,2  # objId=<filled by annotator>
walkbox:3204,3208 - 3210,3214,0
walkbox:3215,3220 - 3220,3230,0
walkbox:3225,3260 - 3232,3275,0
walkbox:3234,3285 - 3239,3293,0
pen_gate: open:3239,3295,0  # objId=<filled by annotator>
chicken_pen: walkbox:3232,3293 - 3239,3300,0
```

Coords above are educated guesses and must be replaced by the annotator's output. The plan is correct as long as the file exists with at least one `lumbridge_bank:` walkbox, one `chicken_pen:` walkbox, and a sequence of waypoints (walks + transports) between them.

---

## Phase 2 — Chicken farm loop

### Task 8: `FarmConfig` — hardcoded coords loader

**Files:**
- Create: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/farm/FarmConfig.java`

- [ ] **Step 1: Implement**

```java
/* RuneLite copyright header. */
package net.runelite.client.plugins.recorder.farm;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import net.runelite.api.coords.WorldArea;
import net.runelite.client.plugins.recorder.transport.RouteParser;
import net.runelite.client.plugins.recorder.transport.Waypoint;

/**
 * Loads the route file and indexes the bank / pen walk-areas by name. The
 * canonical names are {@code lumbridge_bank} and {@code chicken_pen}.
 * Everything between (in order) is the bank-to-pen route; reverse for
 * the return trip.
 */
public final class FarmConfig
{
    public static final String BANK_NAME = "lumbridge_bank";
    public static final String PEN_NAME = "chicken_pen";
    /** Tolerance in tiles for "I'm near the next waypoint" arrival checks. */
    public static final int ARRIVAL_TILE_TOLERANCE = 2;
    /** Tolerance in tiles for "I'm on the path" resume detection. */
    public static final int RESUME_TILE_TOLERANCE = 8;
    public static final Path DEFAULT_ROUTE_FILE = Paths.get(
        System.getProperty("user.home"),
        ".runelite", "sequencer", "routes", "lumbridge_bank_to_pen.txt");

    private final WorldArea bankArea;
    private final WorldArea penArea;
    private final List<Waypoint> routeBankToPen;

    private FarmConfig(WorldArea bank, WorldArea pen, List<Waypoint> route)
    {
        this.bankArea = bank;
        this.penArea = pen;
        this.routeBankToPen = List.copyOf(route);
    }

    public WorldArea bankArea() { return bankArea; }
    public WorldArea penArea() { return penArea; }
    public List<Waypoint> routeBankToPen() { return routeBankToPen; }

    /** Reverse of {@link #routeBankToPen()}; same waypoints, opposite order.
     *  Transports auto-skip if already-open so reusing the same waypoints is safe. */
    public List<Waypoint> routePenToBank()
    {
        return List.copyOf(reversed(routeBankToPen));
    }

    private static <T> List<T> reversed(List<T> in)
    {
        List<T> out = new java.util.ArrayList<>(in);
        java.util.Collections.reverse(out);
        return out;
    }

    public static FarmConfig load(Path routeFile) throws java.io.IOException
    {
        String text = Files.readString(routeFile);
        RouteParser.Result r = RouteParser.parse(text);
        if (r.hasErrors())
            throw new IllegalStateException("route file errors: " + r.errors());
        WorldArea bank = null;
        WorldArea pen = null;
        java.util.List<Waypoint> mid = new java.util.ArrayList<>();
        boolean afterBank = false;
        for (Waypoint w : r.waypoints())
        {
            if (BANK_NAME.equals(w.name()) && w.kind() == Waypoint.Kind.WALK_AREA)
            {
                bank = w.area();
                afterBank = true;
                continue;
            }
            if (PEN_NAME.equals(w.name()) && w.kind() == Waypoint.Kind.WALK_AREA)
            {
                pen = w.area();
                break;
            }
            if (afterBank) mid.add(w);
        }
        if (bank == null) throw new IllegalStateException(
            "route file missing '" + BANK_NAME + ":' walkbox");
        if (pen == null) throw new IllegalStateException(
            "route file missing '" + PEN_NAME + ":' walkbox");
        return new FarmConfig(bank, pen, mid);
    }
}
```

- [ ] **Step 2: Compile**

```bash
./gradlew :client:compileJava
```

- [ ] **Step 3: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/plugins/recorder/farm/FarmConfig.java
git commit -m "recorder/farm: FarmConfig loads bank/pen + route from named route file

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

### Task 9: `InventoryUtil` — free-slot count

**Files:**
- Create: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/farm/InventoryUtil.java`
- Create: `runelite-client/src/test/java/net/runelite/client/plugins/recorder/farm/InventoryUtilTest.java`

- [ ] **Step 1: Write the failing test**

```java
package net.runelite.client.plugins.recorder.farm;

import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.gameval.InventoryID;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class InventoryUtilTest
{
    @Test
    public void emptyInventoryHas28FreeSlots()
    {
        Client c = mock(Client.class);
        ItemContainer inv = mock(ItemContainer.class);
        when(c.getItemContainer(InventoryID.INV)).thenReturn(inv);
        Item[] items = new Item[28];
        for (int i = 0; i < 28; i++)
        {
            Item it = mock(Item.class);
            when(it.getId()).thenReturn(-1);
            items[i] = it;
        }
        when(inv.getItems()).thenReturn(items);
        assertEquals(28, InventoryUtil.freeSlotCount(c));
        assertFalse(InventoryUtil.isInventoryFull(c));
    }

    @Test
    public void fullInventoryIsFull()
    {
        Client c = mock(Client.class);
        ItemContainer inv = mock(ItemContainer.class);
        when(c.getItemContainer(InventoryID.INV)).thenReturn(inv);
        Item[] items = new Item[28];
        for (int i = 0; i < 28; i++)
        {
            Item it = mock(Item.class);
            when(it.getId()).thenReturn(314);
            items[i] = it;
        }
        when(inv.getItems()).thenReturn(items);
        assertEquals(0, InventoryUtil.freeSlotCount(c));
        assertTrue(InventoryUtil.isInventoryFull(c));
    }

    @Test
    public void nullContainerTreatedAsFullyEmpty()
    {
        Client c = mock(Client.class);
        when(c.getItemContainer(InventoryID.INV)).thenReturn(null);
        assertEquals(28, InventoryUtil.freeSlotCount(c));
    }
}
```

- [ ] **Step 2: Run to verify failure**

```bash
./gradlew :client:test --tests 'net.runelite.client.plugins.recorder.farm.InventoryUtilTest'
```

Expected: fails (`InventoryUtil` undefined).

- [ ] **Step 3: Implement**

```java
/* RuneLite copyright header. */
package net.runelite.client.plugins.recorder.farm;

import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.gameval.InventoryID;

/** Inventory snapshot helpers. Reads the player inventory item container
 *  and counts empty slots; works on the client thread. */
public final class InventoryUtil
{
    public static final int INVENTORY_SIZE = 28;

    private InventoryUtil() {}

    public static int freeSlotCount(Client client)
    {
        ItemContainer inv = client.getItemContainer(InventoryID.INV);
        if (inv == null) return INVENTORY_SIZE;
        Item[] items = inv.getItems();
        if (items == null) return INVENTORY_SIZE;
        int free = INVENTORY_SIZE - items.length;
        for (Item it : items)
        {
            if (it == null || it.getId() <= 0) free++;
        }
        return Math.min(free, INVENTORY_SIZE);
    }

    public static boolean isInventoryFull(Client client)
    {
        return freeSlotCount(client) == 0;
    }
}
```

- [ ] **Step 4: Run, then commit**

```bash
./gradlew :client:test --tests 'net.runelite.client.plugins.recorder.farm.InventoryUtilTest'
git add runelite-client/src/main/java/net/runelite/client/plugins/recorder/farm/InventoryUtil.java \
        runelite-client/src/test/java/net/runelite/client/plugins/recorder/farm/InventoryUtilTest.java
git commit -m "recorder/farm: InventoryUtil.freeSlotCount + isInventoryFull

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

### Task 10: `BankInteraction` — open / deposit / close

The Lumbridge upstairs bank uses a bank booth (`Bank` left-click verb). Once open, the inventory orb on the bank widget is "Deposit inventory" — a known widget child id.

**Files:**
- Create: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/farm/BankInteraction.java`

- [ ] **Step 1: Implement (no unit test — needs live widget tree)**

```java
/* RuneLite copyright header. */
package net.runelite.client.plugins.recorder.farm;

import java.awt.Rectangle;
import java.util.concurrent.atomic.AtomicBoolean;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.sequence.dispatch.HumanizedInputDispatcher;

/**
 * Drives the bank booth interaction at Lumbridge upstairs.
 *
 * <p>The flow: find a bank booth NPC inside {@code bankArea}, click it,
 * wait for the bank widget to appear, click the "Deposit inventory" orb,
 * close. Each step uses the same humanized dispatcher the rest of the
 * system uses.
 */
public final class BankInteraction
{
    private final Client client;
    private final ClientThread clientThread;
    private final HumanizedInputDispatcher dispatcher;

    public BankInteraction(Client client, ClientThread clientThread,
                           HumanizedInputDispatcher dispatcher)
    {
        this.client = client;
        this.clientThread = clientThread;
        this.dispatcher = dispatcher;
    }

    public boolean isBankOpen()
    {
        Widget w = client.getWidget(InterfaceID.Bankmain.UNIVERSE);
        return w != null && !w.isHidden();
    }

    /** Step 1: click a bank booth NPC. Returns true on click dispatched. */
    public boolean clickBankBooth() throws InterruptedException
    {
        NPC booth = findBankBoothNPC();
        if (booth == null) return false;
        var hull = booth.getConvexHull();
        if (hull == null) return false;
        Rectangle b = hull.getBounds();
        int cx = b.x + b.width / 2;
        int cy = b.y + b.height / 2;
        dispatcher.clickCanvas(cx, cy);
        return true;
    }

    private NPC findBankBoothNPC()
    {
        AtomicBoolean done = new AtomicBoolean(false);
        NPC[] hit = new NPC[1];
        clientThread.invokeLater(() -> {
            var wv = client.getTopLevelWorldView();
            if (wv != null)
            {
                for (NPC npc : wv.npcs())
                {
                    if (npc == null) continue;
                    String name = npc.getName();
                    if (name == null) continue;
                    if (name.toLowerCase().contains("banker")
                        || name.toLowerCase().contains("bank booth"))
                    {
                        hit[0] = npc;
                        break;
                    }
                }
            }
            done.set(true);
        });
        long deadline = System.currentTimeMillis() + 500;
        while (!done.get() && System.currentTimeMillis() < deadline)
        {
            try { Thread.sleep(20); } catch (InterruptedException ie)
            { Thread.currentThread().interrupt(); break; }
        }
        return hit[0];
    }

    /** Step 2: click the deposit-inventory orb on the bank widget. */
    public boolean clickDepositInventory() throws InterruptedException
    {
        Widget w = client.getWidget(InterfaceID.Bankmain.DEPOSITINV);
        if (w == null || w.isHidden()) return false;
        Rectangle b = w.getBounds();
        if (b == null || b.isEmpty()) return false;
        dispatcher.clickCanvas(b.x + b.width / 2, b.y + b.height / 2);
        return true;
    }

    /** Step 3: close the bank widget by pressing Escape. */
    public void closeBank() throws InterruptedException
    {
        // The dispatcher exposes a key-press helper; if the project uses
        // pressKey(KeyEvent.VK_ESCAPE) wire that here. As a fallback, click
        // the X widget.
        Widget close = client.getWidget(InterfaceID.Bankmain.CLOSE);
        if (close == null || close.isHidden()) return;
        Rectangle b = close.getBounds();
        if (b == null || b.isEmpty()) return;
        dispatcher.clickCanvas(b.x + b.width / 2, b.y + b.height / 2);
    }
}
```

NOTE: The exact `InterfaceID.Bankmain.*` constant names may differ in this RuneLite fork. Before this step, run:

```bash
grep -rn 'Bankmain' runelite-api/src/ runelite-client/src/main/java/ 2>/dev/null | head -20
```

…and substitute the right field names. Common alternatives in older code: `WidgetInfo.BANK_CONTAINER`, `WidgetInfo.BANK_DEPOSIT_INVENTORY`, `WidgetInfo.BANK_TITLE_BAR`. Patch the imports to match.

- [ ] **Step 2: Compile + commit**

```bash
./gradlew :client:compileJava
git add runelite-client/src/main/java/net/runelite/client/plugins/recorder/farm/BankInteraction.java
git commit -m "recorder/farm: BankInteraction click-booth, deposit-inv, close

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

### Task 11: `RouteWalker` — area-aware per-waypoint runtime

**Files:**
- Create: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/farm/RouteWalker.java`
- Create: `runelite-client/src/test/java/net/runelite/client/plugins/recorder/farm/RouteWalkerTest.java`

- [ ] **Step 1: Write a focused tile-sampler test (the part we can unit-test without a live client)**

```java
package net.runelite.client.plugins.recorder.farm;

import java.util.ArrayList;
import java.util.List;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.*;

public class RouteWalkerTest
{
    @Test
    public void sampleTileReturnsTileInsideTheArea()
    {
        WorldArea a = new WorldArea(3091, 3243, 7, 5, 2);
        java.util.Random rng = new java.util.Random(42);
        WorldPoint t = RouteWalker.sampleTile(a, rng, p -> true);
        assertNotNull(t);
        assertTrue(t.getX() >= 3091 && t.getX() <= 3097);
        assertTrue(t.getY() >= 3243 && t.getY() <= 3247);
        assertEquals(2, t.getPlane());
    }

    @Test
    public void sampleTileSkipsRejectedTiles()
    {
        WorldArea a = new WorldArea(0, 0, 3, 3, 0);
        java.util.Random rng = new java.util.Random(0);
        // Reject every tile except (1,1).
        WorldPoint t = RouteWalker.sampleTile(a, rng, p -> p.getX() == 1 && p.getY() == 1);
        assertNotNull(t);
        assertEquals(1, t.getX());
        assertEquals(1, t.getY());
    }

    @Test
    public void sampleTileReturnsNullWhenAllTilesRejected()
    {
        WorldArea a = new WorldArea(0, 0, 3, 3, 0);
        java.util.Random rng = new java.util.Random(0);
        assertNull(RouteWalker.sampleTile(a, rng, p -> false));
    }
}
```

- [ ] **Step 2: Run to verify failure**

```bash
./gradlew :client:test --tests 'net.runelite.client.plugins.recorder.farm.RouteWalkerTest'
```

Expected: fails (`RouteWalker.sampleTile` undefined).

- [ ] **Step 3: Implement**

```java
/* RuneLite copyright header. */
package net.runelite.client.plugins.recorder.farm;

import java.awt.Rectangle;
import java.util.Random;
import java.util.function.Predicate;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.plugins.recorder.combat.TargetVisibility;
import net.runelite.client.plugins.recorder.transport.TransportResolver;
import net.runelite.client.plugins.recorder.transport.Waypoint;
import net.runelite.client.sequence.dispatch.HumanizedInputDispatcher;

/** Drives a single waypoint per tick. The outer state machine pumps
 *  {@link #tick(Waypoint)} repeatedly until {@link #arrived(Waypoint)}
 *  returns true, then advances to the next waypoint.
 *
 *  <p>For WALK_AREA: samples a random tile that satisfies (same plane,
 *  reachable on foot, on canvas, in viewport, not occupied by an NPC),
 *  minimap-clicks it. Re-rolls tile each tick.
 *
 *  <p>For TRANSPORT: pre-checks the verb via {@link TransportResolver}.
 *  If the object exposing that verb isn't present (gate already open),
 *  arrival is immediate. Otherwise click and wait for crossing.
 */
@Slf4j
public final class RouteWalker
{
    private final Client client;
    private final ClientThread clientThread;
    private final HumanizedInputDispatcher dispatcher;
    private final TransportResolver resolver;
    private final Random rng = new Random();

    public RouteWalker(Client client, ClientThread clientThread,
                       HumanizedInputDispatcher dispatcher, TransportResolver resolver)
    {
        this.client = client;
        this.clientThread = clientThread;
        this.dispatcher = dispatcher;
        this.resolver = resolver;
    }

    /** Drive {@code wp} once. Caller is responsible for arrival polling. */
    public void tick(Waypoint wp) throws InterruptedException
    {
        switch (wp.kind())
        {
            case WALK:
            case WALK_AREA:
                tickWalk(wp.area());
                break;
            case TRANSPORT:
                tickTransport(wp);
                break;
        }
    }

    public boolean arrived(Waypoint wp)
    {
        Player self = client.getLocalPlayer();
        if (self == null) return false;
        WorldPoint here = self.getWorldLocation();
        if (here == null) return false;
        switch (wp.kind())
        {
            case WALK:
            case WALK_AREA:
            {
                WorldArea a = wp.area();
                return here.getPlane() == a.getPlane()
                    && here.getX() >= a.getX() && here.getX() < a.getX() + a.getWidth()
                    && here.getY() >= a.getY() && here.getY() < a.getY() + a.getHeight();
            }
            case TRANSPORT:
            {
                if (wp.transportKind() == Waypoint.TransportKind.CLIMB_UP
                    || wp.transportKind() == Waypoint.TransportKind.CLIMB_DOWN)
                {
                    // Plane changed = transit complete.
                    return here.getPlane() != wp.tile().getPlane();
                }
                // OPEN / INTERACT — arrived if we crossed the wall, i.e.
                // we're now on the opposite side of the gate tile from
                // our pre-click position. Conservative: arrived if we're
                // adjacent to the wall on either side.
                return Math.abs(here.getX() - wp.tile().getX()) <= 1
                    && Math.abs(here.getY() - wp.tile().getY()) <= 1
                    && here.getPlane() == wp.tile().getPlane();
            }
        }
        return false;
    }

    private void tickWalk(WorldArea area) throws InterruptedException
    {
        Player self = client.getLocalPlayer();
        if (self == null) return;
        WorldPoint here = self.getWorldLocation();
        if (here == null) return;
        WorldPoint pick = sampleTile(area, rng,
            tile -> tile.getPlane() == here.getPlane()
                 && projectsToCanvas(tile));
        if (pick == null) return; // try again next tick
        // Translate world tile → canvas pixel via Perspective and click.
        var wv = client.getTopLevelWorldView();
        if (wv == null) return;
        LocalPoint lp = LocalPoint.fromWorld(wv, pick);
        if (lp == null) return;
        net.runelite.api.Point cp = net.runelite.api.Perspective.localToCanvas(client, lp, pick.getPlane());
        if (cp == null) return;
        dispatcher.clickCanvas(cp.getX(), cp.getY());
    }

    private boolean projectsToCanvas(WorldPoint wp)
    {
        var wv = client.getTopLevelWorldView();
        if (wv == null) return false;
        LocalPoint lp = LocalPoint.fromWorld(wv, wp);
        if (lp == null) return false;
        net.runelite.api.Point cp = net.runelite.api.Perspective.localToCanvas(client, lp, wp.getPlane());
        if (cp == null) return false;
        // Inside playable viewport.
        int vx = client.getViewportXOffset();
        int vy = client.getViewportYOffset();
        int vw = client.getViewportWidth();
        int vh = client.getViewportHeight();
        return cp.getX() >= vx && cp.getX() < vx + vw
            && cp.getY() >= vy && cp.getY() < vy + vh;
    }

    private void tickTransport(Waypoint wp) throws InterruptedException
    {
        TransportResolver.Match m = resolver.findTransport(wp.tile(), wp.verb());
        if (!m.isSuccess())
        {
            // Verb absent → likely already-open / not yet loaded. Skip; the
            // farm loop's arrival check will see the player progress.
            log.debug("transport at {} skipped (verb '{}' absent)", wp.tile(), wp.verb());
            return;
        }
        Rectangle b = clickTargetBounds(m);
        if (b == null) return;
        dispatcher.clickCanvas(b.x + b.width / 2, b.y + b.height / 2);
    }

    private Rectangle clickTargetBounds(TransportResolver.Match m)
    {
        if (m.wallObject() != null && m.wallObject().getConvexHull() != null)
            return m.wallObject().getConvexHull().getBounds();
        if (m.gameObject() != null && m.gameObject().getConvexHull() != null)
            return m.gameObject().getConvexHull().getBounds();
        if (m.decorativeObject() != null)
            return m.decorativeObject().getCanvasTilePoly() == null ? null
                : m.decorativeObject().getCanvasTilePoly().getBounds();
        if (m.groundObject() != null)
            return m.groundObject().getCanvasTilePoly() == null ? null
                : m.groundObject().getCanvasTilePoly().getBounds();
        return null;
    }

    /** Pure sampler — exposed package-private for unit tests. */
    static WorldPoint sampleTile(WorldArea a, Random rng, Predicate<WorldPoint> accept)
    {
        int n = a.getWidth() * a.getHeight();
        // Up to 3*N attempts — covers the case where most tiles are rejected.
        int attempts = Math.max(8, n * 3);
        for (int i = 0; i < attempts; i++)
        {
            int x = a.getX() + rng.nextInt(a.getWidth());
            int y = a.getY() + rng.nextInt(a.getHeight());
            WorldPoint p = new WorldPoint(x, y, a.getPlane());
            if (accept.test(p)) return p;
        }
        // Last resort: enumerate every tile in random order.
        java.util.List<WorldPoint> all = new java.util.ArrayList<>(n);
        for (int dx = 0; dx < a.getWidth(); dx++)
            for (int dy = 0; dy < a.getHeight(); dy++)
                all.add(new WorldPoint(a.getX() + dx, a.getY() + dy, a.getPlane()));
        java.util.Collections.shuffle(all, rng);
        for (WorldPoint p : all) if (accept.test(p)) return p;
        return null;
    }
}
```

- [ ] **Step 4: Run, then commit**

```bash
./gradlew :client:test --tests 'net.runelite.client.plugins.recorder.farm.RouteWalkerTest'
git add runelite-client/src/main/java/net/runelite/client/plugins/recorder/farm/RouteWalker.java \
        runelite-client/src/test/java/net/runelite/client/plugins/recorder/farm/RouteWalkerTest.java
git commit -m "recorder/farm: RouteWalker drives one waypoint per tick

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

### Task 12: Constrain `NpcSelector` by area

The combat selector must reject chickens outside the pen. This is one new field + one new check + one new enum value.

**Files:**
- Modify: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/combat/NpcSelector.java`
- Modify: `runelite-client/src/test/java/net/runelite/client/plugins/recorder/combat/NpcSelectorTest.java`

- [ ] **Step 1: Write the failing test**

Append to `NpcSelectorTest`:

```java
@Test
public void rejectsChickenOutsideArea()
{
    WorldPoint here = new WorldPoint(3236, 3296, 0);
    WorldArea pen = new WorldArea(3232, 3293, 8, 8, 0);
    NPC inside = mockChicken(1, 3235, 3296, 0);
    NPC outside = mockChicken(2, 3245, 3296, 0); // east of the pen
    NpcSelector sel = new NpcSelector("Chicken", NpcSelector.DEFAULT_RANGE, pen);
    assertEquals(NpcSelector.Rejection.OUT_OF_AREA,
        sel.classify(outside, null, here, -1, null, null));
    assertNull(sel.classify(inside, null, here, -1, null, null));
}
```

(Add `import net.runelite.api.coords.WorldArea;` to the test file.)

- [ ] **Step 2: Run to verify failure**

```bash
./gradlew :client:test --tests 'net.runelite.client.plugins.recorder.combat.NpcSelectorTest.rejectsChickenOutsideArea'
```

- [ ] **Step 3: Implement**

In `NpcSelector.java`:

1. Add field + constructor + enum value:

```java
@Nullable private final net.runelite.api.coords.WorldArea confineTo;

public NpcSelector(String nameFilter)
{
    this(nameFilter, DEFAULT_RANGE, null);
}

public NpcSelector(String nameFilter, int range)
{
    this(nameFilter, range, null);
}

public NpcSelector(String nameFilter, int range,
                   @Nullable net.runelite.api.coords.WorldArea confineTo)
{
    this.nameFilter = Objects.requireNonNull(nameFilter, "nameFilter");
    this.range = range;
    this.confineTo = confineTo;
}
```

2. Add `OUT_OF_AREA` to the enum (place after `OUT_OF_RANGE`):

```java
/** Selector was constructed with a confinement area and the NPC's
 *  tile sits outside it. */
OUT_OF_AREA,
```

3. Add the check in `classify(...)` immediately after the `OUT_OF_RANGE` check:

```java
if (confineTo != null)
{
    if (npcLoc.getPlane() != confineTo.getPlane()
        || npcLoc.getX() < confineTo.getX()
        || npcLoc.getX() >= confineTo.getX() + confineTo.getWidth()
        || npcLoc.getY() < confineTo.getY()
        || npcLoc.getY() >= confineTo.getY() + confineTo.getHeight())
    {
        return Rejection.OUT_OF_AREA;
    }
}
```

- [ ] **Step 4: Run all combat tests**

```bash
./gradlew :client:test --tests 'net.runelite.client.plugins.recorder.combat.*'
```

Expected: all PASS.

- [ ] **Step 5: Pass the area through `ChickenCombatLoop`**

Add an optional `WorldArea confineTo` to the combat-loop constructor and pass it into the `NpcSelector`. Existing callers (test code, side panel) keep working because the additional constructor takes a default of `null`.

- [ ] **Step 6: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/plugins/recorder/combat/NpcSelector.java \
        runelite-client/src/main/java/net/runelite/client/plugins/recorder/combat/ChickenCombatLoop.java \
        runelite-client/src/test/java/net/runelite/client/plugins/recorder/combat/NpcSelectorTest.java
git commit -m "$(cat <<'EOF'
recorder/combat: NpcSelector confinement area for farm-loop targeting

When constructed with a non-null WorldArea, the selector rejects NPCs
whose tile sits outside that area (Rejection.OUT_OF_AREA). The farm
loop pins combat to penArea so the bot never chases a chicken that
wandered out into the courtyard.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

### Task 13: `ChickenFarmLoop` state machine

**Files:**
- Create: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/farm/ChickenFarmLoop.java`
- Create: `runelite-client/src/test/java/net/runelite/client/plugins/recorder/farm/ChickenFarmLoopTest.java`

- [ ] **Step 1: Write a state-decision test (pure function — no client mocking needed)**

```java
package net.runelite.client.plugins.recorder.farm;

import java.util.List;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.transport.Waypoint;
import org.junit.Test;
import static org.junit.Assert.*;

public class ChickenFarmLoopTest
{
    private static final WorldArea BANK = new WorldArea(3091, 3243, 7, 5, 2);
    private static final WorldArea PEN  = new WorldArea(3232, 3293, 8, 8, 0);
    private static final List<Waypoint> ROUTE = List.of(
        Waypoint.walkArea("step1", new WorldArea(3204, 3208, 8, 8, 0)),
        Waypoint.transport(new WorldPoint(3239, 3295, 0),
            Waypoint.TransportKind.OPEN, "Open"));

    @Test
    public void inPenWithFreeSlotsKills()
    {
        assertEquals(ChickenFarmLoop.State.KILLING,
            ChickenFarmLoop.decideResume(BANK, PEN, ROUTE,
                new WorldPoint(3236, 3296, 0), 5));
    }

    @Test
    public void inPenWithFullInvWalksToBank()
    {
        assertEquals(ChickenFarmLoop.State.WALKING_TO_BANK,
            ChickenFarmLoop.decideResume(BANK, PEN, ROUTE,
                new WorldPoint(3236, 3296, 0), 0));
    }

    @Test
    public void inBankWithItemsBanks()
    {
        assertEquals(ChickenFarmLoop.State.BANKING,
            ChickenFarmLoop.decideResume(BANK, PEN, ROUTE,
                new WorldPoint(3094, 3245, 2), 0));
    }

    @Test
    public void inBankWithEmptyInvWalksToPen()
    {
        assertEquals(ChickenFarmLoop.State.WALKING_TO_PEN,
            ChickenFarmLoop.decideResume(BANK, PEN, ROUTE,
                new WorldPoint(3094, 3245, 2), 28));
    }

    @Test
    public void onPathFullInvHeadsToBank()
    {
        assertEquals(ChickenFarmLoop.State.WALKING_TO_BANK,
            ChickenFarmLoop.decideResume(BANK, PEN, ROUTE,
                new WorldPoint(3208, 3210, 0), 0));
    }

    @Test
    public void onPathEmptyInvHeadsToPen()
    {
        assertEquals(ChickenFarmLoop.State.WALKING_TO_PEN,
            ChickenFarmLoop.decideResume(BANK, PEN, ROUTE,
                new WorldPoint(3208, 3210, 0), 28));
    }

    @Test
    public void unknownLocationAborts()
    {
        assertEquals(ChickenFarmLoop.State.ABORTED,
            ChickenFarmLoop.decideResume(BANK, PEN, ROUTE,
                new WorldPoint(2000, 2000, 0), 28));
    }
}
```

- [ ] **Step 2: Run to verify failure**

```bash
./gradlew :client:test --tests 'net.runelite.client.plugins.recorder.farm.ChickenFarmLoopTest'
```

- [ ] **Step 3: Implement**

```java
/* RuneLite copyright header. */
package net.runelite.client.plugins.recorder.farm;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.plugins.recorder.combat.ChickenCombatLoop;
import net.runelite.client.plugins.recorder.transport.TransportResolver;
import net.runelite.client.plugins.recorder.transport.Waypoint;
import net.runelite.client.sequence.dispatch.HumanizedInputDispatcher;

/** Outer state machine: KILLING / WALKING_TO_BANK / BANKING /
 *  WALKING_TO_PEN. Wraps ChickenCombatLoop and RouteWalker.
 *
 *  <p>Pure state-decision logic is exposed via
 *  {@link #decideResume} so tests can exercise it without mocking the
 *  client.
 */
@Slf4j
public final class ChickenFarmLoop
{
    public enum State { IDLE, KILLING, WALKING_TO_BANK, BANKING,
                        WALKING_TO_PEN, ABORTED }

    private final Client client;
    private final ClientThread clientThread;
    private final HumanizedInputDispatcher dispatcher;
    private final FarmConfig config;
    private final RouteWalker walker;
    private final BankInteraction bank;
    private final ChickenCombatLoop combat;

    private final AtomicReference<State> state = new AtomicReference<>(State.IDLE);
    private final AtomicReference<String> status = new AtomicReference<>("idle");
    private final AtomicInteger routeIdx = new AtomicInteger(0);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<Thread> worker = new AtomicReference<>();

    public ChickenFarmLoop(Client client, ClientThread clientThread,
                           HumanizedInputDispatcher dispatcher, FarmConfig config)
    {
        this.client = client;
        this.clientThread = clientThread;
        this.dispatcher = dispatcher;
        this.config = config;
        TransportResolver resolver = new TransportResolver(client);
        this.walker = new RouteWalker(client, clientThread, dispatcher, resolver);
        this.bank = new BankInteraction(client, clientThread, dispatcher);
        this.combat = new ChickenCombatLoop(dispatcher, client, clientThread, config.penArea());
        // ↑ Adjust ChickenCombatLoop ctor in Task 12 to accept WorldArea or
        //   pass via setter.
    }

    public State state() { return state.get(); }
    public String status() { return status.get(); }

    public void start()
    {
        if (!running.compareAndSet(false, true)) return;
        Player self = client.getLocalPlayer();
        WorldPoint here = self == null ? null : self.getWorldLocation();
        int free = InventoryUtil.freeSlotCount(client);
        State decided = here == null ? State.ABORTED
            : decideResume(config.bankArea(), config.penArea(),
                config.routeBankToPen(), here, free);
        state.set(decided);
        status.set("resume → " + decided);
        if (decided == State.ABORTED)
        {
            running.set(false);
            return;
        }
        // Outer worker thread runs the tick loop until stopped.
        Thread t = new Thread(this::tickLoop, "chicken-farm");
        t.setDaemon(true);
        worker.set(t);
        t.start();
    }

    public void stop()
    {
        running.set(false);
        Thread t = worker.getAndSet(null);
        if (t != null) t.interrupt();
        combat.stop();
        state.set(State.IDLE);
        status.set("stopped");
    }

    private void tickLoop()
    {
        try
        {
            while (running.get() && !Thread.currentThread().isInterrupted())
            {
                State s = state.get();
                switch (s)
                {
                    case KILLING:           tickKilling();       break;
                    case WALKING_TO_BANK:   tickWalking(true);   break;
                    case BANKING:           tickBanking();       break;
                    case WALKING_TO_PEN:    tickWalking(false);  break;
                    case ABORTED:
                    case IDLE:
                    default:                running.set(false);  break;
                }
                Thread.sleep(200 + (int)(Math.random() * 400));
            }
        }
        catch (InterruptedException ie)
        {
            Thread.currentThread().interrupt();
        }
        finally
        {
            running.set(false);
        }
    }

    private void tickKilling() throws InterruptedException
    {
        if (combat.state() == ChickenCombatLoop.State.IDLE) combat.start();
        if (InventoryUtil.isInventoryFull(client))
        {
            combat.stop();
            routeIdx.set(config.routeBankToPen().size() - 1);
            state.set(State.WALKING_TO_BANK);
        }
    }

    private void tickWalking(boolean toBank) throws InterruptedException
    {
        List<Waypoint> route = toBank ? config.routePenToBank() : config.routeBankToPen();
        int idx = routeIdx.get();
        if (idx < 0 || idx >= route.size())
        {
            // Past the route — check terminal arrival.
            Player self = client.getLocalPlayer();
            WorldPoint here = self == null ? null : self.getWorldLocation();
            if (here != null && contains(toBank ? config.bankArea() : config.penArea(), here))
            {
                state.set(toBank ? State.BANKING : State.KILLING);
                routeIdx.set(0);
            }
            return;
        }
        Waypoint wp = route.get(idx);
        if (walker.arrived(wp))
        {
            routeIdx.set(idx + 1);
            return;
        }
        walker.tick(wp);
    }

    private void tickBanking() throws InterruptedException
    {
        if (!bank.isBankOpen())
        {
            if (!bank.clickBankBooth()) status.set("bank booth not visible");
            return;
        }
        if (!InventoryUtil.isInventoryFull(client) && InventoryUtil.freeSlotCount(client) >= 27)
        {
            // Close-and-walk after deposit succeeded (free slots ≈ 28).
            bank.closeBank();
            routeIdx.set(0);
            state.set(State.WALKING_TO_PEN);
            return;
        }
        bank.clickDepositInventory();
    }

    /** Pure resume decision for tests. */
    public static State decideResume(WorldArea bank, WorldArea pen,
                                     List<Waypoint> route, WorldPoint here, int freeSlots)
    {
        if (contains(pen, here)) return freeSlots == 0 ? State.WALKING_TO_BANK : State.KILLING;
        if (contains(bank, here)) return freeSlots == 28 ? State.WALKING_TO_PEN : State.BANKING;
        if (nearAnyWaypoint(route, here, FarmConfig.RESUME_TILE_TOLERANCE))
            return freeSlots == 0 ? State.WALKING_TO_BANK : State.WALKING_TO_PEN;
        return State.ABORTED;
    }

    private static boolean contains(WorldArea a, WorldPoint p)
    {
        return a.getPlane() == p.getPlane()
            && p.getX() >= a.getX() && p.getX() < a.getX() + a.getWidth()
            && p.getY() >= a.getY() && p.getY() < a.getY() + a.getHeight();
    }

    private static boolean nearAnyWaypoint(List<Waypoint> route, WorldPoint here, int tol)
    {
        for (Waypoint w : route)
        {
            WorldArea a = w.area();
            if (a != null)
            {
                if (a.getPlane() != here.getPlane()) continue;
                if (here.getX() >= a.getX() - tol
                    && here.getX() < a.getX() + a.getWidth() + tol
                    && here.getY() >= a.getY() - tol
                    && here.getY() < a.getY() + a.getHeight() + tol)
                    return true;
            }
            else if (w.tile() != null)
            {
                if (w.tile().getPlane() != here.getPlane()) continue;
                if (Math.abs(w.tile().getX() - here.getX()) <= tol
                    && Math.abs(w.tile().getY() - here.getY()) <= tol)
                    return true;
            }
        }
        return false;
    }
}
```

- [ ] **Step 4: Run, then commit**

```bash
./gradlew :client:test --tests 'net.runelite.client.plugins.recorder.farm.*'
git add runelite-client/src/main/java/net/runelite/client/plugins/recorder/farm/ChickenFarmLoop.java \
        runelite-client/src/test/java/net/runelite/client/plugins/recorder/farm/ChickenFarmLoopTest.java
git commit -m "recorder/farm: ChickenFarmLoop state machine + resume logic

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

### Task 14: Replace combat-section buttons with farm-loop buttons

**Files:**
- Modify: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/RecorderPanel.java`

- [ ] **Step 1: Replace handlers**

Where `chickenStartBtn.addActionListener(e -> ...)` exists, change it to call `farmLoop.start()`. Similarly for stop. Update `chickenStatusLabel` text to read from `farmLoop.status()` and `farmLoop.state()`. Keep the same button labels (or rename to "Start farm" / "Stop farm" if you prefer).

- [ ] **Step 2: Wire farmLoop**

Add to `RecorderPanel`:

```java
private ChickenFarmLoop farmLoop;
public void setFarmLoop(ChickenFarmLoop fl) { this.farmLoop = fl; }
```

In `RecorderPlugin.startUp()`:

```java
FarmConfig farmCfg;
try { farmCfg = FarmConfig.load(FarmConfig.DEFAULT_ROUTE_FILE); }
catch (Exception ex) { log.warn("farm route load failed: {}", ex.getMessage()); farmCfg = null; }
if (farmCfg != null)
{
    HumanizedInputDispatcher farmDispatcher = new HumanizedInputDispatcher(client, clientThread);
    ChickenFarmLoop farmLoop = new ChickenFarmLoop(client, clientThread, farmDispatcher, farmCfg);
    panel.setFarmLoop(farmLoop);
}
```

The bare `chickenLoop` field stays — it's still used for tests. The panel only exposes the farm loop now.

- [ ] **Step 3: Compile + manual smoke**

```bash
./gradlew :client:shadowJar
# kill + relaunch as in Task 6 step 6
```

Manual checklist:
1. Without a route file at `~/.runelite/sequencer/routes/lumbridge_bank_to_pen.txt`, the panel shows the farm buttons but Start logs a warning (route load failed).
2. With a route file present, Start triggers the resume flow and the status label updates.

- [ ] **Step 4: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/plugins/recorder/RecorderPanel.java \
        runelite-client/src/main/java/net/runelite/client/plugins/recorder/RecorderPlugin.java
git commit -m "$(cat <<'EOF'
recorder: side panel drives ChickenFarmLoop instead of bare combat loop

Same Start/Stop/status surface, now wraps the outer state machine that
walks bank ↔ pen and banks the loot. Bare ChickenCombatLoop stays as an
internal sub-loop driven by the farm loop; not exposed in the panel.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

### Task 15: End-to-end smoke and push

- [ ] **Step 1: Verify the full test suite**

```bash
./gradlew :client:test
```

Expected: BUILD SUCCESSFUL, no regressions.

- [ ] **Step 2: Manual verification (with a recorded route file in place)**

1. Stand at Lumbridge bank booth with empty inventory → Start → bot opens bank? (Likely NO — needs items to deposit; ABORTED is also acceptable. Confirm with user before changing the resume rule.)
2. Stand at the bank with items in inv → Start → bot deposits, walks to pen, kills.
3. Stand inside the pen with empty inv → Start → bot kills until full, walks to bank, deposits, walks back.
4. Stand on the route with full inv → Start → bot continues to bank.
5. Standing in Varrock → Start → ABORTED with status "resume → ABORTED".

- [ ] **Step 3: Push**

```bash
git push
```

---

## Self-review

**Spec coverage:**
- Annotator with name + path-checkbox + area + object marking → Tasks 1, 2, 3, 4, 5, 6.
- Visual overlay of route while editing → Task 4 (RouteOverlay) + Task 6 step 4 (refresh on append).
- File format extension → Task 2 (`walkbox:` and named entries).
- Object IDs captured → Task 3 (`findAnyTransport.objectId()`) + Task 6 (annotation comment in line).
- Reverse direction is "free" → Task 8 (`FarmConfig.routePenToBank()` reverses the same list).
- Outer farm loop with resume logic → Tasks 11, 13.
- Banking via Deposit-inventory orb → Task 10.
- Targets restricted to penArea → Task 12.
- Gates auto-skip when already open → Task 11 (`tickTransport` returns when verb absent).
- UI surface unchanged → Task 14 reuses existing chicken buttons.

**Placeholder scan:** None. Every task has runnable code, exact commands, and a commit. Coords inside the route file are explicitly user-supplied (Task 7).

**Type consistency:**
- `Waypoint.area()` defined in Task 1, used in Tasks 4, 11, 13. ✓
- `Rejection.OUT_OF_AREA` defined in Task 12, no one else needs to reference it. ✓
- `FarmConfig` constructor lookups (`bankArea()`, `penArea()`, `routeBankToPen()`) consistent across Task 8 → 13 → 14. ✓
- `RouteWalker.arrived(Waypoint)` defined in Task 11, used in `ChickenFarmLoop.tickWalking` Task 13. ✓
- `BankInteraction.{isBankOpen, clickBankBooth, clickDepositInventory, closeBank}` defined in Task 10, used in `tickBanking` Task 13. ✓
- `ChickenCombatLoop` — Task 13 calls `combat = new ChickenCombatLoop(dispatcher, client, clientThread, penArea)` which assumes Task 12 added a 4-arg constructor. Reminder bullet inline noted; engineer must confirm the constructor signature matches.

**Open questions (call out before Phase 2 if relevant):**
- The exact `InterfaceID.Bankmain.*` member names depend on the RuneLite fork. Task 10 includes a `grep` step to confirm.
- Resume rule for "bank with empty inv": treat as WALKING_TO_PEN (Task 13 test `inBankWithEmptyInvWalksToPen`). User confirmed in conversation.
- Farm loop only loads a route from disk; there is no in-process editor pushing changes. The annotator's edits are reflected on disk; Start re-reads the file. If you want hot-reload, that's a follow-up.

---

## Execution handoff

Plan complete and saved to `docs/superpowers/plans/2026-04-27-chicken-farm-bot.md`.

**Two execution options:**

1. **Subagent-Driven (recommended)** — fresh subagent per task, review between tasks, fast iteration. Good fit because the tasks are mostly independent module additions; subagent context stays small.

2. **Inline Execution** — execute tasks in this session using executing-plans, batch execution with checkpoints. Larger context but lets you eyeball each step.

**Which approach?**
