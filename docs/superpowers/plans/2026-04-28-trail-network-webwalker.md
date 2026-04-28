# Trail-Network Web-Walker Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship a recorded-trail navigation system end-to-end so the user can record one walk from Lumby bank to the chicken pen, then run a Chicken-Farm V3 script that follows the recorded trail, kills chickens, banks, and returns — with humanized click variation on every interaction.

**Architecture:**
- New package `recorder/trail/` with `Trail`, `TrailEvent`, `TrailIO`, `TrailRegistry`, `TrailRecorder`, `TrailGraph`, `TrailPlanner`, `TrailPath`, `Leg`, `TrailWalker`. Pure-data + pure-logic except `TrailRecorder` (event-bus subscribed) and `TrailWalker` (drives the existing `HumanizedInputDispatcher`).
- `RecorderPanel` gets a "Trails" sub-section: **Record trail / Stop & save / Walk to…** dev buttons.
- New `ChickenFarmV3Script` mirrors V2's outer FSM (BANKING / OUTBOUND / AT_PEN / RETURN) but swaps `UniversalWalker.tick(PathSpec)` for `TrailWalker.tick(TrailPath)`. `BankInteraction`, `ChickenCombatLoop`, `BANK_AREA`, `PEN_AREA` are reused unchanged.
- Click humanization: tighten `PixelResolver.MIN_REPEAT_PX` and add a regression test so consecutive clicks on the same hull never land within ~6 px of each other; the walker also picks a random tile from the leg's "ahead" window each click instead of always the farthest tile.

**Tech Stack:** Java 17, Lombok, Gson (already in deps), JUnit 4 + Mockito (existing test conventions), RuneLite event bus + ClientThread.

**Spec:** `docs/superpowers/specs/2026-04-28-trail-network-webwalker-design.md`. Read it first if you're picking up this plan cold.

---

## Phase 0 — Click variation hardening

The dispatcher already samples random pixels inside an object's convex hull (`PixelResolver.resolveGameObject` / `resolveWallObject` / `resolveNpc`), and rejects pixels within 2 px of the last 12 clicks. Two pixels is too tight to feel "different to a human watching" — bump it and lock the behavior in with a regression test before adding new code that depends on click variation.

### Task 1: Tighten dispatcher click-repeat distance

**Files:**
- Modify: `runelite-client/src/main/java/net/runelite/client/sequence/dispatch/PixelResolver.java:80-83`
- Test: `runelite-client/src/test/java/net/runelite/client/sequence/dispatch/PixelResolverRepeatTest.java` (create)

- [ ] **Step 1: Write the failing test**

```java
// runelite-client/src/test/java/net/runelite/client/sequence/dispatch/PixelResolverRepeatTest.java
package net.runelite.client.sequence.dispatch;

import java.awt.Polygon;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import net.runelite.api.Point;
import org.junit.Test;
import static org.junit.Assert.*;

/** Sanity check that consecutive {@code sampleInsidePolygon} calls return
 *  pixels at least {@code MIN_REPEAT_PX} apart — i.e. the recent-click
 *  history actually de-duplicates clicks the way a human watching the
 *  bot would expect. */
public class PixelResolverRepeatTest
{
    @Test
    public void consecutiveSamplesNeverLandWithinSixPixels() throws Exception
    {
        // Create a 60x60 square hull at (100,100) — plenty of room for the
        // sampler to find non-overlapping pixels.
        Polygon hull = new Polygon(
            new int[]{100, 160, 160, 100},
            new int[]{100, 100, 160, 160},
            4);
        PixelResolver pr = new PixelResolver(null);
        Method sample = PixelResolver.class.getDeclaredMethod("sampleInsidePolygon", Polygon.class);
        sample.setAccessible(true);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 12; i++)
        {
            Point p = (Point) sample.invoke(pr, hull);
            assertNotNull("sampler returned null on iter " + i, p);
            for (String prev : seen)
            {
                String[] parts = prev.split(",");
                int dx = p.getX() - Integer.parseInt(parts[0]);
                int dy = p.getY() - Integer.parseInt(parts[1]);
                assertTrue("click " + i + " landed within 6px of a recent click: ("
                    + p.getX() + "," + p.getY() + ") vs " + prev,
                    dx * dx + dy * dy >= 36);
            }
            seen.add(p.getX() + "," + p.getY());
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :runelite-client:test --tests "net.runelite.client.sequence.dispatch.PixelResolverRepeatTest" -q`
Expected: FAIL — current `MIN_REPEAT_PX = 2` allows pixels 3-5 px apart, which is < 6 px (squared distance < 36).

- [ ] **Step 3: Bump MIN_REPEAT_PX to 6**

Edit `PixelResolver.java`. Replace:

```java
    /** No two consecutive clicks within this many pixels of each other.
     *  Small enough to fit inside a tile's minimap ring (~4px diameter)
     *  while still meaningfully de-duplicating. */
    private static final int MIN_REPEAT_PX = 2;
```

with:

```java
    /** No two consecutive clicks within this many pixels of each other.
     *  6 px is large enough that a human watching the cursor sees a
     *  visibly different landing spot between two clicks on the same
     *  model (a stairs hull is typically 30-60 px wide so the rejection
     *  budget never starves the sampler), small enough to still fit
     *  inside the minimap disc when the resolver falls back to ring
     *  jitter (the ring radius scales with the rejection budget — see
     *  {@link #ringJitter}). */
    private static final int MIN_REPEAT_PX = 6;
```

- [ ] **Step 4: Re-run test**

Run: `./gradlew :runelite-client:test --tests "net.runelite.client.sequence.dispatch.PixelResolverRepeatTest" -q`
Expected: PASS.

- [ ] **Step 5: Confirm minimap ring jitter still works**

Run: `./gradlew :runelite-client:test --tests "net.runelite.client.sequence.dispatch.*" -q`
Expected: PASS — the ring jitter uses radii 1..2 px, which is below MIN_REPEAT_PX = 6, so it falls through to the "accept the last sample" fallback at the end of `ringJitter`. That's intentional: minimap clicks are visually similar by nature; the rejection only meaningfully fires for hull-sampled object clicks.

- [ ] **Step 6: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/sequence/dispatch/PixelResolver.java \
        runelite-client/src/test/java/net/runelite/client/sequence/dispatch/PixelResolverRepeatTest.java
git commit -m "dispatch: bump click-repeat distance to 6px (humanize hull clicks)"
```

---

## Phase 1 — Trail data model + JSON I/O

Pure data, no event subscriptions. `Trail` is the in-memory model; `TrailEvent` is a sealed-style hierarchy with `TileEvent` and `TransportEvent` subtypes; `TrailIO` handles Gson read/write to `~/.runelite/recorder/trails/<name>.json`.

### Task 2: TrailEvent value classes

**Files:**
- Create: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/trail/TrailEvent.java`
- Test: `runelite-client/src/test/java/net/runelite/client/plugins/recorder/trail/TrailEventTest.java`

- [ ] **Step 1: Write the failing test**

```java
// runelite-client/src/test/java/net/runelite/client/plugins/recorder/trail/TrailEventTest.java
package net.runelite.client.plugins.recorder.trail;

import java.util.List;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.*;

public class TrailEventTest
{
    @Test
    public void tileEventCarriesTileAndOffset()
    {
        TrailEvent.Tile t = new TrailEvent.Tile(600L, new WorldPoint(3208, 3220, 2));
        assertEquals("TILE", t.kind());
        assertEquals(600L, t.msSinceStart());
        assertEquals(new WorldPoint(3208, 3220, 2), t.tile());
    }

    @Test
    public void transportEventCarriesFullMetadata()
    {
        TrailEvent.Transport tr = new TrailEvent.Transport(
            12_000L,
            new WorldPoint(3205, 3229, 2),
            "Climb-down", "Staircase", 16671, "GameObject",
            3, 53, 14,
            List.of("Climb-down Staircase", "Cancel", "Examine Staircase"));
        assertEquals("TRANSPORT", tr.kind());
        assertEquals("Climb-down", tr.option());
        assertEquals(16671, tr.targetId());
        assertEquals(3, tr.actionId());
        assertEquals(List.of("Climb-down Staircase", "Cancel", "Examine Staircase"),
            tr.menuRowsAtClick());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :runelite-client:test --tests "net.runelite.client.plugins.recorder.trail.TrailEventTest" -q`
Expected: FAIL — class doesn't exist yet.

- [ ] **Step 3: Implement TrailEvent**

```java
// runelite-client/src/main/java/net/runelite/client/plugins/recorder/trail/TrailEvent.java
package net.runelite.client.plugins.recorder.trail;

import java.util.List;
import net.runelite.api.coords.WorldPoint;

/**
 * One recorded event in a {@link Trail}. Either a {@link Tile}
 * (player position changed) or a {@link Transport} (the user clicked a
 * region-transition menu entry). Sealed-style: {@link #kind()} returns
 * {@code "TILE"} or {@code "TRANSPORT"}; pattern-match by class.
 */
public sealed interface TrailEvent permits TrailEvent.Tile, TrailEvent.Transport
{
    long msSinceStart();
    String kind();
    WorldPoint tile();

    record Tile(long msSinceStart, WorldPoint tile) implements TrailEvent
    {
        @Override public String kind() { return "TILE"; }
    }

    /** A region-transition menu click. Carries the full metadata so the
     *  walker can reproduce the click without reloading the original
     *  scene (the {@code targetId} alone is enough for the resolver, but
     *  we keep the rest for diagnostics and future fallbacks). */
    record Transport(
        long msSinceStart,
        WorldPoint tile,
        String option,
        String target,
        int targetId,
        String targetKind,
        int actionId,
        int param0,
        int param1,
        List<String> menuRowsAtClick) implements TrailEvent
    {
        @Override public String kind() { return "TRANSPORT"; }
    }
}
```

- [ ] **Step 4: Re-run test**

Run: `./gradlew :runelite-client:test --tests "net.runelite.client.plugins.recorder.trail.TrailEventTest" -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/plugins/recorder/trail/TrailEvent.java \
        runelite-client/src/test/java/net/runelite/client/plugins/recorder/trail/TrailEventTest.java
git commit -m "trail: add TrailEvent (Tile + Transport sealed records)"
```

### Task 3: Trail container

**Files:**
- Create: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/trail/Trail.java`
- Test: `runelite-client/src/test/java/net/runelite/client/plugins/recorder/trail/TrailTest.java`

- [ ] **Step 1: Write the failing test**

```java
// runelite-client/src/test/java/net/runelite/client/plugins/recorder/trail/TrailTest.java
package net.runelite.client.plugins.recorder.trail;

import java.util.List;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.*;

public class TrailTest
{
    @Test
    public void preservesNameRecordedAtAndEvents()
    {
        TrailEvent a = new TrailEvent.Tile(0L,   new WorldPoint(3208, 3220, 2));
        TrailEvent b = new TrailEvent.Tile(600L, new WorldPoint(3208, 3221, 2));
        Trail t = new Trail("lumby-bank-to-pen", 1714247000000L, List.of(a, b));
        assertEquals("lumby-bank-to-pen", t.name());
        assertEquals(1714247000000L, t.recordedAt());
        assertEquals(2, t.events().size());
        assertSame(a, t.events().get(0));
    }

    @Test
    public void eventsAreUnmodifiable()
    {
        Trail t = new Trail("x", 0L, List.of(new TrailEvent.Tile(0L, new WorldPoint(1, 2, 0))));
        try { t.events().add(new TrailEvent.Tile(1L, new WorldPoint(1, 2, 0))); }
        catch (UnsupportedOperationException ok) { return; }
        fail("events list must be unmodifiable");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :runelite-client:test --tests "net.runelite.client.plugins.recorder.trail.TrailTest" -q`
Expected: FAIL — `Trail` doesn't exist yet.

- [ ] **Step 3: Implement Trail**

```java
// runelite-client/src/main/java/net/runelite/client/plugins/recorder/trail/Trail.java
package net.runelite.client.plugins.recorder.trail;

import java.util.Collections;
import java.util.List;

/** A single recorded trail. Immutable: name, recording timestamp (epoch
 *  millis), and an unmodifiable list of {@link TrailEvent}s ordered by
 *  {@link TrailEvent#msSinceStart()} ascending.
 *
 *  <p>The schema mirrors the on-disk JSON written by {@link TrailIO} 1:1.
 *  When loaded from disk, {@code recordedAt} is the epoch ms baked into
 *  the file; when constructed live by {@link TrailRecorder}, it is
 *  {@code System.currentTimeMillis()} at recording start. */
public final class Trail
{
    private final String name;
    private final long recordedAt;
    private final List<TrailEvent> events;

    public Trail(String name, long recordedAt, List<TrailEvent> events)
    {
        if (name == null || name.isBlank())
            throw new IllegalArgumentException("Trail name blank");
        if (events == null) throw new IllegalArgumentException("events null");
        this.name = name;
        this.recordedAt = recordedAt;
        this.events = Collections.unmodifiableList(List.copyOf(events));
    }

    public String name() { return name; }
    public long recordedAt() { return recordedAt; }
    public List<TrailEvent> events() { return events; }
}
```

- [ ] **Step 4: Re-run test**

Run: `./gradlew :runelite-client:test --tests "net.runelite.client.plugins.recorder.trail.TrailTest" -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/plugins/recorder/trail/Trail.java \
        runelite-client/src/test/java/net/runelite/client/plugins/recorder/trail/TrailTest.java
git commit -m "trail: add Trail container (name + timestamp + immutable events)"
```

### Task 4: TrailIO Gson round-trip

**Files:**
- Create: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/trail/TrailIO.java`
- Test: `runelite-client/src/test/java/net/runelite/client/plugins/recorder/trail/TrailIOTest.java`

- [ ] **Step 1: Write the failing test**

```java
// runelite-client/src/test/java/net/runelite/client/plugins/recorder/trail/TrailIOTest.java
package net.runelite.client.plugins.recorder.trail;

import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.*;

public class TrailIOTest
{
    @Test
    public void roundTripPreservesAllFields()
    {
        Trail original = new Trail("lumby-bank-to-pen", 1714247000000L, List.of(
            new TrailEvent.Tile(0L,   new WorldPoint(3208, 3220, 2)),
            new TrailEvent.Tile(600L, new WorldPoint(3208, 3221, 2)),
            new TrailEvent.Transport(12_000L, new WorldPoint(3205, 3229, 2),
                "Climb-down", "Staircase", 16671, "GameObject",
                3, 53, 14,
                List.of("Climb-down Staircase", "Cancel", "Examine Staircase")),
            new TrailEvent.Tile(12_600L, new WorldPoint(3205, 3229, 1))
        ));

        StringWriter sw = new StringWriter();
        TrailIO.write(original, sw);
        String json = sw.toString();
        assertTrue("JSON missing version", json.contains("\"version\""));
        assertTrue("JSON missing transport metadata", json.contains("\"targetId\":16671"));
        assertTrue("JSON missing menuRowsAtClick",
            json.contains("Climb-down Staircase"));

        Trail roundTripped = TrailIO.read(new StringReader(json));
        assertEquals("lumby-bank-to-pen", roundTripped.name());
        assertEquals(1714247000000L, roundTripped.recordedAt());
        assertEquals(4, roundTripped.events().size());
        TrailEvent t0 = roundTripped.events().get(0);
        assertTrue(t0 instanceof TrailEvent.Tile);
        assertEquals(new WorldPoint(3208, 3220, 2), t0.tile());
        TrailEvent t2 = roundTripped.events().get(2);
        assertTrue(t2 instanceof TrailEvent.Transport);
        TrailEvent.Transport tr = (TrailEvent.Transport) t2;
        assertEquals("Climb-down", tr.option());
        assertEquals(16671, tr.targetId());
        assertEquals(53, tr.param0());
        assertEquals(14, tr.param1());
        assertEquals(List.of("Climb-down Staircase", "Cancel", "Examine Staircase"),
            tr.menuRowsAtClick());
    }

    @Test
    public void readMissingFieldThrows()
    {
        // Older / hand-edited file missing 'name' should fail loudly so we
        // don't load a half-broken trail and confuse the planner.
        try
        {
            TrailIO.read(new StringReader("{\"version\":1,\"events\":[]}"));
            fail("expected IllegalArgumentException for missing name");
        }
        catch (IllegalArgumentException ok) { /* expected */ }
    }

    @Test
    public void writeAndReadFile() throws Exception
    {
        Path tmp = Files.createTempFile("trail-iotest-", ".json");
        try
        {
            Trail t = new Trail("tiny", 0L, List.of(
                new TrailEvent.Tile(0L, new WorldPoint(3, 2, 1))));
            TrailIO.writeFile(t, tmp);
            Trail back = TrailIO.readFile(tmp);
            assertEquals("tiny", back.name());
            assertEquals(1, back.events().size());
        }
        finally { Files.deleteIfExists(tmp); }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :runelite-client:test --tests "net.runelite.client.plugins.recorder.trail.TrailIOTest" -q`
Expected: FAIL — `TrailIO` doesn't exist yet.

- [ ] **Step 3: Implement TrailIO**

```java
// runelite-client/src/main/java/net/runelite/client/plugins/recorder/trail/TrailIO.java
package net.runelite.client.plugins.recorder.trail;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.runelite.api.coords.WorldPoint;

/** JSON serialisation for {@link Trail}. Schema:
 *
 *  <pre>{@code
 *  {
 *    "version": 1,
 *    "name": "lumby-bank-to-pen",
 *    "recordedAt": 1714247000000,
 *    "events": [
 *      {"t":"TILE","ms":0,"x":3208,"y":3220,"p":2},
 *      {"t":"TRANSPORT","ms":12000,"x":3205,"y":3229,"p":2,
 *       "option":"Climb-down","target":"Staircase","targetId":16671,
 *       "targetKind":"GameObject","actionId":3,"param0":53,"param1":14,
 *       "menuRowsAtClick":["Climb-down Staircase","Cancel"]}
 *    ]
 *  }
 *  }</pre>
 *
 *  <p>Field-by-field hand-marshal rather than Gson reflection because
 *  sealed-interface unions don't round-trip cleanly through Gson without
 *  a TypeAdapterFactory; the schema is small and stable enough that an
 *  if-tree is the simpler bet. */
public final class TrailIO
{
    private static final int CURRENT_VERSION = 1;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Gson GSON_PRETTY = new GsonBuilder()
        .setPrettyPrinting().disableHtmlEscaping().create();

    private TrailIO() {}

    public static void write(Trail trail, Writer out)
    {
        try { GSON_PRETTY.toJson(toJson(trail), out); }
        catch (Throwable t) { throw new UncheckedIOException(new IOException(t)); }
    }

    public static String writeToString(Trail trail)
    {
        StringWriter sw = new StringWriter();
        write(trail, sw);
        return sw.toString();
    }

    public static void writeFile(Trail trail, Path file) throws IOException
    {
        Files.createDirectories(file.getParent());
        try (Writer w = Files.newBufferedWriter(file)) { write(trail, w); }
    }

    public static Trail read(Reader in)
    {
        JsonElement root = GSON.fromJson(in, JsonElement.class);
        if (root == null || !root.isJsonObject())
            throw new IllegalArgumentException("Trail JSON not an object");
        return fromJson(root.getAsJsonObject());
    }

    public static Trail readString(String s) { return read(new StringReader(s)); }

    public static Trail readFile(Path file) throws IOException
    {
        try (Reader r = Files.newBufferedReader(file)) { return read(r); }
    }

    // ──────── marshalling helpers ────────

    private static JsonObject toJson(Trail trail)
    {
        JsonObject root = new JsonObject();
        root.addProperty("version", CURRENT_VERSION);
        root.addProperty("name", trail.name());
        root.addProperty("recordedAt", trail.recordedAt());
        JsonArray events = new JsonArray();
        for (TrailEvent e : trail.events()) events.add(eventToJson(e));
        root.add("events", events);
        return root;
    }

    private static JsonObject eventToJson(TrailEvent e)
    {
        JsonObject o = new JsonObject();
        o.addProperty("t", e.kind());
        o.addProperty("ms", e.msSinceStart());
        WorldPoint tile = e.tile();
        o.addProperty("x", tile.getX());
        o.addProperty("y", tile.getY());
        o.addProperty("p", tile.getPlane());
        if (e instanceof TrailEvent.Transport tr)
        {
            o.addProperty("option", tr.option());
            o.addProperty("target", tr.target());
            o.addProperty("targetId", tr.targetId());
            o.addProperty("targetKind", tr.targetKind());
            o.addProperty("actionId", tr.actionId());
            o.addProperty("param0", tr.param0());
            o.addProperty("param1", tr.param1());
            JsonArray rows = new JsonArray();
            for (String r : tr.menuRowsAtClick()) rows.add(r);
            o.add("menuRowsAtClick", rows);
        }
        return o;
    }

    private static Trail fromJson(JsonObject root)
    {
        if (!root.has("name"))
            throw new IllegalArgumentException("Trail missing 'name'");
        String name = root.get("name").getAsString();
        long recordedAt = root.has("recordedAt") ? root.get("recordedAt").getAsLong() : 0L;
        List<TrailEvent> events = new ArrayList<>();
        if (root.has("events") && root.get("events").isJsonArray())
        {
            for (JsonElement el : root.getAsJsonArray("events"))
            {
                events.add(eventFromJson(el.getAsJsonObject()));
            }
        }
        return new Trail(name, recordedAt, Collections.unmodifiableList(events));
    }

    private static TrailEvent eventFromJson(JsonObject o)
    {
        String t = o.get("t").getAsString();
        long ms = o.get("ms").getAsLong();
        WorldPoint tile = new WorldPoint(
            o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("p").getAsInt());
        if ("TILE".equals(t)) return new TrailEvent.Tile(ms, tile);
        if ("TRANSPORT".equals(t))
        {
            List<String> rows = new ArrayList<>();
            if (o.has("menuRowsAtClick") && o.get("menuRowsAtClick").isJsonArray())
            {
                for (JsonElement el : o.getAsJsonArray("menuRowsAtClick"))
                {
                    rows.add(el.getAsString());
                }
            }
            return new TrailEvent.Transport(ms, tile,
                o.has("option")     ? o.get("option").getAsString()     : "",
                o.has("target")     ? o.get("target").getAsString()     : "",
                o.has("targetId")   ? o.get("targetId").getAsInt()      : 0,
                o.has("targetKind") ? o.get("targetKind").getAsString() : "",
                o.has("actionId")   ? o.get("actionId").getAsInt()      : 0,
                o.has("param0")     ? o.get("param0").getAsInt()        : 0,
                o.has("param1")     ? o.get("param1").getAsInt()        : 0,
                Collections.unmodifiableList(rows));
        }
        throw new IllegalArgumentException("Unknown event type: " + t);
    }
}
```

- [ ] **Step 4: Re-run test**

Run: `./gradlew :runelite-client:test --tests "net.runelite.client.plugins.recorder.trail.TrailIOTest" -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/plugins/recorder/trail/TrailIO.java \
        runelite-client/src/test/java/net/runelite/client/plugins/recorder/trail/TrailIOTest.java
git commit -m "trail: add TrailIO (Gson read/write with stable schema)"
```

---

## Phase 2 — Trail recorder

`TrailRecorder` subscribes to `GameTick` (sample player tile, dedupe) and `MenuOptionClicked` (record transport when verb is whitelisted). Lifecycle: `start(name)` clears the buffer and stamps recordedAt; `stopAndSave()` writes the trail file.

### Task 5: Transport verb whitelist + recorder skeleton

**Files:**
- Create: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/trail/TrailRecorder.java`
- Test: `runelite-client/src/test/java/net/runelite/client/plugins/recorder/trail/TrailRecorderWhitelistTest.java`

- [ ] **Step 1: Write the failing test**

```java
// runelite-client/src/test/java/net/runelite/client/plugins/recorder/trail/TrailRecorderWhitelistTest.java
package net.runelite.client.plugins.recorder.trail;

import org.junit.Test;
import static org.junit.Assert.*;

public class TrailRecorderWhitelistTest
{
    @Test
    public void whitelistContainsRegionTransitionVerbs()
    {
        // Spec list: Open, Close, Climb-up, Climb-down, Cross, Pass,
        // Squeeze-through, Jump, Climb, Climb-over, Enter, Exit,
        // Pay-toll, Squeeze-past.
        assertTrue(TrailRecorder.isTransportVerb("Open"));
        assertTrue(TrailRecorder.isTransportVerb("Close"));
        assertTrue(TrailRecorder.isTransportVerb("Climb-up"));
        assertTrue(TrailRecorder.isTransportVerb("Climb-down"));
        assertTrue(TrailRecorder.isTransportVerb("Cross"));
        assertTrue(TrailRecorder.isTransportVerb("Pass"));
        assertTrue(TrailRecorder.isTransportVerb("Squeeze-through"));
        assertTrue(TrailRecorder.isTransportVerb("Jump"));
        assertTrue(TrailRecorder.isTransportVerb("Climb"));
        assertTrue(TrailRecorder.isTransportVerb("Climb-over"));
        assertTrue(TrailRecorder.isTransportVerb("Enter"));
        assertTrue(TrailRecorder.isTransportVerb("Exit"));
        assertTrue(TrailRecorder.isTransportVerb("Pay-toll"));
        assertTrue(TrailRecorder.isTransportVerb("Squeeze-past"));
    }

    @Test
    public void whitelistRejectsCommonNonTransportVerbs()
    {
        assertFalse(TrailRecorder.isTransportVerb("Walk here"));
        assertFalse(TrailRecorder.isTransportVerb("Talk-to"));
        assertFalse(TrailRecorder.isTransportVerb("Attack"));
        assertFalse(TrailRecorder.isTransportVerb("Take"));
        assertFalse(TrailRecorder.isTransportVerb("Trade"));
        assertFalse(TrailRecorder.isTransportVerb("Examine"));
        assertFalse(TrailRecorder.isTransportVerb("Cancel"));
        assertFalse(TrailRecorder.isTransportVerb(""));
        assertFalse(TrailRecorder.isTransportVerb(null));
    }

    @Test
    public void whitelistIsCaseInsensitive()
    {
        assertTrue(TrailRecorder.isTransportVerb("climb-up"));
        assertTrue(TrailRecorder.isTransportVerb("OPEN"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :runelite-client:test --tests "net.runelite.client.plugins.recorder.trail.TrailRecorderWhitelistTest" -q`
Expected: FAIL — `TrailRecorder` class doesn't exist yet.

- [ ] **Step 3: Implement skeleton with whitelist**

```java
// runelite-client/src/main/java/net/runelite/client/plugins/recorder/trail/TrailRecorder.java
package net.runelite.client.plugins.recorder.trail;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Live capture of a {@link Trail}: subscribes to the RuneLite event bus
 * (in production via {@link net.runelite.client.eventbus.EventBus}) and
 * records {@link TrailEvent.Tile} entries on each {@code GameTick} where
 * the player tile changed, plus {@link TrailEvent.Transport} entries on
 * each {@code MenuOptionClicked} whose verb is in the
 * {@link #TRANSPORT_VERBS} whitelist.
 *
 * <p>Wire-up happens in {@code RecorderPlugin#startUp()} via
 * {@code eventBus.register(trailRecorder)}. The recorder is dormant until
 * {@link #start(String)} is called from the side-panel button.
 *
 * <p>Static {@link #isTransportVerb(String)} kept package-public so other
 * components (graph builder, walker debug logging) can use the same rule
 * without a circular dependency.
 */
public final class TrailRecorder
{
    /** The verbs that mark a region-transition click. Mirror of the spec. */
    public static final Set<String> TRANSPORT_VERBS = Set.of(
        "Open", "Close",
        "Climb-up", "Climb-down", "Climb", "Climb-over",
        "Cross", "Pass",
        "Squeeze-through", "Squeeze-past",
        "Jump",
        "Enter", "Exit",
        "Pay-toll");

    private static final Set<String> TRANSPORT_VERBS_LOWER = Set.copyOf(
        TRANSPORT_VERBS.stream().map(s -> s.toLowerCase()).toList());

    public static boolean isTransportVerb(String option)
    {
        if (option == null) return false;
        return TRANSPORT_VERBS_LOWER.contains(option.toLowerCase());
    }

    /** Recording session — null when idle. */
    private final AtomicReference<Session> session = new AtomicReference<>(null);

    /** Returns true if a recording session is active. */
    public boolean isRecording() { return session.get() != null; }

    /** The name the active recording will save under, or null if idle. */
    public String currentName()
    {
        Session s = session.get();
        return s == null ? null : s.name;
    }

    static final class Session
    {
        final String name;
        final long startMs;
        Session(String name, long startMs) { this.name = name; this.startMs = startMs; }
    }
}
```

- [ ] **Step 4: Re-run test**

Run: `./gradlew :runelite-client:test --tests "net.runelite.client.plugins.recorder.trail.TrailRecorderWhitelistTest" -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/plugins/recorder/trail/TrailRecorder.java \
        runelite-client/src/test/java/net/runelite/client/plugins/recorder/trail/TrailRecorderWhitelistTest.java
git commit -m "trail: add TrailRecorder skeleton with transport-verb whitelist"
```

### Task 6: Tile event dedupe + GameTick capture

**Files:**
- Modify: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/trail/TrailRecorder.java`
- Test: `runelite-client/src/test/java/net/runelite/client/plugins/recorder/trail/TrailRecorderTickTest.java`

- [ ] **Step 1: Write the failing test**

```java
// runelite-client/src/test/java/net/runelite/client/plugins/recorder/trail/TrailRecorderTickTest.java
package net.runelite.client.plugins.recorder.trail;

import java.util.List;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.*;

public class TrailRecorderTickTest
{
    @Test
    public void tickCapturesTileWhenChanged()
    {
        TrailRecorder rec = new TrailRecorder();
        rec.start("test");
        rec.recordTile(0L,    new WorldPoint(100, 100, 0));
        rec.recordTile(600L,  new WorldPoint(100, 101, 0));
        rec.recordTile(1200L, new WorldPoint(100, 101, 0)); // unchanged → drop
        rec.recordTile(1800L, new WorldPoint(101, 101, 0));
        Trail t = rec.stopAndBuild();
        assertEquals(3, t.events().size());
        assertEquals(new WorldPoint(100, 100, 0), t.events().get(0).tile());
        assertEquals(new WorldPoint(100, 101, 0), t.events().get(1).tile());
        assertEquals(new WorldPoint(101, 101, 0), t.events().get(2).tile());
    }

    @Test
    public void recordingNotStartedDropsAllEvents()
    {
        TrailRecorder rec = new TrailRecorder();
        rec.recordTile(0L, new WorldPoint(1, 1, 0));     // dropped — not started
        rec.start("a");
        rec.recordTile(100L, new WorldPoint(2, 2, 0));   // kept
        Trail t = rec.stopAndBuild();
        assertEquals(1, t.events().size());
        assertEquals(new WorldPoint(2, 2, 0), t.events().get(0).tile());
    }

    @Test
    public void msIsRelativeToStart()
    {
        TrailRecorder rec = new TrailRecorder();
        rec.startAt("rel", 10_000L);
        rec.recordTileAtAbsoluteMs(10_000L, new WorldPoint(0, 0, 0));
        rec.recordTileAtAbsoluteMs(10_600L, new WorldPoint(0, 1, 0));
        Trail t = rec.stopAndBuild();
        assertEquals(0L,   t.events().get(0).msSinceStart());
        assertEquals(600L, t.events().get(1).msSinceStart());
    }

    @Test
    public void stopWhileNotRecordingThrows()
    {
        TrailRecorder rec = new TrailRecorder();
        try { rec.stopAndBuild(); fail(); }
        catch (IllegalStateException ok) { /* expected */ }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :runelite-client:test --tests "net.runelite.client.plugins.recorder.trail.TrailRecorderTickTest" -q`
Expected: FAIL — `start`, `recordTile`, `stopAndBuild` don't exist yet.

- [ ] **Step 3: Add session lifecycle + tile capture**

Replace the `Session` inner class and append after the existing class body:

```java
    // ──────────────── public API ────────────────

    public synchronized void start(String name)
    {
        startAt(name, System.currentTimeMillis());
    }

    /** Test-friendly variant: pin the start instant. Production callers
     *  always go through {@link #start(String)}. */
    public synchronized void startAt(String name, long startMs)
    {
        if (name == null || name.isBlank())
            throw new IllegalArgumentException("trail name blank");
        if (session.get() != null)
            throw new IllegalStateException("already recording: "
                + session.get().name);
        session.set(new Session(name, startMs));
    }

    public synchronized Trail stopAndBuild()
    {
        Session s = session.get();
        if (s == null) throw new IllegalStateException("not recording");
        Trail t = new Trail(s.name, s.startMs, java.util.List.copyOf(s.events));
        session.set(null);
        return t;
    }

    public synchronized void cancel()
    {
        session.set(null);
    }

    // ──────── tile capture (called by event-bus subscriber) ────────

    /** Append a {@link TrailEvent.Tile} if recording and the tile differs
     *  from the previous one. {@code msSinceStart} is the ms offset from
     *  the recording start; pass 0 for the first tile.
     *
     *  <p>Thread-safety: synchronized on this recorder. The plugin's event
     *  subscribers fire on the client thread, so concurrency is rare —
     *  this is belt + suspenders. */
    public synchronized void recordTile(long msSinceStart, net.runelite.api.coords.WorldPoint tile)
    {
        Session s = session.get();
        if (s == null || tile == null) return;
        TrailEvent last = s.events.isEmpty() ? null : s.events.get(s.events.size() - 1);
        if (last instanceof TrailEvent.Tile lt && lt.tile().equals(tile)) return;
        s.events.add(new TrailEvent.Tile(msSinceStart, tile));
    }

    /** Wall-clock convenience: caller passes the absolute epoch ms; the
     *  recorder converts to {@code msSinceStart}. Used by the live
     *  {@code @Subscribe} handler. */
    public synchronized void recordTileAtAbsoluteMs(long absoluteMs, net.runelite.api.coords.WorldPoint tile)
    {
        Session s = session.get();
        if (s == null) return;
        recordTile(absoluteMs - s.startMs, tile);
    }
```

Update the `Session` inner class to carry an event buffer:

```java
    static final class Session
    {
        final String name;
        final long startMs;
        final java.util.List<TrailEvent> events = new java.util.ArrayList<>();
        Session(String name, long startMs) { this.name = name; this.startMs = startMs; }
    }
```

- [ ] **Step 4: Re-run test**

Run: `./gradlew :runelite-client:test --tests "net.runelite.client.plugins.recorder.trail.TrailRecorderTickTest" -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/plugins/recorder/trail/TrailRecorder.java \
        runelite-client/src/test/java/net/runelite/client/plugins/recorder/trail/TrailRecorderTickTest.java
git commit -m "trail: TrailRecorder records tiles with dedupe + ms-since-start"
```

### Task 7: Transport event capture + filter

**Files:**
- Modify: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/trail/TrailRecorder.java`
- Test: `runelite-client/src/test/java/net/runelite/client/plugins/recorder/trail/TrailRecorderMenuTest.java`

- [ ] **Step 1: Write the failing test**

```java
// runelite-client/src/test/java/net/runelite/client/plugins/recorder/trail/TrailRecorderMenuTest.java
package net.runelite.client.plugins.recorder.trail;

import java.util.List;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.*;

public class TrailRecorderMenuTest
{
    @Test
    public void recordsTransportClickWithFullMetadata()
    {
        TrailRecorder rec = new TrailRecorder();
        rec.start("trail-m");
        rec.recordTransport(500L,
            new WorldPoint(3205, 3229, 2),
            "Climb-down", "Staircase", 16671, "GameObject",
            3, 53, 14,
            List.of("Climb-down Staircase", "Cancel", "Examine Staircase"));
        Trail t = rec.stopAndBuild();
        assertEquals(1, t.events().size());
        assertTrue(t.events().get(0) instanceof TrailEvent.Transport);
        TrailEvent.Transport tr = (TrailEvent.Transport) t.events().get(0);
        assertEquals("Climb-down", tr.option());
        assertEquals(16671, tr.targetId());
        assertEquals(53, tr.param0());
        assertEquals(14, tr.param1());
        assertEquals(List.of("Climb-down Staircase", "Cancel", "Examine Staircase"),
            tr.menuRowsAtClick());
    }

    @Test
    public void filtersNonTransportVerbs()
    {
        TrailRecorder rec = new TrailRecorder();
        rec.start("trail-m");
        boolean kept = rec.recordTransportIfWhitelisted(
            500L, new WorldPoint(0, 0, 0),
            "Talk-to", "Bob", 0, "NPC", 0, 0, 0, List.of());
        assertFalse("non-whitelisted Talk-to should be filtered", kept);
        Trail t = rec.stopAndBuild();
        assertEquals(0, t.events().size());
    }

    @Test
    public void keepsWhitelistedVerb()
    {
        TrailRecorder rec = new TrailRecorder();
        rec.start("trail-m");
        boolean kept = rec.recordTransportIfWhitelisted(
            100L, new WorldPoint(1, 1, 0),
            "Open", "Gate", 1234, "WallObject", 0, 0, 0, List.of("Open Gate"));
        assertTrue(kept);
        Trail t = rec.stopAndBuild();
        assertEquals(1, t.events().size());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :runelite-client:test --tests "net.runelite.client.plugins.recorder.trail.TrailRecorderMenuTest" -q`
Expected: FAIL — `recordTransport` and `recordTransportIfWhitelisted` don't exist.

- [ ] **Step 3: Implement transport capture**

Append to `TrailRecorder.java` (inside the class body):

```java
    /** Append a {@link TrailEvent.Transport} unconditionally. The caller
     *  has already done verb filtering. */
    public synchronized void recordTransport(long msSinceStart,
        net.runelite.api.coords.WorldPoint tile,
        String option, String target, int targetId, String targetKind,
        int actionId, int param0, int param1, java.util.List<String> menuRowsAtClick)
    {
        Session s = session.get();
        if (s == null) return;
        s.events.add(new TrailEvent.Transport(msSinceStart, tile,
            option == null ? "" : option,
            target == null ? "" : target,
            targetId, targetKind == null ? "" : targetKind,
            actionId, param0, param1,
            menuRowsAtClick == null ? java.util.List.of()
                : java.util.List.copyOf(menuRowsAtClick)));
    }

    /** Append a transport iff the verb is in the whitelist. Returns true
     *  if it was kept, false if filtered. */
    public synchronized boolean recordTransportIfWhitelisted(long msSinceStart,
        net.runelite.api.coords.WorldPoint tile,
        String option, String target, int targetId, String targetKind,
        int actionId, int param0, int param1, java.util.List<String> menuRowsAtClick)
    {
        if (!isTransportVerb(option)) return false;
        recordTransport(msSinceStart, tile, option, target, targetId, targetKind,
            actionId, param0, param1, menuRowsAtClick);
        return true;
    }

    public synchronized boolean recordTransportAtAbsoluteMsIfWhitelisted(long absoluteMs,
        net.runelite.api.coords.WorldPoint tile,
        String option, String target, int targetId, String targetKind,
        int actionId, int param0, int param1, java.util.List<String> menuRowsAtClick)
    {
        Session s = session.get();
        if (s == null) return false;
        return recordTransportIfWhitelisted(absoluteMs - s.startMs, tile,
            option, target, targetId, targetKind, actionId, param0, param1,
            menuRowsAtClick);
    }
```

- [ ] **Step 4: Re-run test**

Run: `./gradlew :runelite-client:test --tests "net.runelite.client.plugins.recorder.trail.TrailRecorderMenuTest" -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/plugins/recorder/trail/TrailRecorder.java \
        runelite-client/src/test/java/net/runelite/client/plugins/recorder/trail/TrailRecorderMenuTest.java
git commit -m "trail: TrailRecorder records whitelisted transports with full menu metadata"
```

### Task 8: Live event-bus subscription

**Files:**
- Modify: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/trail/TrailRecorder.java`

- [ ] **Step 1: Add @Subscribe handlers**

Add these imports near the top of `TrailRecorder.java`:

```java
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.MenuEntry;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.client.eventbus.Subscribe;
```

Add `@Slf4j` and `@RequiredArgsConstructor(onConstructor_={@javax.inject.Inject})` annotations to the class. Add a `Client client` field and the subscriber methods inside the class:

```java
    private final Client client;

    @Subscribe
    public void onGameTick(GameTick e)
    {
        if (!isRecording()) return;
        var local = client.getLocalPlayer();
        if (local == null) return;
        var here = local.getWorldLocation();
        if (here == null) return;
        recordTileAtAbsoluteMs(System.currentTimeMillis(), here);
    }

    @Subscribe
    public void onMenuOptionClicked(MenuOptionClicked e)
    {
        if (!isRecording()) return;
        MenuEntry entry = e.getMenuEntry();
        if (entry == null) return;
        String option = entry.getOption();
        if (!isTransportVerb(option)) return;
        // Resolve the click's world tile from param0/param1 (scene coords)
        // → world via the player's current scene base. Falls back to the
        // player's tile if the scene math fails (rare; means the click
        // wasn't on a tile-anchored object).
        var local = client.getLocalPlayer();
        if (local == null || local.getWorldLocation() == null) return;
        net.runelite.api.coords.WorldPoint tile = resolveClickTile(entry, local);

        // Snapshot the current menu rows so we can faithfully reproduce
        // the click context offline. The MenuOptionClicked event fires
        // BEFORE the engine consumes the click, so the menu is still in
        // memory. getMenuEntries() includes 'Cancel' at index 0.
        java.util.List<String> rows = new java.util.ArrayList<>();
        try
        {
            MenuEntry[] entries = client.getMenu().getMenuEntries();
            for (MenuEntry me : entries)
            {
                String opt = me.getOption() == null ? "" : me.getOption();
                String tgt = me.getTarget() == null ? "" : me.getTarget();
                rows.add((opt + " " + tgt).trim());
            }
        }
        catch (Throwable t) { log.debug("trail: failed to snapshot menu", t); }

        recordTransportAtAbsoluteMsIfWhitelisted(
            System.currentTimeMillis(),
            tile,
            option,
            entry.getTarget() == null ? "" : entry.getTarget(),
            entry.getIdentifier(),
            entry.getType() == null ? "" : entry.getType().toString(),
            entry.getType() == null ? 0 : entry.getType().getId(),
            entry.getParam0(),
            entry.getParam1(),
            rows);
    }

    private net.runelite.api.coords.WorldPoint resolveClickTile(
        MenuEntry entry, net.runelite.api.Player local)
    {
        try
        {
            // For game-object / wall / decorative menu entries the engine
            // packs scene coords into param0/param1. Translate to world via
            // the player's current scene base.
            int sx = entry.getParam0();
            int sy = entry.getParam1();
            net.runelite.api.coords.LocalPoint lp =
                net.runelite.api.coords.LocalPoint.fromScene(
                    sx, sy, client.getTopLevelWorldView());
            net.runelite.api.coords.WorldPoint wp =
                net.runelite.api.coords.WorldPoint.fromLocal(client, lp);
            if (wp != null) return wp;
        }
        catch (Throwable ignored) { /* fall through */ }
        return local.getWorldLocation();
    }
```

Remove the duplicate `Client` reference from the standalone-constructor variant; use Lombok's `@RequiredArgsConstructor` so `RecorderPlugin` can `eventBus.register(new TrailRecorder(client))`. Keep a no-arg constructor for tests by adding above the field:

```java
    public TrailRecorder() { this(null); }
```

- [ ] **Step 2: Run all trail tests**

Run: `./gradlew :runelite-client:test --tests "net.runelite.client.plugins.recorder.trail.*" -q`
Expected: PASS — all unit tests still pass; the new subscriber code only fires when `client != null` and is exercised by the smoke test in Task 25, not unit tests.

- [ ] **Step 3: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/plugins/recorder/trail/TrailRecorder.java
git commit -m "trail: TrailRecorder subscribes to GameTick + MenuOptionClicked"
```

---

## Phase 3 — Trail registry

### Task 9: TrailRegistry directory loader

**Files:**
- Create: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/trail/TrailRegistry.java`
- Test: `runelite-client/src/test/java/net/runelite/client/plugins/recorder/trail/TrailRegistryTest.java`

- [ ] **Step 1: Write the failing test**

```java
// runelite-client/src/test/java/net/runelite/client/plugins/recorder/trail/TrailRegistryTest.java
package net.runelite.client.plugins.recorder.trail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.*;

public class TrailRegistryTest
{
    @Test
    public void loadsAllJsonFilesInDirectory() throws IOException
    {
        Path dir = Files.createTempDirectory("trails-");
        try
        {
            TrailIO.writeFile(new Trail("a", 1L, List.of(
                new TrailEvent.Tile(0L, new WorldPoint(1,1,0)))), dir.resolve("a.json"));
            TrailIO.writeFile(new Trail("b", 2L, List.of(
                new TrailEvent.Tile(0L, new WorldPoint(2,2,0)))), dir.resolve("b.json"));
            Files.writeString(dir.resolve("note.txt"), "ignore me");

            TrailRegistry reg = new TrailRegistry(dir);
            reg.load();
            assertEquals(2, reg.all().size());
            assertNotNull(reg.byName("a"));
            assertNotNull(reg.byName("b"));
            assertNull(reg.byName("missing"));
        }
        finally { deleteRecursive(dir); }
    }

    @Test
    public void missingDirectoryYieldsEmptyRegistry() throws IOException
    {
        Path dir = Files.createTempDirectory("trails-").resolve("nope");
        TrailRegistry reg = new TrailRegistry(dir);
        reg.load();
        assertEquals(0, reg.all().size());
    }

    @Test
    public void saveWritesUnderRegistryDir() throws IOException
    {
        Path dir = Files.createTempDirectory("trails-");
        try
        {
            TrailRegistry reg = new TrailRegistry(dir);
            Trail t = new Trail("save-test", 0L, List.of(
                new TrailEvent.Tile(0L, new WorldPoint(3,3,0))));
            reg.save(t);
            assertTrue(Files.exists(dir.resolve("save-test.json")));
            reg.load();
            assertNotNull(reg.byName("save-test"));
        }
        finally { deleteRecursive(dir); }
    }

    private static void deleteRecursive(Path p) throws IOException
    {
        if (!Files.exists(p)) return;
        try (var s = Files.walk(p))
        {
            s.sorted(java.util.Comparator.reverseOrder())
             .forEach(f -> { try { Files.deleteIfExists(f); } catch (IOException ignored){} });
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :runelite-client:test --tests "net.runelite.client.plugins.recorder.trail.TrailRegistryTest" -q`
Expected: FAIL — `TrailRegistry` doesn't exist.

- [ ] **Step 3: Implement TrailRegistry**

```java
// runelite-client/src/main/java/net/runelite/client/plugins/recorder/trail/TrailRegistry.java
package net.runelite.client.plugins.recorder.trail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/** Loads and stores {@link Trail}s under a directory (default
 *  {@code ~/.runelite/recorder/trails/}). Each trail lives in its own
 *  {@code <name>.json} file; {@link #save(Trail)} overwrites silently
 *  (the panel UI surfaces a confirm-overwrite dialog before calling). */
@Slf4j
public final class TrailRegistry
{
    private final Path dir;
    private final Map<String, Trail> byName = new LinkedHashMap<>();

    public TrailRegistry(Path dir) { this.dir = dir; }

    public Path directory() { return dir; }

    /** Re-scan the directory, replacing the in-memory map. */
    public synchronized void load()
    {
        byName.clear();
        if (!Files.isDirectory(dir)) return;
        try (var stream = Files.list(dir))
        {
            stream
                .filter(p -> p.getFileName().toString().endsWith(".json"))
                .sorted()
                .forEach(p -> {
                    try
                    {
                        Trail t = TrailIO.readFile(p);
                        byName.put(t.name(), t);
                    }
                    catch (Throwable th)
                    {
                        log.warn("trail: failed to load {}", p, th);
                    }
                });
        }
        catch (IOException e) { log.warn("trail: list dir {} failed", dir, e); }
    }

    /** Persist a trail; subsequent {@link #load()} calls will see it. */
    public synchronized void save(Trail trail) throws IOException
    {
        Files.createDirectories(dir);
        TrailIO.writeFile(trail, dir.resolve(trail.name() + ".json"));
        byName.put(trail.name(), trail);
    }

    public synchronized Trail byName(String name) { return byName.get(name); }

    public synchronized Collection<Trail> all()
    {
        return Collections.unmodifiableCollection(new java.util.ArrayList<>(byName.values()));
    }
}
```

- [ ] **Step 4: Re-run test**

Run: `./gradlew :runelite-client:test --tests "net.runelite.client.plugins.recorder.trail.TrailRegistryTest" -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/plugins/recorder/trail/TrailRegistry.java \
        runelite-client/src/test/java/net/runelite/client/plugins/recorder/trail/TrailRegistryTest.java
git commit -m "trail: TrailRegistry loads/saves trails under a directory"
```

---

## Phase 4 — Junction graph + planner

### Task 10: Leg + TrailPath types

**Files:**
- Create: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/trail/Leg.java`
- Create: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/trail/TrailPath.java`
- Test: `runelite-client/src/test/java/net/runelite/client/plugins/recorder/trail/TrailPathTest.java`

- [ ] **Step 1: Write the failing test**

```java
// runelite-client/src/test/java/net/runelite/client/plugins/recorder/trail/TrailPathTest.java
package net.runelite.client.plugins.recorder.trail;

import java.util.List;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.*;

public class TrailPathTest
{
    @Test
    public void walkLegCarriesTileList()
    {
        Leg.Walk w = new Leg.Walk(List.of(
            new WorldPoint(1, 1, 0), new WorldPoint(1, 2, 0)));
        assertEquals(2, w.tiles().size());
        assertEquals("WALK", w.kind());
    }

    @Test
    public void transportLegCarriesVerbAndObjectMetadata()
    {
        Leg.Transport t = new Leg.Transport(
            new WorldPoint(3, 3, 0), "Climb-down", 16671, "GameObject", 53, 14);
        assertEquals("TRANSPORT", t.kind());
        assertEquals("Climb-down", t.verb());
        assertEquals(16671, t.objectId());
    }

    @Test
    public void trailPathExposesLegs()
    {
        Leg.Walk w = new Leg.Walk(List.of(new WorldPoint(0,0,0)));
        TrailPath p = new TrailPath(List.of(w));
        assertEquals(1, p.legs().size());
        assertSame(w, p.legs().get(0));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :runelite-client:test --tests "net.runelite.client.plugins.recorder.trail.TrailPathTest" -q`
Expected: FAIL — `Leg` and `TrailPath` don't exist.

- [ ] **Step 3: Implement Leg**

```java
// runelite-client/src/main/java/net/runelite/client/plugins/recorder/trail/Leg.java
package net.runelite.client.plugins.recorder.trail;

import java.util.Collections;
import java.util.List;
import net.runelite.api.coords.WorldPoint;

/** One step in a {@link TrailPath}. Either a {@link Walk} along a list of
 *  tiles, or a {@link Transport} click on a recorded object. */
public sealed interface Leg permits Leg.Walk, Leg.Transport
{
    String kind();

    record Walk(List<WorldPoint> tiles) implements Leg
    {
        public Walk(List<WorldPoint> tiles)
        {
            if (tiles == null || tiles.isEmpty())
                throw new IllegalArgumentException("walk leg has no tiles");
            this.tiles = Collections.unmodifiableList(List.copyOf(tiles));
        }
        @Override public String kind() { return "WALK"; }
    }

    record Transport(WorldPoint tile, String verb, int objectId,
                     String targetKind, int param0, int param1) implements Leg
    {
        @Override public String kind() { return "TRANSPORT"; }
    }
}
```

- [ ] **Step 4: Implement TrailPath**

```java
// runelite-client/src/main/java/net/runelite/client/plugins/recorder/trail/TrailPath.java
package net.runelite.client.plugins.recorder.trail;

import java.util.Collections;
import java.util.List;

/** Output of {@link TrailPlanner}: an ordered sequence of {@link Leg}s
 *  the {@link TrailWalker} executes. Two {@link Leg.Walk} legs never
 *  appear consecutively — the planner coalesces them into one. */
public final class TrailPath
{
    private final List<Leg> legs;

    public TrailPath(List<Leg> legs)
    {
        if (legs == null) throw new IllegalArgumentException("legs null");
        this.legs = Collections.unmodifiableList(List.copyOf(legs));
    }

    public List<Leg> legs() { return legs; }
    public int size() { return legs.size(); }
    public boolean isEmpty() { return legs.isEmpty(); }
}
```

- [ ] **Step 5: Re-run test**

Run: `./gradlew :runelite-client:test --tests "net.runelite.client.plugins.recorder.trail.TrailPathTest" -q`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/plugins/recorder/trail/Leg.java \
        runelite-client/src/main/java/net/runelite/client/plugins/recorder/trail/TrailPath.java \
        runelite-client/src/test/java/net/runelite/client/plugins/recorder/trail/TrailPathTest.java
git commit -m "trail: add Leg (sealed Walk + Transport) and TrailPath"
```

### Task 11: TrailGraph build with within-trail edges

**Files:**
- Create: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/trail/TrailGraph.java`
- Test: `runelite-client/src/test/java/net/runelite/client/plugins/recorder/trail/TrailGraphWithinTrailTest.java`

- [ ] **Step 1: Write the failing test**

```java
// runelite-client/src/test/java/net/runelite/client/plugins/recorder/trail/TrailGraphWithinTrailTest.java
package net.runelite.client.plugins.recorder.trail;

import java.util.List;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.*;

public class TrailGraphWithinTrailTest
{
    @Test
    public void consecutiveTilesGetCost1Edge()
    {
        Trail t = new Trail("t", 0L, List.of(
            new TrailEvent.Tile(0L,    new WorldPoint(100, 100, 0)),
            new TrailEvent.Tile(600L,  new WorldPoint(100, 101, 0)),
            new TrailEvent.Tile(1200L, new WorldPoint(100, 102, 0))));
        TrailGraph g = TrailGraph.build(List.of(t));
        WorldPoint a = new WorldPoint(100, 100, 0);
        WorldPoint b = new WorldPoint(100, 101, 0);
        WorldPoint c = new WorldPoint(100, 102, 0);
        assertEquals(1, g.edgeCost(a, b));
        assertEquals(1, g.edgeCost(b, c));
        // Bidirectional.
        assertEquals(1, g.edgeCost(b, a));
        // Non-adjacent in the trail (a → c) — no within-trail edge.
        assertEquals(-1, g.edgeCost(a, c));
        // Reciprocal also -1 unless a junction edge added it.
        assertTrue(g.nodes().contains(a));
        assertTrue(g.nodes().contains(b));
        assertTrue(g.nodes().contains(c));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :runelite-client:test --tests "net.runelite.client.plugins.recorder.trail.TrailGraphWithinTrailTest" -q`
Expected: FAIL — `TrailGraph` doesn't exist.

- [ ] **Step 3: Implement skeleton with within-trail walk edges**

```java
// runelite-client/src/main/java/net/runelite/client/plugins/recorder/trail/TrailGraph.java
package net.runelite.client.plugins.recorder.trail;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.coords.WorldPoint;

/** Junction graph built from N trails. See spec
 *  {@code docs/superpowers/specs/2026-04-28-trail-network-webwalker-design.md}
 *  for edge rules. Pure data; build via {@link #build(Collection)}.
 *
 *  <p>Edges are bidirectional. Edge weight is encoded as an int cost; -1
 *  signals "no edge". Transport edges carry their {@link Leg.Transport}
 *  payload so the planner can emit it in the {@link TrailPath} output. */
public final class TrailGraph
{
    private final Set<WorldPoint> nodes;
    /** {@code adj.get(a).get(b) = cost(a→b)}. Symmetric for walk + junction. */
    private final Map<WorldPoint, Map<WorldPoint, Integer>> adj;
    /** {@code transports.get(a).get(b) = transport leg payload between a and b}. */
    private final Map<WorldPoint, Map<WorldPoint, Leg.Transport>> transports;

    private TrailGraph(Set<WorldPoint> nodes,
                       Map<WorldPoint, Map<WorldPoint, Integer>> adj,
                       Map<WorldPoint, Map<WorldPoint, Leg.Transport>> transports)
    {
        this.nodes = Collections.unmodifiableSet(nodes);
        this.adj = adj;
        this.transports = transports;
    }

    public Set<WorldPoint> nodes() { return nodes; }

    /** Cost of the cheapest direct edge from {@code a} to {@code b}, or
     *  -1 if no edge exists. */
    public int edgeCost(WorldPoint a, WorldPoint b)
    {
        Map<WorldPoint, Integer> n = adj.get(a);
        if (n == null) return -1;
        Integer c = n.get(b);
        return c == null ? -1 : c;
    }

    /** All neighbours of {@code a} with their costs. Empty map if isolated. */
    public Map<WorldPoint, Integer> neighbours(WorldPoint a)
    {
        Map<WorldPoint, Integer> n = adj.get(a);
        return n == null ? Collections.emptyMap() : Collections.unmodifiableMap(n);
    }

    /** Transport-edge payload for the edge {@code a → b}, or null if the
     *  edge is a walk/junction edge. */
    public Leg.Transport transportBetween(WorldPoint a, WorldPoint b)
    {
        Map<WorldPoint, Leg.Transport> n = transports.get(a);
        return n == null ? null : n.get(b);
    }

    public static TrailGraph build(Collection<Trail> trails)
    {
        Set<WorldPoint> nodes = new HashSet<>();
        Map<WorldPoint, Map<WorldPoint, Integer>> adj = new HashMap<>();
        Map<WorldPoint, Map<WorldPoint, Leg.Transport>> trans = new HashMap<>();

        for (Trail t : trails) addWithinTrailEdges(t, nodes, adj, trans);
        addJunctionEdges(nodes, adj);
        return new TrailGraph(nodes, adj, trans);
    }

    private static void addWithinTrailEdges(Trail t, Set<WorldPoint> nodes,
        Map<WorldPoint, Map<WorldPoint, Integer>> adj,
        Map<WorldPoint, Map<WorldPoint, Leg.Transport>> trans)
    {
        List<TrailEvent> events = t.events();
        WorldPoint prevTile = null;
        TrailEvent.Transport pendingTransport = null;
        for (TrailEvent ev : events)
        {
            if (ev instanceof TrailEvent.Tile tile)
            {
                nodes.add(tile.tile());
                if (prevTile != null)
                {
                    if (pendingTransport != null)
                    {
                        Leg.Transport tr = new Leg.Transport(
                            pendingTransport.tile(),
                            pendingTransport.option(),
                            pendingTransport.targetId(),
                            pendingTransport.targetKind(),
                            pendingTransport.param0(),
                            pendingTransport.param1());
                        addEdge(adj, prevTile, tile.tile(), 1);
                        trans.computeIfAbsent(prevTile, k -> new HashMap<>())
                             .put(tile.tile(), tr);
                        trans.computeIfAbsent(tile.tile(), k -> new HashMap<>())
                             .put(prevTile, tr);
                        pendingTransport = null;
                    }
                    else
                    {
                        addEdge(adj, prevTile, tile.tile(), 1);
                    }
                }
                prevTile = tile.tile();
            }
            else if (ev instanceof TrailEvent.Transport tr)
            {
                pendingTransport = tr;
            }
        }
    }

    private static void addEdge(Map<WorldPoint, Map<WorldPoint, Integer>> adj,
                                WorldPoint a, WorldPoint b, int cost)
    {
        if (a.equals(b)) return;
        adj.computeIfAbsent(a, k -> new HashMap<>())
           .merge(b, cost, Math::min);
        adj.computeIfAbsent(b, k -> new HashMap<>())
           .merge(a, cost, Math::min);
    }

    /** Junction edges: any two nodes on the same plane within Chebyshev ≤ 1
     *  of each other get a cost-{0|1} edge. Spatial-hashed so we don't do
     *  O(N²) over all node pairs. */
    private static void addJunctionEdges(Set<WorldPoint> nodes,
                                         Map<WorldPoint, Map<WorldPoint, Integer>> adj)
    {
        // Bucket nodes by (plane, x>>4, y>>4) — 16-tile boxes; only compare
        // a node against nodes in the same bucket and the 8 neighbour
        // buckets. For 10k tiles this brings build to typical-case O(N).
        Map<Long, java.util.ArrayList<WorldPoint>> buckets = new HashMap<>();
        for (WorldPoint p : nodes)
        {
            long key = bucketKey(p.getPlane(), p.getX() >> 4, p.getY() >> 4);
            buckets.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add(p);
        }
        for (WorldPoint p : nodes)
        {
            int bx = p.getX() >> 4;
            int by = p.getY() >> 4;
            for (int dx = -1; dx <= 1; dx++)
            {
                for (int dy = -1; dy <= 1; dy++)
                {
                    long key = bucketKey(p.getPlane(), bx + dx, by + dy);
                    var list = buckets.get(key);
                    if (list == null) continue;
                    for (WorldPoint q : list)
                    {
                        if (q == p) continue;
                        if (q.getPlane() != p.getPlane()) continue;
                        int chebX = Math.abs(q.getX() - p.getX());
                        int chebY = Math.abs(q.getY() - p.getY());
                        if (chebX > 1 || chebY > 1) continue;
                        int cost = Math.max(chebX, chebY); // 0 or 1
                        addEdge(adj, p, q, cost);
                    }
                }
            }
        }
    }

    private static long bucketKey(int plane, int bx, int by)
    {
        // Pack plane (3 bits) + bx (24 bits) + by (24 bits). Trails span
        // OSRS world coords (~3000-4000) — 24 bits is more than enough.
        return ((long) (plane & 0x7) << 48)
             | ((long) (bx & 0xFFFFFF) << 24)
             | ((long) (by & 0xFFFFFF));
    }
}
```

- [ ] **Step 4: Re-run test**

Run: `./gradlew :runelite-client:test --tests "net.runelite.client.plugins.recorder.trail.TrailGraphWithinTrailTest" -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/plugins/recorder/trail/TrailGraph.java \
        runelite-client/src/test/java/net/runelite/client/plugins/recorder/trail/TrailGraphWithinTrailTest.java
git commit -m "trail: TrailGraph builds within-trail walk edges + bucketed nodes"
```

### Task 12: Junction edges (cost 0 / cost 1)

**Files:**
- Test: `runelite-client/src/test/java/net/runelite/client/plugins/recorder/trail/TrailGraphJunctionTest.java`

(The implementation already covers junctions — this task verifies it.)

- [ ] **Step 1: Write the test**

```java
// runelite-client/src/test/java/net/runelite/client/plugins/recorder/trail/TrailGraphJunctionTest.java
package net.runelite.client.plugins.recorder.trail;

import java.util.List;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.*;

public class TrailGraphJunctionTest
{
    @Test
    public void exactSharedTileGetsCost0Edge()
    {
        // Trail A passes through (10,10,0); trail B also includes (10,10,0).
        // Cost-0 means free transfer.
        Trail a = new Trail("A", 0L, List.of(
            new TrailEvent.Tile(0L, new WorldPoint(9, 10, 0)),
            new TrailEvent.Tile(1L, new WorldPoint(10, 10, 0))));
        Trail b = new Trail("B", 0L, List.of(
            new TrailEvent.Tile(0L, new WorldPoint(10, 10, 0)),
            new TrailEvent.Tile(1L, new WorldPoint(11, 10, 0))));
        TrailGraph g = TrailGraph.build(List.of(a, b));
        // A's (9,10) and B's (11,10) connect through (10,10) at cost 0+1+1.
        // The cost-0 edge isn't between (9,10) and (11,10) directly, but
        // any node already a tile-set in both trails gets a cost-0 self-
        // edge (we filter self-edges). So we test by ensuring the graph
        // has the shared node and the planner finds a low-cost path.
        assertTrue(g.nodes().contains(new WorldPoint(10, 10, 0)));
        // Direct edge (9,10) → (10,10) cost 1 (within-trail walk).
        assertEquals(1, g.edgeCost(new WorldPoint(9,10,0), new WorldPoint(10,10,0)));
        // Direct edge (10,10) → (11,10) cost 1 (within-trail walk via B).
        assertEquals(1, g.edgeCost(new WorldPoint(10,10,0), new WorldPoint(11,10,0)));
    }

    @Test
    public void chebyshev1ApartGetsCost1Edge()
    {
        // Trail A passes through (10,10,0); trail B nearby at (11,11,0).
        // Junction edge: cost 1 between (10,10) and (11,11).
        Trail a = new Trail("A", 0L, List.of(
            new TrailEvent.Tile(0L, new WorldPoint(10, 10, 0)),
            new TrailEvent.Tile(1L, new WorldPoint(10, 11, 0))));
        Trail b = new Trail("B", 0L, List.of(
            new TrailEvent.Tile(0L, new WorldPoint(11, 11, 0)),
            new TrailEvent.Tile(1L, new WorldPoint(12, 11, 0))));
        TrailGraph g = TrailGraph.build(List.of(a, b));
        // Junction between (10,10) [trail A] and (11,11) [trail B] —
        // Chebyshev = 1, cost = 1.
        assertEquals(1, g.edgeCost(new WorldPoint(10, 10, 0), new WorldPoint(11, 11, 0)));
    }

    @Test
    public void chebyshev2ApartGetsNoEdge()
    {
        Trail a = new Trail("A", 0L, List.of(
            new TrailEvent.Tile(0L, new WorldPoint(10, 10, 0))));
        Trail b = new Trail("B", 0L, List.of(
            new TrailEvent.Tile(0L, new WorldPoint(12, 12, 0))));
        TrailGraph g = TrailGraph.build(List.of(a, b));
        assertEquals(-1, g.edgeCost(new WorldPoint(10,10,0), new WorldPoint(12,12,0)));
    }

    @Test
    public void differentPlanesGetNoJunctionEdge()
    {
        Trail a = new Trail("A", 0L, List.of(
            new TrailEvent.Tile(0L, new WorldPoint(10, 10, 0))));
        Trail b = new Trail("B", 0L, List.of(
            new TrailEvent.Tile(0L, new WorldPoint(10, 10, 1))));
        TrailGraph g = TrailGraph.build(List.of(a, b));
        // Same x/y but different plane — no junction.
        assertEquals(-1, g.edgeCost(
            new WorldPoint(10, 10, 0), new WorldPoint(10, 10, 1)));
    }
}
```

- [ ] **Step 2: Run test**

Run: `./gradlew :runelite-client:test --tests "net.runelite.client.plugins.recorder.trail.TrailGraphJunctionTest" -q`
Expected: PASS — the existing `addJunctionEdges` already handles all four cases.

- [ ] **Step 3: Commit**

```bash
git add runelite-client/src/test/java/net/runelite/client/plugins/recorder/trail/TrailGraphJunctionTest.java
git commit -m "trail: regression tests for junction edges (cost 0/1, plane gating)"
```

### Task 13: Transport edge survives in graph

**Files:**
- Test: `runelite-client/src/test/java/net/runelite/client/plugins/recorder/trail/TrailGraphTransportTest.java`

- [ ] **Step 1: Write the test**

```java
// runelite-client/src/test/java/net/runelite/client/plugins/recorder/trail/TrailGraphTransportTest.java
package net.runelite.client.plugins.recorder.trail;

import java.util.List;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.*;

public class TrailGraphTransportTest
{
    @Test
    public void transportEdgeBetweenPrevAndNextTile()
    {
        // Tile (10,10,0) → TRANSPORT (Climb-down at (11,10,0)) → Tile (11,10,1)
        // Should produce: walk edge (10,10,0)→(11,10,0) is NOT created;
        // instead a TRANSPORT edge between the two tiles flanking the
        // transport event. The transport tile itself is the click target.
        Trail t = new Trail("ladder", 0L, List.of(
            new TrailEvent.Tile(0L,    new WorldPoint(10, 10, 0)),
            new TrailEvent.Transport(600L, new WorldPoint(11, 10, 0),
                "Climb-down", "Ladder", 1234, "GameObject", 3, 12, 13, List.of()),
            new TrailEvent.Tile(1200L, new WorldPoint(11, 10, 1))));
        TrailGraph g = TrailGraph.build(List.of(t));
        WorldPoint a = new WorldPoint(10, 10, 0);
        WorldPoint c = new WorldPoint(11, 10, 1);
        // Edge present, cost 1.
        assertEquals(1, g.edgeCost(a, c));
        Leg.Transport tr = g.transportBetween(a, c);
        assertNotNull("transport payload missing", tr);
        assertEquals("Climb-down", tr.verb());
        assertEquals(1234, tr.objectId());
        assertEquals(new WorldPoint(11, 10, 0), tr.tile());
        // Bidirectional.
        assertEquals(1, g.edgeCost(c, a));
        assertNotNull(g.transportBetween(c, a));
    }
}
```

- [ ] **Step 2: Run test**

Run: `./gradlew :runelite-client:test --tests "net.runelite.client.plugins.recorder.trail.TrailGraphTransportTest" -q`
Expected: PASS — already handled by `addWithinTrailEdges`.

- [ ] **Step 3: Commit**

```bash
git add runelite-client/src/test/java/net/runelite/client/plugins/recorder/trail/TrailGraphTransportTest.java
git commit -m "trail: regression test for transport edge in graph"
```

### Task 14: TrailPlanner A*

**Files:**
- Create: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/trail/TrailPlanner.java`
- Test: `runelite-client/src/test/java/net/runelite/client/plugins/recorder/trail/TrailPlannerTest.java`

- [ ] **Step 1: Write the failing test**

```java
// runelite-client/src/test/java/net/runelite/client/plugins/recorder/trail/TrailPlannerTest.java
package net.runelite.client.plugins.recorder.trail;

import java.util.List;
import java.util.Optional;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.*;

public class TrailPlannerTest
{
    @Test
    public void planSingleTrailWalksEveryTile()
    {
        Trail t = new Trail("straight", 0L, List.of(
            new TrailEvent.Tile(0L, new WorldPoint(0, 0, 0)),
            new TrailEvent.Tile(1L, new WorldPoint(0, 1, 0)),
            new TrailEvent.Tile(2L, new WorldPoint(0, 2, 0))));
        TrailGraph g = TrailGraph.build(List.of(t));
        TrailPlanner pl = new TrailPlanner(g);
        Optional<TrailPath> p = pl.plan(new WorldPoint(0, 0, 0), new WorldPoint(0, 2, 0));
        assertTrue(p.isPresent());
        assertEquals(1, p.get().legs().size());
        Leg.Walk w = (Leg.Walk) p.get().legs().get(0);
        assertEquals(3, w.tiles().size());
        assertEquals(new WorldPoint(0, 0, 0), w.tiles().get(0));
        assertEquals(new WorldPoint(0, 2, 0), w.tiles().get(2));
    }

    @Test
    public void planEmitsTransportLegBetweenWalks()
    {
        Trail t = new Trail("ladder", 0L, List.of(
            new TrailEvent.Tile(0L, new WorldPoint(0, 0, 0)),
            new TrailEvent.Tile(1L, new WorldPoint(1, 0, 0)),
            new TrailEvent.Transport(2L, new WorldPoint(1, 0, 0),
                "Climb-up", "Ladder", 9999, "GameObject", 3, 5, 6, List.of()),
            new TrailEvent.Tile(3L, new WorldPoint(1, 0, 1)),
            new TrailEvent.Tile(4L, new WorldPoint(2, 0, 1))));
        TrailGraph g = TrailGraph.build(List.of(t));
        TrailPlanner pl = new TrailPlanner(g);
        Optional<TrailPath> p = pl.plan(new WorldPoint(0, 0, 0), new WorldPoint(2, 0, 1));
        assertTrue(p.isPresent());
        List<Leg> legs = p.get().legs();
        assertEquals(3, legs.size());
        assertTrue("first leg WALK", legs.get(0) instanceof Leg.Walk);
        assertTrue("middle leg TRANSPORT", legs.get(1) instanceof Leg.Transport);
        assertTrue("last leg WALK", legs.get(2) instanceof Leg.Walk);
        Leg.Transport tr = (Leg.Transport) legs.get(1);
        assertEquals("Climb-up", tr.verb());
        assertEquals(9999, tr.objectId());
    }

    @Test
    public void planAcrossJunctionOfTwoTrails()
    {
        // Trail A: (0,0)→(1,0)→(2,0) on plane 0.
        // Trail B: (2,0)→(3,0)→(4,0) on plane 0.
        // Shared tile (2,0) — cost-0 junction. Plan (0,0)→(4,0) returns one
        // coalesced walk leg covering both trails.
        Trail a = new Trail("A", 0L, List.of(
            new TrailEvent.Tile(0L, new WorldPoint(0, 0, 0)),
            new TrailEvent.Tile(1L, new WorldPoint(1, 0, 0)),
            new TrailEvent.Tile(2L, new WorldPoint(2, 0, 0))));
        Trail b = new Trail("B", 0L, List.of(
            new TrailEvent.Tile(0L, new WorldPoint(2, 0, 0)),
            new TrailEvent.Tile(1L, new WorldPoint(3, 0, 0)),
            new TrailEvent.Tile(2L, new WorldPoint(4, 0, 0))));
        TrailGraph g = TrailGraph.build(List.of(a, b));
        TrailPlanner pl = new TrailPlanner(g);
        Optional<TrailPath> p = pl.plan(new WorldPoint(0, 0, 0), new WorldPoint(4, 0, 0));
        assertTrue(p.isPresent());
        // Coalesced into one walk.
        assertEquals(1, p.get().legs().size());
        Leg.Walk w = (Leg.Walk) p.get().legs().get(0);
        // Path visits all 5 tiles in order (the shared (2,0) tile dedupes).
        assertEquals(5, w.tiles().size());
        assertEquals(new WorldPoint(0, 0, 0), w.tiles().get(0));
        assertEquals(new WorldPoint(4, 0, 0), w.tiles().get(4));
    }

    @Test
    public void planReturnsEmptyWhenTargetOffGraph()
    {
        Trail t = new Trail("a", 0L, List.of(
            new TrailEvent.Tile(0L, new WorldPoint(0, 0, 0))));
        TrailGraph g = TrailGraph.build(List.of(t));
        TrailPlanner pl = new TrailPlanner(g);
        Optional<TrailPath> p = pl.plan(new WorldPoint(0, 0, 0), new WorldPoint(999, 999, 0));
        assertFalse(p.isPresent());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :runelite-client:test --tests "net.runelite.client.plugins.recorder.trail.TrailPlannerTest" -q`
Expected: FAIL — `TrailPlanner` doesn't exist.

- [ ] **Step 3: Implement TrailPlanner**

```java
// runelite-client/src/main/java/net/runelite/client/plugins/recorder/trail/TrailPlanner.java
package net.runelite.client.plugins.recorder.trail;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import net.runelite.api.coords.WorldPoint;

/** A* planner over a {@link TrailGraph}. Heuristic = Chebyshev distance
 *  to target on the same plane (admissible — every walk and transport
 *  edge costs at least 1). */
@RequiredArgsConstructor
public final class TrailPlanner
{
    private final TrailGraph graph;

    public Optional<TrailPath> plan(WorldPoint from, WorldPoint to)
    {
        if (from == null || to == null) return Optional.empty();
        // Both endpoints must already lie on the graph (or near it within
        // Chebyshev ≤ 1 — caller's responsibility, see spec). For now we
        // require an exact match.
        if (!graph.nodes().contains(from)) return Optional.empty();
        if (!graph.nodes().contains(to)) return Optional.empty();
        if (from.equals(to))
        {
            return Optional.of(new TrailPath(List.of(new Leg.Walk(List.of(from)))));
        }

        Map<WorldPoint, Integer> gScore = new HashMap<>();
        Map<WorldPoint, WorldPoint> cameFrom = new HashMap<>();
        gScore.put(from, 0);
        PriorityQueue<Node> open = new PriorityQueue<>();
        open.add(new Node(from, heuristic(from, to)));
        Set<WorldPoint> closed = new HashSet<>();

        while (!open.isEmpty())
        {
            Node cur = open.poll();
            if (cur.tile.equals(to))
            {
                return Optional.of(buildPath(cameFrom, cur.tile));
            }
            if (!closed.add(cur.tile)) continue;
            int curG = gScore.get(cur.tile);
            for (Map.Entry<WorldPoint, Integer> en : graph.neighbours(cur.tile).entrySet())
            {
                WorldPoint nb = en.getKey();
                int nextG = curG + en.getValue();
                Integer prev = gScore.get(nb);
                if (prev == null || nextG < prev)
                {
                    gScore.put(nb, nextG);
                    cameFrom.put(nb, cur.tile);
                    open.add(new Node(nb, nextG + heuristic(nb, to)));
                }
            }
        }
        return Optional.empty();
    }

    private static int heuristic(WorldPoint a, WorldPoint b)
    {
        // Chebyshev — admissible because every edge costs ≥ 1.
        if (a.getPlane() != b.getPlane())
        {
            return Math.max(Math.abs(a.getX() - b.getX()), Math.abs(a.getY() - b.getY())) + 1;
        }
        return Math.max(Math.abs(a.getX() - b.getX()), Math.abs(a.getY() - b.getY()));
    }

    /** Walk back through {@code cameFrom} and emit a {@link TrailPath} of
     *  alternating walk/transport legs. Two consecutive walk legs are
     *  coalesced into one. */
    private TrailPath buildPath(Map<WorldPoint, WorldPoint> cameFrom, WorldPoint end)
    {
        // Reconstruct tile chain start → end.
        List<WorldPoint> chain = new ArrayList<>();
        WorldPoint cur = end;
        while (cur != null) { chain.add(cur); cur = cameFrom.get(cur); }
        Collections.reverse(chain);

        List<Leg> legs = new ArrayList<>();
        List<WorldPoint> walkBuf = new ArrayList<>();
        walkBuf.add(chain.get(0));
        for (int i = 1; i < chain.size(); i++)
        {
            WorldPoint a = chain.get(i - 1);
            WorldPoint b = chain.get(i);
            Leg.Transport tr = graph.transportBetween(a, b);
            if (tr != null)
            {
                if (!walkBuf.isEmpty())
                {
                    legs.add(new Leg.Walk(new ArrayList<>(walkBuf)));
                    walkBuf.clear();
                }
                legs.add(tr);
                walkBuf.add(b);
            }
            else
            {
                walkBuf.add(b);
            }
        }
        if (walkBuf.size() > 1)
        {
            legs.add(new Leg.Walk(new ArrayList<>(walkBuf)));
        }
        else if (legs.isEmpty() && !walkBuf.isEmpty())
        {
            // Single-tile path — emit it as a one-tile walk so the walker
            // has something to do.
            legs.add(new Leg.Walk(new ArrayList<>(walkBuf)));
        }
        return new TrailPath(legs);
    }

    private static final class Node implements Comparable<Node>
    {
        final WorldPoint tile;
        final int f;
        Node(WorldPoint tile, int f) { this.tile = tile; this.f = f; }
        @Override public int compareTo(Node o) { return Integer.compare(this.f, o.f); }
    }
}
```

- [ ] **Step 4: Re-run test**

Run: `./gradlew :runelite-client:test --tests "net.runelite.client.plugins.recorder.trail.TrailPlannerTest" -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/plugins/recorder/trail/TrailPlanner.java \
        runelite-client/src/test/java/net/runelite/client/plugins/recorder/trail/TrailPlannerTest.java
git commit -m "trail: TrailPlanner A* over the junction graph (walk/transport legs)"
```

### Task 15: TrailPlanner — accept off-graph endpoints within 1 tile

The spec says `playerTile` and `targetTile` must be "on the graph (Chebyshev ≤ 1 from some trail node)". Current planner requires exact match — relax it.

**Files:**
- Modify: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/trail/TrailPlanner.java`
- Test: `runelite-client/src/test/java/net/runelite/client/plugins/recorder/trail/TrailPlannerProximityTest.java`

- [ ] **Step 1: Write the failing test**

```java
// runelite-client/src/test/java/net/runelite/client/plugins/recorder/trail/TrailPlannerProximityTest.java
package net.runelite.client.plugins.recorder.trail;

import java.util.List;
import java.util.Optional;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.*;

public class TrailPlannerProximityTest
{
    @Test
    public void offGraphStartWithinOneTileSnapsToNearestNode()
    {
        Trail t = new Trail("a", 0L, List.of(
            new TrailEvent.Tile(0L, new WorldPoint(10, 10, 0)),
            new TrailEvent.Tile(1L, new WorldPoint(10, 11, 0))));
        TrailGraph g = TrailGraph.build(List.of(t));
        TrailPlanner pl = new TrailPlanner(g);
        // Player at (11,10) — 1 tile off-axis from (10,10). Target at (10,11).
        Optional<TrailPath> p = pl.plan(new WorldPoint(11, 10, 0), new WorldPoint(10, 11, 0));
        assertTrue(p.isPresent());
        Leg.Walk w = (Leg.Walk) p.get().legs().get(0);
        // First tile is the snap-to node.
        assertEquals(new WorldPoint(10, 10, 0), w.tiles().get(0));
    }

    @Test
    public void offGraphEndWithinOneTileSnapsToNearestNode()
    {
        Trail t = new Trail("a", 0L, List.of(
            new TrailEvent.Tile(0L, new WorldPoint(10, 10, 0)),
            new TrailEvent.Tile(1L, new WorldPoint(10, 11, 0))));
        TrailGraph g = TrailGraph.build(List.of(t));
        TrailPlanner pl = new TrailPlanner(g);
        // Target (11,11) — 1 tile from (10,11).
        Optional<TrailPath> p = pl.plan(new WorldPoint(10, 10, 0), new WorldPoint(11, 11, 0));
        assertTrue(p.isPresent());
        Leg.Walk w = (Leg.Walk) p.get().legs().get(0);
        // Last tile is (10,11) (graph node) — caller walks the final hop.
        assertEquals(new WorldPoint(10, 11, 0), w.tiles().get(w.tiles().size() - 1));
    }

    @Test
    public void offGraphEndpointBeyondOneTileFails()
    {
        Trail t = new Trail("a", 0L, List.of(
            new TrailEvent.Tile(0L, new WorldPoint(10, 10, 0))));
        TrailGraph g = TrailGraph.build(List.of(t));
        TrailPlanner pl = new TrailPlanner(g);
        // Target 3 tiles away — no graph node within Chebyshev ≤ 1.
        Optional<TrailPath> p = pl.plan(new WorldPoint(10, 10, 0), new WorldPoint(13, 13, 0));
        assertFalse(p.isPresent());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :runelite-client:test --tests "net.runelite.client.plugins.recorder.trail.TrailPlannerProximityTest" -q`
Expected: FAIL — current planner returns empty for off-graph endpoints.

- [ ] **Step 3: Add snap-to-nearest-node logic**

In `TrailPlanner.java`, replace the early-return checks at the top of `plan(...)`:

```java
        if (!graph.nodes().contains(from)) return Optional.empty();
        if (!graph.nodes().contains(to)) return Optional.empty();
```

with:

```java
        WorldPoint snapFrom = snapToGraph(from);
        if (snapFrom == null) return Optional.empty();
        WorldPoint snapTo = snapToGraph(to);
        if (snapTo == null) return Optional.empty();
        from = snapFrom; to = snapTo;
```

Add the helper method:

```java
    /** Snap {@code p} to the nearest graph node within Chebyshev ≤ 1 on the
     *  same plane. Returns {@code p} unchanged if it is already on the
     *  graph, the closest within-1 node otherwise, or null if no node
     *  qualifies. */
    private WorldPoint snapToGraph(WorldPoint p)
    {
        if (graph.nodes().contains(p)) return p;
        WorldPoint best = null;
        int bestDist = Integer.MAX_VALUE;
        for (WorldPoint n : graph.nodes())
        {
            if (n.getPlane() != p.getPlane()) continue;
            int dx = Math.abs(n.getX() - p.getX());
            int dy = Math.abs(n.getY() - p.getY());
            int d = Math.max(dx, dy);
            if (d > 1) continue;
            if (d < bestDist) { best = n; bestDist = d; }
        }
        return best;
    }
```

- [ ] **Step 4: Re-run all planner tests**

Run: `./gradlew :runelite-client:test --tests "net.runelite.client.plugins.recorder.trail.TrailPlanner*Test" -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/plugins/recorder/trail/TrailPlanner.java \
        runelite-client/src/test/java/net/runelite/client/plugins/recorder/trail/TrailPlannerProximityTest.java
git commit -m "trail: TrailPlanner snaps off-graph endpoints within 1 tile"
```

---

## Phase 5 — Trail walker

### Task 16: TrailWalker skeleton + tile-progress tracking

**Files:**
- Create: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/trail/TrailWalker.java`
- Test: `runelite-client/src/test/java/net/runelite/client/plugins/recorder/trail/TrailWalkerProgressTest.java`

- [ ] **Step 1: Write the failing test**

```java
// runelite-client/src/test/java/net/runelite/client/plugins/recorder/trail/TrailWalkerProgressTest.java
package net.runelite.client.plugins.recorder.trail;

import java.util.List;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.*;

public class TrailWalkerProgressTest
{
    @Test
    public void chooseLegAdvancesPastWalkLegPlayerHasPassed()
    {
        Leg.Walk a = new Leg.Walk(List.of(
            new WorldPoint(0,0,0), new WorldPoint(0,1,0), new WorldPoint(0,2,0)));
        Leg.Walk b = new Leg.Walk(List.of(
            new WorldPoint(0,2,0), new WorldPoint(0,3,0), new WorldPoint(0,4,0)));
        TrailPath p = new TrailPath(List.of(a, b));
        // Player at (0,3,0) — already in leg b's tile-set.
        assertEquals(1, TrailWalker.chooseLegIndex(p, 0, new WorldPoint(0, 3, 0)));
    }

    @Test
    public void chooseLegStaysOnLegWhenNotYetPassed()
    {
        Leg.Walk a = new Leg.Walk(List.of(
            new WorldPoint(0,0,0), new WorldPoint(0,1,0)));
        Leg.Walk b = new Leg.Walk(List.of(
            new WorldPoint(0,1,0), new WorldPoint(0,2,0)));
        TrailPath p = new TrailPath(List.of(a, b));
        assertEquals(0, TrailWalker.chooseLegIndex(p, 0, new WorldPoint(0, 0, 0)));
    }

    @Test
    public void chooseLegMonotonicForward()
    {
        // Even if the player drifts back into leg-0's bbox, do not roll
        // back to leg 0 — start the search from minIdx.
        Leg.Walk a = new Leg.Walk(List.of(
            new WorldPoint(0,0,0), new WorldPoint(0,1,0)));
        Leg.Walk b = new Leg.Walk(List.of(
            new WorldPoint(0,2,0), new WorldPoint(0,3,0)));
        TrailPath p = new TrailPath(List.of(a, b));
        // Already on leg 1; player drifted back to (0,1,0). Should NOT
        // return 0.
        assertEquals(1, TrailWalker.chooseLegIndex(p, 1, new WorldPoint(0, 1, 0)));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :runelite-client:test --tests "net.runelite.client.plugins.recorder.trail.TrailWalkerProgressTest" -q`
Expected: FAIL — `TrailWalker.chooseLegIndex` doesn't exist.

- [ ] **Step 3: Implement skeleton + chooseLegIndex**

```java
// runelite-client/src/main/java/net/runelite/client/plugins/recorder/trail/TrailWalker.java
package net.runelite.client.plugins.recorder.trail;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.sequence.dispatch.HumanizedInputDispatcher;
import net.runelite.client.sequence.internal.ActionRequest;

/** Drives a {@link TrailPath} to completion. Same shape as
 *  {@link net.runelite.client.plugins.recorder.walker.UniversalWalker}:
 *  outer thread calls {@link #tick(TrailPath)} every ~600 ms and watches
 *  the returned {@link Status}.
 *
 *  <p>Per tick:
 *  <ol>
 *    <li>Read the player's tile on the client thread.</li>
 *    <li>{@link #chooseLegIndex} — monotonic-forward leg pick.</li>
 *    <li>WALK leg → click a randomly-chosen tile from the "ahead" window
 *        (humanizes which tile we click on each pass).</li>
 *    <li>TRANSPORT leg → walk to the transport tile if not adjacent;
 *        once adjacent, dispatch CLICK_GAME_OBJECT with the recorded
 *        verb / objectId.</li>
 *    <li>If the player hasn't moved for {@link #STUCK_AFTER_MS}, return
 *        {@link Status#STUCK}.</li>
 *  </ol>
 */
@Slf4j
public final class TrailWalker
{
    public enum Status { IN_PROGRESS, ARRIVED, STUCK, ERROR }

    /** Re-issue a click only if the leg's pick changed OR this many ms
     *  have passed since the last click without movement. */
    public static final long RECLICK_AFTER_MS = 3_000;
    public static final long STILL_THRESHOLD_MS = 2_500;
    public static final long INTERACT_THROTTLE_MS = 3_000;
    public static final long STUCK_AFTER_MS = 15_000;
    public static final int TRANSPORT_ADJACENCY = 1;

    private final Client client;
    private final ClientThread clientThread;
    private final HumanizedInputDispatcher dispatcher;

    private TrailPath currentPath;
    private int legIdx;
    private WorldPoint lastSeenPosition;
    private long lastMovementMs;
    private long lastClickMs;
    private long lastInteractMs;
    private int lastClickLegIdx = -1;
    private WorldPoint lastWalkPick;

    public TrailWalker(Client client, ClientThread clientThread,
                       HumanizedInputDispatcher dispatcher)
    {
        this.client = client;
        this.clientThread = clientThread;
        this.dispatcher = dispatcher;
        reset();
    }

    public void reset()
    {
        currentPath = null;
        legIdx = 0;
        lastSeenPosition = null;
        lastMovementMs = System.currentTimeMillis();
        lastClickMs = 0;
        lastInteractMs = 0;
        lastClickLegIdx = -1;
        lastWalkPick = null;
    }

    public int currentLegIndex() { return legIdx; }

    /** Pick the active leg index for the given player position. Pure
     *  function — exposed package-public for unit testing. */
    static int chooseLegIndex(TrailPath path, int minIdx, WorldPoint pos)
    {
        List<Leg> legs = path.legs();
        // Walk forward from minIdx; advance past any leg whose tile-set
        // the player has already visited AND whose successor leg also
        // contains the player.
        int idx = minIdx;
        for (int i = minIdx; i < legs.size() - 1; i++)
        {
            Leg next = legs.get(i + 1);
            if (legContainsTile(next, pos))
            {
                idx = i + 1;
            }
        }
        return idx;
    }

    private static boolean legContainsTile(Leg l, WorldPoint pos)
    {
        if (l instanceof Leg.Walk w)
        {
            for (WorldPoint t : w.tiles())
            {
                if (t.equals(pos)) return true;
            }
            return false;
        }
        if (l instanceof Leg.Transport t)
        {
            return t.tile().equals(pos);
        }
        return false;
    }
}
```

- [ ] **Step 4: Re-run test**

Run: `./gradlew :runelite-client:test --tests "net.runelite.client.plugins.recorder.trail.TrailWalkerProgressTest" -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/plugins/recorder/trail/TrailWalker.java \
        runelite-client/src/test/java/net/runelite/client/plugins/recorder/trail/TrailWalkerProgressTest.java
git commit -m "trail: TrailWalker skeleton + monotonic chooseLegIndex"
```

### Task 17: Walk-leg pick (random ahead-window for click variety)

**Files:**
- Modify: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/trail/TrailWalker.java`
- Test: `runelite-client/src/test/java/net/runelite/client/plugins/recorder/trail/TrailWalkerPickTest.java`

- [ ] **Step 1: Write the failing test**

```java
// runelite-client/src/test/java/net/runelite/client/plugins/recorder/trail/TrailWalkerPickTest.java
package net.runelite.client.plugins.recorder.trail;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.*;

public class TrailWalkerPickTest
{
    @Test
    public void pickAheadTileIsAheadOfPlayerInLeg()
    {
        Leg.Walk leg = new Leg.Walk(List.of(
            new WorldPoint(0, 0, 0), new WorldPoint(0, 1, 0), new WorldPoint(0, 2, 0),
            new WorldPoint(0, 3, 0), new WorldPoint(0, 4, 0)));
        // Player at index 1 → ahead = indices 2..4. Each call must return
        // a tile in that ahead window.
        Random rng = new Random(42);
        for (int i = 0; i < 50; i++)
        {
            WorldPoint pick = TrailWalker.pickAheadTile(leg, new WorldPoint(0, 1, 0), rng);
            int idx = leg.tiles().indexOf(pick);
            assertTrue("pick " + pick + " not ahead (idx=" + idx + ")",
                idx >= 2 && idx <= 4);
        }
    }

    @Test
    public void pickAheadTileVariesAcrossCalls()
    {
        // Long leg → multiple distinct picks across many calls.
        Leg.Walk leg = new Leg.Walk(List.of(
            new WorldPoint(0,0,0), new WorldPoint(0,1,0), new WorldPoint(0,2,0),
            new WorldPoint(0,3,0), new WorldPoint(0,4,0), new WorldPoint(0,5,0),
            new WorldPoint(0,6,0), new WorldPoint(0,7,0), new WorldPoint(0,8,0)));
        Random rng = new Random(42);
        Set<WorldPoint> seen = new HashSet<>();
        for (int i = 0; i < 50; i++)
        {
            seen.add(TrailWalker.pickAheadTile(leg, new WorldPoint(0, 0, 0), rng));
        }
        // With a 6-tile ahead window we expect at least 4 distinct picks
        // over 50 trials (would be 6 with perfect uniform sampling, leave
        // headroom for the smallest-window-bias rule).
        assertTrue("only " + seen.size() + " distinct picks", seen.size() >= 4);
    }

    @Test
    public void pickAheadTilePlayerNotInLegFallsBackToFarthest()
    {
        Leg.Walk leg = new Leg.Walk(List.of(
            new WorldPoint(0,0,0), new WorldPoint(0,1,0), new WorldPoint(0,2,0)));
        Random rng = new Random(0);
        // Player at (5,5,0) — not in leg. Pick should be farthest tile.
        WorldPoint pick = TrailWalker.pickAheadTile(leg, new WorldPoint(5, 5, 0), rng);
        assertEquals(new WorldPoint(0, 2, 0), pick);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :runelite-client:test --tests "net.runelite.client.plugins.recorder.trail.TrailWalkerPickTest" -q`
Expected: FAIL — `pickAheadTile` doesn't exist.

- [ ] **Step 3: Implement pickAheadTile**

Append to `TrailWalker.java`:

```java
    /** Choose a tile inside {@code leg} that is "ahead" of the player.
     *  Ahead = later in {@code leg.tiles()} than the closest tile to the
     *  player. From those candidates, pick one uniformly at random — this
     *  is the click humanization that makes a real player's "I'll click
     *  somewhere over there" different on every pass.
     *
     *  <p>If the player isn't in the leg's tile list at all, we fall back
     *  to the farthest tile (== the leg's destination), matching the
     *  spec's "farthest tile in this leg that is still ahead of the
     *  player". */
    static WorldPoint pickAheadTile(Leg.Walk leg, WorldPoint player, java.util.Random rng)
    {
        List<WorldPoint> tiles = leg.tiles();
        int closestIdx = -1;
        int closestDist = Integer.MAX_VALUE;
        for (int i = 0; i < tiles.size(); i++)
        {
            WorldPoint t = tiles.get(i);
            if (t.getPlane() != player.getPlane()) continue;
            int dx = Math.abs(t.getX() - player.getX());
            int dy = Math.abs(t.getY() - player.getY());
            int d = Math.max(dx, dy);
            if (d < closestDist) { closestDist = d; closestIdx = i; }
        }
        if (closestIdx < 0) return tiles.get(tiles.size() - 1);
        int firstAhead = closestIdx + 1;
        if (firstAhead >= tiles.size()) return tiles.get(tiles.size() - 1);
        // Bias toward the END of the ahead window (real players don't
        // click 1-step ahead unless they have to). Pick uniformly from
        // the back HALF of the ahead window (rounded up) so we still vary
        // the click but stay forward-leaning.
        int aheadCount = tiles.size() - firstAhead;
        int windowStart = firstAhead + Math.max(0, aheadCount / 2);
        int windowSize = tiles.size() - windowStart;
        int pickIdx = windowStart + rng.nextInt(Math.max(1, windowSize));
        return tiles.get(pickIdx);
    }
```

- [ ] **Step 4: Re-run test**

Run: `./gradlew :runelite-client:test --tests "net.runelite.client.plugins.recorder.trail.TrailWalkerPickTest" -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/plugins/recorder/trail/TrailWalker.java \
        runelite-client/src/test/java/net/runelite/client/plugins/recorder/trail/TrailWalkerPickTest.java
git commit -m "trail: pickAheadTile picks a random tile from leg's ahead window"
```

### Task 18: TrailWalker.tick — full per-tick logic

**Files:**
- Modify: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/trail/TrailWalker.java`
- Test: `runelite-client/src/test/java/net/runelite/client/plugins/recorder/trail/TrailWalkerTickTest.java`

- [ ] **Step 1: Write the failing test**

```java
// runelite-client/src/test/java/net/runelite/client/plugins/recorder/trail/TrailWalkerTickTest.java
package net.runelite.client.plugins.recorder.trail;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.sequence.dispatch.HumanizedInputDispatcher;
import net.runelite.client.sequence.internal.ActionRequest;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class TrailWalkerTickTest
{
    private Client client;
    private ClientThread clientThread;
    private HumanizedInputDispatcher dispatcher;
    private Player local;
    private final AtomicReference<WorldPoint> playerPos = new AtomicReference<>();

    @Before
    public void setUp()
    {
        client = mock(Client.class);
        clientThread = mock(ClientThread.class);
        dispatcher = mock(HumanizedInputDispatcher.class);
        local = mock(Player.class);
        when(client.getLocalPlayer()).thenReturn(local);
        when(local.getWorldLocation()).thenAnswer(i -> playerPos.get());
        // ClientThread.invokeLater runs the runnable inline (synchronous
        // for tests).
        doAnswer(inv -> { ((Runnable) inv.getArgument(0)).run(); return null; })
            .when(clientThread).invokeLater(any(Runnable.class));
    }

    @Test
    public void walkLegDispatchesWALK() throws InterruptedException
    {
        playerPos.set(new WorldPoint(0, 0, 0));
        TrailPath path = new TrailPath(List.of(new Leg.Walk(List.of(
            new WorldPoint(0, 0, 0), new WorldPoint(0, 1, 0), new WorldPoint(0, 2, 0)))));
        TrailWalker w = new TrailWalker(client, clientThread, dispatcher);
        TrailWalker.Status s = w.tick(path);
        assertEquals(TrailWalker.Status.IN_PROGRESS, s);
        ArgumentCaptor<ActionRequest> cap = ArgumentCaptor.forClass(ActionRequest.class);
        verify(dispatcher).dispatch(cap.capture());
        assertEquals(ActionRequest.Kind.WALK, cap.getValue().getKind());
    }

    @Test
    public void singleTileWalkLegArrivesWhenPlayerOnTile() throws InterruptedException
    {
        playerPos.set(new WorldPoint(5, 5, 0));
        TrailPath path = new TrailPath(List.of(new Leg.Walk(List.of(
            new WorldPoint(5, 5, 0)))));
        TrailWalker w = new TrailWalker(client, clientThread, dispatcher);
        assertEquals(TrailWalker.Status.ARRIVED, w.tick(path));
    }

    @Test
    public void transportLegDispatchesCLICK_GAME_OBJECTwhenAdjacent() throws InterruptedException
    {
        playerPos.set(new WorldPoint(10, 10, 0));
        when(local.getPoseAnimation()).thenReturn(7);
        when(local.getIdlePoseAnimation()).thenReturn(7); // settled
        TrailPath path = new TrailPath(List.of(
            new Leg.Walk(List.of(new WorldPoint(10, 10, 0))),
            new Leg.Transport(new WorldPoint(10, 10, 0),
                "Climb-down", 5678, "GameObject", 1, 2),
            new Leg.Walk(List.of(new WorldPoint(10, 10, 1)))));
        TrailWalker w = new TrailWalker(client, clientThread, dispatcher);
        // First tick on leg 0: player at (10,10,0) — single-tile walk. The
        // walker should advance into leg 1 (transport) and dispatch a
        // CLICK_GAME_OBJECT.
        w.tick(path);
        ArgumentCaptor<ActionRequest> cap = ArgumentCaptor.forClass(ActionRequest.class);
        verify(dispatcher, atLeastOnce()).dispatch(cap.capture());
        boolean sawTransportClick = cap.getAllValues().stream()
            .anyMatch(r -> r.getKind() == ActionRequest.Kind.CLICK_GAME_OBJECT);
        assertTrue("expected at least one CLICK_GAME_OBJECT dispatch", sawTransportClick);
    }

    @Test
    public void emptyPathReturnsArrived() throws InterruptedException
    {
        TrailWalker w = new TrailWalker(client, clientThread, dispatcher);
        assertEquals(TrailWalker.Status.ARRIVED, w.tick(new TrailPath(List.of())));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :runelite-client:test --tests "net.runelite.client.plugins.recorder.trail.TrailWalkerTickTest" -q`
Expected: FAIL — `tick` is not implemented.

- [ ] **Step 3: Implement tick**

Append to `TrailWalker.java`:

```java
    public Status tick(TrailPath path) throws InterruptedException
    {
        if (path == null || path.isEmpty()) return Status.ARRIVED;
        if (currentPath != path)
        {
            reset();
            currentPath = path;
        }
        if (legIdx >= path.size()) return Status.ARRIVED;

        WorldPoint pos = readPlayerTile();
        if (pos == null) return Status.IN_PROGRESS;

        long now = System.currentTimeMillis();
        if (lastSeenPosition == null || !lastSeenPosition.equals(pos))
        {
            lastSeenPosition = pos;
            lastMovementMs = now;
        }

        // Locate the active leg. Monotonic forward.
        int newIdx = chooseLegIndex(path, legIdx, pos);
        if (newIdx > legIdx)
        {
            log.info("trail-walker: advancing leg {} → {} (player at {})",
                legIdx, newIdx, pos);
            legIdx = newIdx;
            lastWalkPick = null;
            // Camera rotate to a tile inside the new leg so we don't walk
            // staring at the back of our head.
            WorldPoint focus = legFocusTile(path.legs().get(legIdx));
            if (focus != null)
            {
                try { dispatcher.rotateCameraToward(focus); }
                catch (Throwable t) { log.debug("camera rotate failed", t); }
            }
        }
        if (legIdx >= path.size()) return Status.ARRIVED;

        Leg active = path.legs().get(legIdx);
        Status s;
        if (active instanceof Leg.Walk wl) s = handleWalkLeg(wl, pos, now);
        else if (active instanceof Leg.Transport tr) s = handleTransportLeg(tr, pos, now);
        else s = Status.ERROR;

        if (s == Status.IN_PROGRESS && now - lastMovementMs > STUCK_AFTER_MS)
        {
            log.warn("trail-walker: STUCK — no movement for {}ms at leg {} ({})",
                now - lastMovementMs, legIdx, active.kind());
            return Status.STUCK;
        }

        // ARRIVED only when the very last leg is satisfied.
        if (s == Status.IN_PROGRESS && legIdx == path.size() - 1
            && active instanceof Leg.Walk fin
            && legContainsTile(fin, pos))
        {
            return Status.ARRIVED;
        }
        return s;
    }

    private Status handleWalkLeg(Leg.Walk leg, WorldPoint pos, long now) throws InterruptedException
    {
        if (legContainsTile(leg, pos) && pos.equals(leg.tiles().get(leg.tiles().size() - 1)))
        {
            // Reached the final tile of THIS leg — let the next tick
            // advance to the next leg.
            return Status.IN_PROGRESS;
        }
        WorldPoint pick = pickAheadTile(leg,
            pos, ThreadLocalRandom.current());
        if (shouldClick(now, pick))
        {
            log.info("trail-walker: WALK leg {} → tile {}", legIdx, pick);
            ActionRequest req = ActionRequest.builder()
                .kind(ActionRequest.Kind.WALK)
                .channel(ActionRequest.Channel.MOUSE)
                .tile(pick)
                .build();
            dispatcher.dispatch(req);
            lastClickMs = now;
            lastClickLegIdx = legIdx;
            lastWalkPick = pick;
        }
        return Status.IN_PROGRESS;
    }

    private Status handleTransportLeg(Leg.Transport leg, WorldPoint pos, long now)
        throws InterruptedException
    {
        WorldPoint t = leg.tile();
        boolean adjacent = pos.getPlane() == t.getPlane()
            && Math.abs(pos.getX() - t.getX()) <= TRANSPORT_ADJACENCY
            && Math.abs(pos.getY() - t.getY()) <= TRANSPORT_ADJACENCY;
        if (!adjacent)
        {
            // Walk toward the transport tile.
            if (shouldClick(now, t))
            {
                log.info("trail-walker: walk-to-transport {} (verb {})", t, leg.verb());
                ActionRequest req = ActionRequest.builder()
                    .kind(ActionRequest.Kind.WALK)
                    .channel(ActionRequest.Channel.MOUSE)
                    .tile(t)
                    .build();
                dispatcher.dispatch(req);
                lastClickMs = now;
                lastClickLegIdx = legIdx;
                lastWalkPick = t;
            }
            return Status.IN_PROGRESS;
        }
        if (now - lastInteractMs < INTERACT_THROTTLE_MS) return Status.IN_PROGRESS;
        // Wait until the player's pose is idle before clicking the verb —
        // mirrors UniversalWalker's "don't open menu mid-walk" rule.
        Boolean settled = onClient(() -> {
            Player self = client.getLocalPlayer();
            return self != null && self.getPoseAnimation() == self.getIdlePoseAnimation();
        });
        if (settled == null || !settled) return Status.IN_PROGRESS;
        log.info("trail-walker: CLICK_GAME_OBJECT verb '{}' tile {} id {}",
            leg.verb(), t, leg.objectId());
        ActionRequest req = ActionRequest.builder()
            .kind(ActionRequest.Kind.CLICK_GAME_OBJECT)
            .channel(ActionRequest.Channel.MOUSE)
            .tile(t)
            .verb(leg.verb())
            .build();
        dispatcher.dispatch(req);
        lastInteractMs = now;
        lastClickMs = now;
        return Status.IN_PROGRESS;
    }

    private boolean shouldClick(long now, WorldPoint pick)
    {
        if (legIdx != lastClickLegIdx) return true;
        if (lastWalkPick == null || !lastWalkPick.equals(pick)) return true;
        long sinceClick = lastClickMs == 0 ? Long.MAX_VALUE : now - lastClickMs;
        long sinceMove = now - lastMovementMs;
        boolean stillWalking = sinceMove < STILL_THRESHOLD_MS;
        boolean recentClick = sinceClick < RECLICK_AFTER_MS;
        return !recentClick && !stillWalking;
    }

    @Nullable
    private WorldPoint readPlayerTile()
    {
        return onClient(() -> {
            Player self = client.getLocalPlayer();
            return self == null ? null : self.getWorldLocation();
        });
    }

    @Nullable
    private <T> T onClient(java.util.function.Supplier<T> sup)
    {
        AtomicReference<T> ref = new AtomicReference<>();
        java.util.concurrent.CountDownLatch latch =
            new java.util.concurrent.CountDownLatch(1);
        clientThread.invokeLater(() -> {
            try { ref.set(sup.get()); }
            catch (Throwable th) { log.warn("trail-walker: onClient threw", th); }
            finally { latch.countDown(); }
        });
        try { latch.await(); }
        catch (InterruptedException ie) { Thread.currentThread().interrupt(); return null; }
        return ref.get();
    }

    @Nullable
    private static WorldPoint legFocusTile(Leg leg)
    {
        if (leg instanceof Leg.Walk w) return w.tiles().get(w.tiles().size() - 1);
        if (leg instanceof Leg.Transport t) return t.tile();
        return null;
    }
```

- [ ] **Step 4: Re-run test**

Run: `./gradlew :runelite-client:test --tests "net.runelite.client.plugins.recorder.trail.TrailWalkerTickTest" -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/plugins/recorder/trail/TrailWalker.java \
        runelite-client/src/test/java/net/runelite/client/plugins/recorder/trail/TrailWalkerTickTest.java
git commit -m "trail: TrailWalker.tick walks legs, dispatches WALK and CLICK_GAME_OBJECT"
```

---

## Phase 6 — Plugin + panel wiring

### Task 19: RecorderPlugin Guice wiring

**Files:**
- Modify: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/RecorderPlugin.java`

- [ ] **Step 1: Wire up TrailRecorder + TrailRegistry**

Add imports near the top of `RecorderPlugin.java`:

```java
import net.runelite.client.plugins.recorder.trail.TrailRecorder;
import net.runelite.client.plugins.recorder.trail.TrailRegistry;
```

Add fields:

```java
    private TrailRecorder trailRecorder;
    private TrailRegistry trailRegistry;
```

Inside `startUp()`, after the V2 wiring block (around line 214), add:

```java
        // Trail recorder + registry. The registry directory lives under
        // ~/.runelite/recorder/trails/ — separate from the session
        // recordings root because trails are a different artefact (route
        // capture, not full session capture).
        java.nio.file.Path trailDir = java.nio.file.Paths.get(
            System.getProperty("user.home"), ".runelite", "recorder", "trails");
        trailRegistry = new TrailRegistry(trailDir);
        trailRegistry.load();
        trailRecorder = new TrailRecorder(client);
        eventBus.register(trailRecorder);
        panel.setTrailRecorder(trailRecorder);
        panel.setTrailRegistry(trailRegistry);
```

Inside `shutDown()`, before the field-clearing block at the end:

```java
        if (trailRecorder != null) eventBus.unregister(trailRecorder);
        trailRecorder = null;
        trailRegistry = null;
```

- [ ] **Step 2: Run a compile check (without panel changes — they come next)**

Run: `./gradlew :runelite-client:compileJava -q`
Expected: FAIL — `RecorderPanel` doesn't have `setTrailRecorder` / `setTrailRegistry` yet. That's the next task. Confirm the failure mentions only those two methods.

- [ ] **Step 3: Defer commit until Task 20 also lands**

Don't commit yet — the panel methods land next, then we commit both together.

### Task 20: RecorderPanel Trails sub-section

**Files:**
- Modify: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/RecorderPanel.java`

- [ ] **Step 1: Add Trails section to Routes tab**

Add imports near the top of `RecorderPanel.java`:

```java
import net.runelite.client.plugins.recorder.trail.TrailPath;
import net.runelite.client.plugins.recorder.trail.TrailPlanner;
import net.runelite.client.plugins.recorder.trail.TrailGraph;
import net.runelite.client.plugins.recorder.trail.TrailRecorder;
import net.runelite.client.plugins.recorder.trail.TrailRegistry;
import net.runelite.client.plugins.recorder.trail.TrailWalker;
```

Add fields near the other panel fields:

```java
    private TrailRecorder trailRecorder;
    private TrailRegistry trailRegistry;
    private final JTextField trailNameField = new JTextField(14);
    private final JButton trailRecordBtn = new JButton("Record trail");
    private final JButton trailStopSaveBtn = new JButton("Stop & save");
    private final JTextField trailWalkToField = new JTextField(14);
    private final JButton trailWalkToBtn = new JButton("Walk to…");
    private final JLabel trailStatusLabel = new JLabel("Trails: idle");
    private volatile Thread trailWalkerThread;
```

Add setter methods used by `RecorderPlugin`:

```java
    public void setTrailRecorder(TrailRecorder rec)
    {
        this.trailRecorder = rec;
        SwingUtilities.invokeLater(this::updateTrailButtons);
    }

    public void setTrailRegistry(TrailRegistry reg)
    {
        this.trailRegistry = reg;
        SwingUtilities.invokeLater(this::updateTrailButtons);
    }

    private void updateTrailButtons()
    {
        boolean ready = trailRecorder != null && trailRegistry != null;
        boolean recording = ready && trailRecorder.isRecording();
        trailRecordBtn.setEnabled(ready && !recording);
        trailStopSaveBtn.setEnabled(ready && recording);
        trailWalkToBtn.setEnabled(ready && !recording);
        trailNameField.setEnabled(ready && !recording);
        trailStatusLabel.setText("Trails: " + (recording
            ? "recording \"" + trailRecorder.currentName() + "\""
            : "idle"));
    }

    private JComponent buildTrailsSection()
    {
        JPanel out = new JPanel();
        out.setLayout(new BoxLayout(out, BoxLayout.Y_AXIS));
        out.setBackground(ColorScheme.DARK_GRAY_COLOR);
        out.setBorder(BorderFactory.createTitledBorder("Trails"));
        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        row1.setBackground(ColorScheme.DARK_GRAY_COLOR);
        row1.add(new JLabel("Name:"));
        row1.add(trailNameField);
        out.add(row1);
        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        row2.setBackground(ColorScheme.DARK_GRAY_COLOR);
        row2.add(trailRecordBtn);
        row2.add(trailStopSaveBtn);
        out.add(row2);
        JPanel row3 = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        row3.setBackground(ColorScheme.DARK_GRAY_COLOR);
        row3.add(new JLabel("Walk to (x,y,p):"));
        row3.add(trailWalkToField);
        row3.add(trailWalkToBtn);
        out.add(row3);
        out.add(trailStatusLabel);
        trailRecordBtn.addActionListener(e -> startTrailRecord());
        trailStopSaveBtn.addActionListener(e -> stopTrailAndSave());
        trailWalkToBtn.addActionListener(e -> walkToTarget());
        updateTrailButtons();
        return out;
    }

    private void startTrailRecord()
    {
        if (trailRecorder == null) return;
        String name = trailNameField.getText().trim();
        if (name.isEmpty())
        {
            trailStatusLabel.setText("Trails: name required");
            return;
        }
        try { trailRecorder.start(name); }
        catch (Throwable t)
        {
            trailStatusLabel.setText("Trails: " + t.getMessage());
            return;
        }
        updateTrailButtons();
    }

    private void stopTrailAndSave()
    {
        if (trailRecorder == null || trailRegistry == null) return;
        try
        {
            var trail = trailRecorder.stopAndBuild();
            trailRegistry.save(trail);
            trailStatusLabel.setText("Trails: saved \"" + trail.name() + "\" ("
                + trail.events().size() + " events)");
        }
        catch (Throwable t)
        {
            trailStatusLabel.setText("Trails: save failed: " + t.getMessage());
            log.warn("trail save failed", t);
        }
        updateTrailButtons();
    }

    private void walkToTarget()
    {
        if (trailRegistry == null) return;
        String txt = trailWalkToField.getText().trim();
        WorldPoint target = parseWorldPoint(txt);
        if (target == null)
        {
            trailStatusLabel.setText("Trails: bad target — use \"x,y,p\"");
            return;
        }
        trailRegistry.load();   // pick up any trail saved this session
        TrailGraph graph = TrailGraph.build(trailRegistry.all());
        TrailPlanner planner = new TrailPlanner(graph);
        WorldPoint here;
        try { here = onClientThreadGetWorldPoint(); }
        catch (Throwable t) { trailStatusLabel.setText("Trails: " + t); return; }
        if (here == null) { trailStatusLabel.setText("Trails: player not loaded"); return; }
        var pathOpt = planner.plan(here, target);
        if (pathOpt.isEmpty())
        {
            trailStatusLabel.setText("Trails: no trail covers this route — record one");
            return;
        }
        TrailPath path = pathOpt.get();
        trailStatusLabel.setText("Trails: walking " + path.size() + " legs");
        // Off-thread driver so the EDT stays responsive.
        Thread t = new Thread(() -> driveTrailWalker(path), "trail-walker-test");
        t.setDaemon(true);
        trailWalkerThread = t;
        t.start();
    }

    private void driveTrailWalker(TrailPath path)
    {
        TrailWalker w = new TrailWalker(client, clientThread, dispatcher);
        try
        {
            while (!Thread.currentThread().isInterrupted())
            {
                TrailWalker.Status s = w.tick(path);
                final String label = "Trails: walking — leg " + (w.currentLegIndex() + 1)
                    + "/" + path.size() + " — " + s;
                SwingUtilities.invokeLater(() -> trailStatusLabel.setText(label));
                if (s == TrailWalker.Status.ARRIVED || s == TrailWalker.Status.STUCK
                    || s == TrailWalker.Status.ERROR)
                {
                    final String done = "Trails: " + s;
                    SwingUtilities.invokeLater(() -> trailStatusLabel.setText(done));
                    return;
                }
                Thread.sleep(600);
            }
        }
        catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }

    @Nullable
    private static WorldPoint parseWorldPoint(String txt)
    {
        String[] parts = txt.split(",");
        if (parts.length != 3) return null;
        try
        {
            return new WorldPoint(
                Integer.parseInt(parts[0].trim()),
                Integer.parseInt(parts[1].trim()),
                Integer.parseInt(parts[2].trim()));
        }
        catch (NumberFormatException ex) { return null; }
    }

    private WorldPoint onClientThreadGetWorldPoint() throws InterruptedException
    {
        java.util.concurrent.atomic.AtomicReference<WorldPoint> ref = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        clientThread.invokeLater(() -> {
            try
            {
                var local = client.getLocalPlayer();
                ref.set(local == null ? null : local.getWorldLocation());
            }
            finally { latch.countDown(); }
        });
        latch.await();
        return ref.get();
    }
```

- [ ] **Step 2: Add the section to the Routes tab**

Locate `buildRoutesTab()` (use grep — it returns a `JComponent` that's added to the `Routes` tab). At the very end of the routes container build (just before `return …;`), insert:

```java
        out.add(buildTrailsSection());
        out.add(Box.createVerticalStrut(8));
```

(Adjust `out` to whatever the routes container variable name is in this codebase. If `buildRoutesTab` returns a wrapped JPanel, append to that panel before returning.)

- [ ] **Step 3: Compile + commit Tasks 19 + 20 together**

Run: `./gradlew :runelite-client:compileJava -q`
Expected: PASS — both wiring sides agree.

```bash
git add runelite-client/src/main/java/net/runelite/client/plugins/recorder/RecorderPlugin.java \
        runelite-client/src/main/java/net/runelite/client/plugins/recorder/RecorderPanel.java
git commit -m "trail: wire TrailRecorder + TrailRegistry into recorder panel"
```

---

## Phase 7 — Chicken Farm V3 (uses trails)

V3 mirrors V2's outer FSM but swaps the walking implementation. Banking and combat are reused unchanged.

### Task 21: ChickenFarmV3Script skeleton + state FSM

**Files:**
- Create: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/scripts/ChickenFarmV3Script.java`
- Test: `runelite-client/src/test/java/net/runelite/client/plugins/recorder/scripts/ChickenFarmV3ScriptTest.java`

- [ ] **Step 1: Write the failing test**

```java
// runelite-client/src/test/java/net/runelite/client/plugins/recorder/scripts/ChickenFarmV3ScriptTest.java
package net.runelite.client.plugins.recorder.scripts;

import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.plugins.recorder.transport.TransportResolver;
import net.runelite.client.plugins.recorder.trail.TrailRegistry;
import net.runelite.client.sequence.dispatch.HumanizedInputDispatcher;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class ChickenFarmV3ScriptTest
{
    @Test
    public void freshScriptIsIdle()
    {
        TrailRegistry reg = mock(TrailRegistry.class);
        ChickenFarmV3Script s = new ChickenFarmV3Script(
            mock(Client.class), mock(ClientThread.class),
            mock(HumanizedInputDispatcher.class),
            mock(TransportResolver.class), reg);
        assertEquals(ChickenFarmV3Script.State.IDLE, s.state());
    }

    @Test
    public void trailNamesAreThoseSpecified()
    {
        // The script reads two specific trail names from the registry —
        // 'lumby-bank-to-pen' and 'pen-to-lumby-bank'. These are the
        // names the user is told to record.
        assertEquals("lumby-bank-to-pen", ChickenFarmV3Script.OUTBOUND_TRAIL_NAME);
        assertEquals("pen-to-lumby-bank", ChickenFarmV3Script.RETURN_TRAIL_NAME);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :runelite-client:test --tests "net.runelite.client.plugins.recorder.scripts.ChickenFarmV3ScriptTest" -q`
Expected: FAIL — class doesn't exist.

- [ ] **Step 3: Implement V3 skeleton (copy V2 outer FSM)**

```java
// runelite-client/src/main/java/net/runelite/client/plugins/recorder/scripts/ChickenFarmV3Script.java
package net.runelite.client.plugins.recorder.scripts;

import java.awt.Rectangle;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.plugins.recorder.combat.ChickenCombatLoop;
import net.runelite.client.plugins.recorder.farm.BankInteraction;
import net.runelite.client.plugins.recorder.farm.InventoryUtil;
import net.runelite.client.plugins.recorder.trail.TrailGraph;
import net.runelite.client.plugins.recorder.trail.TrailPath;
import net.runelite.client.plugins.recorder.trail.TrailPlanner;
import net.runelite.client.plugins.recorder.trail.TrailRegistry;
import net.runelite.client.plugins.recorder.trail.TrailWalker;
import net.runelite.client.plugins.recorder.transport.TransportResolver;
import net.runelite.client.sequence.dispatch.HumanizedInputDispatcher;

/**
 * Chicken farm bot, V3 — uses the recorded {@link
 * net.runelite.client.plugins.recorder.trail.Trail trail framework} for
 * the WALKING phases. The user records two trails once
 * ({@code lumby-bank-to-pen} and {@code pen-to-lumby-bank}); V3 plans
 * over the resulting graph at each phase transition and feeds the path
 * into a {@link TrailWalker}.
 *
 * <p>BANKING and AT_PEN are reused from V2 unchanged: same
 * {@link BankInteraction} primitives, same {@link ChickenCombatLoop}.
 *
 * <p>If the registry is missing the required trails, V3 surfaces a
 * status message and aborts — the user has to record the trails before
 * V3 can run.
 */
@Slf4j
public final class ChickenFarmV3Script
{
    public static final String OUTBOUND_TRAIL_NAME = "lumby-bank-to-pen";
    public static final String RETURN_TRAIL_NAME   = "pen-to-lumby-bank";

    /** Bank stand area — used for "are we at the bank?" check and as the
     *  RETURN destination. Mirrors V2/V1's BANK_AREA. */
    public static final WorldArea BANK_AREA = new WorldArea(3206, 3215, 13, 5, 2);
    /** Pen kill area — passed to the combat loop. Mirrors V2/V1. */
    public static final WorldArea PEN_AREA  = new WorldArea(3225, 3290, 13, 12, 0);

    private static final long TICK_MS = 600;
    private static final long BANK_PACE_MS = 2000;

    public enum State { IDLE, BANKING, OUTBOUND, AT_PEN, RETURN, ABORTED }

    private final Client client;
    private final ClientThread clientThread;
    private final HumanizedInputDispatcher dispatcher;
    private final TrailRegistry registry;
    private final TrailWalker walker;
    private final BankInteraction bank;
    private final ChickenCombatLoop combat;

    private final AtomicReference<State> state = new AtomicReference<>(State.IDLE);
    private final AtomicReference<String> status = new AtomicReference<>("idle");
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<Thread> worker = new AtomicReference<>();
    private long lastBankActionAtMs;
    private TrailPath currentPath;

    public ChickenFarmV3Script(Client client, ClientThread clientThread,
                               HumanizedInputDispatcher dispatcher,
                               TransportResolver resolver,
                               TrailRegistry registry)
    {
        this.client = client;
        this.clientThread = clientThread;
        this.dispatcher = dispatcher;
        this.registry = registry;
        this.walker = new TrailWalker(client, clientThread, dispatcher);
        this.bank = new BankInteraction(client, clientThread, dispatcher);
        this.combat = new ChickenCombatLoop(dispatcher, client, clientThread, PEN_AREA);
    }

    public State state() { return state.get(); }
    public String status() { return status.get(); }
    public int killCount() { return combat.killCount(); }

    public void start()
    {
        if (!running.compareAndSet(false, true)) return;
        registry.load();
        if (registry.byName(OUTBOUND_TRAIL_NAME) == null
            || registry.byName(RETURN_TRAIL_NAME) == null)
        {
            status.set("missing trail — record \"" + OUTBOUND_TRAIL_NAME
                + "\" and \"" + RETURN_TRAIL_NAME + "\"");
            running.set(false);
            return;
        }
        State decided = decideResume();
        log.info("v3: resume → {} (status: {})", decided, status.get());
        setState(decided);
        if (decided == State.IDLE || decided == State.ABORTED)
        {
            running.set(false);
            return;
        }
        Thread t = new Thread(this::tickLoop, "chicken-farm-v3");
        t.setDaemon(true);
        worker.set(t);
        t.start();
    }

    public void stop()
    {
        running.set(false);
        setState(State.IDLE);
        status.set("stopped");
        Thread t = worker.getAndSet(null);
        if (t != null) t.interrupt();
        combat.stop();
    }

    private State decideResume()
    {
        WorldPoint here = onClient(() -> {
            Player p = client.getLocalPlayer();
            return p == null ? null : p.getWorldLocation();
        });
        if (here == null) { status.set("no player — abort"); return State.ABORTED; }
        boolean invFull = onClient(() -> InventoryUtil.isInventoryFull(client));
        boolean invEmpty = onClient(() ->
            InventoryUtil.freeSlotCount(client) >= InventoryUtil.INVENTORY_SIZE);

        if (areaContains(PEN_AREA, here))
        {
            status.set("starting at pen");
            return invFull ? State.RETURN : State.AT_PEN;
        }
        if (areaContains(BANK_AREA, here))
        {
            status.set("starting at bank");
            return invEmpty ? State.OUTBOUND : State.BANKING;
        }
        status.set("starting mid-route");
        return invFull ? State.RETURN : State.OUTBOUND;
    }

    private void tickLoop()
    {
        try
        {
            while (running.get() && !Thread.currentThread().isInterrupted())
            {
                switch (state.get())
                {
                    case BANKING:  tickBanking();   break;
                    case OUTBOUND: tickWalk(true);  break;
                    case AT_PEN:   tickAtPen();     break;
                    case RETURN:   tickWalk(false); break;
                    case ABORTED:
                    case IDLE:
                    default:       running.set(false); break;
                }
                Thread.sleep(TICK_MS);
            }
        }
        catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        finally { running.set(false); }
    }

    private void tickWalk(boolean outbound) throws InterruptedException
    {
        if (currentPath == null) currentPath = planForCurrentPhase(outbound);
        if (currentPath == null) return;
        TrailWalker.Status st = walker.tick(currentPath);
        status.set("walk: " + (outbound ? "outbound" : "return") + " (" + st + ")");
        switch (st)
        {
            case ARRIVED:
                walker.reset();
                currentPath = null;
                setState(outbound ? State.AT_PEN : State.BANKING);
                break;
            case STUCK:
            case ERROR:
                log.warn("v3: walker {} on {} — aborting", st, outbound ? "OUTBOUND" : "RETURN");
                walker.reset();
                currentPath = null;
                setState(State.ABORTED);
                break;
            case IN_PROGRESS:
            default:
                break;
        }
    }

    private TrailPath planForCurrentPhase(boolean outbound)
    {
        WorldPoint here = onClient(() -> {
            Player p = client.getLocalPlayer();
            return p == null ? null : p.getWorldLocation();
        });
        if (here == null) { status.set("no player — abort"); setState(State.ABORTED); return null; }
        WorldPoint dest = outbound
            ? new WorldPoint(PEN_AREA.getX() + PEN_AREA.getWidth() / 2,
                             PEN_AREA.getY() + PEN_AREA.getHeight() / 2,
                             PEN_AREA.getPlane())
            : new WorldPoint(BANK_AREA.getX() + BANK_AREA.getWidth() / 2,
                             BANK_AREA.getY() + BANK_AREA.getHeight() / 2,
                             BANK_AREA.getPlane());
        TrailGraph graph = TrailGraph.build(registry.all());
        TrailPlanner planner = new TrailPlanner(graph);
        Optional<TrailPath> p = planner.plan(here, dest);
        if (p.isEmpty())
        {
            status.set("no trail covers " + (outbound ? "bank→pen" : "pen→bank"));
            setState(State.ABORTED);
            return null;
        }
        return p.get();
    }

    private void tickBanking() throws InterruptedException
    {
        long now = System.currentTimeMillis();
        long since = lastBankActionAtMs == 0
            ? Long.MAX_VALUE : now - lastBankActionAtMs;
        if (since < BANK_PACE_MS)
        {
            status.set("bank — pacing (" + since + "ms)");
            return;
        }
        boolean open = onClient(bank::isBankOpen);
        boolean empty = onClient(() ->
            InventoryUtil.freeSlotCount(client) >= InventoryUtil.INVENTORY_SIZE);
        if (!open && !empty)
        {
            status.set("clicking bank booth");
            if (bank.clickBankBoothRandom()) lastBankActionAtMs = now;
            return;
        }
        if (open && !empty)
        {
            status.set("depositing inventory");
            if (clickDepositInventoryThreadSafe()) lastBankActionAtMs = now;
            return;
        }
        if (open && empty)
        {
            status.set("closing bank");
            if (bank.closeBank()) lastBankActionAtMs = now;
            return;
        }
        status.set("bank closed — heading to pen");
        setState(State.OUTBOUND);
    }

    private void tickAtPen()
    {
        if (onClient(() -> InventoryUtil.isInventoryFull(client)))
        {
            status.set("inventory full — RETURN");
            combat.stop();
            setState(State.RETURN);
            return;
        }
        if (combat.state() == ChickenCombatLoop.State.IDLE)
        {
            status.set("starting combat");
            combat.start();
        }
        else
        {
            status.set("combat: " + combat.latestStatus()
                + " (kills=" + combat.killCount() + ")");
        }
    }

    private boolean clickDepositInventoryThreadSafe() throws InterruptedException
    {
        Rectangle b = onClient(() -> {
            Widget w = client.getWidget(InterfaceID.Bankmain.DEPOSITINV);
            if (w == null || w.isHidden()) return null;
            Rectangle r = w.getBounds();
            return r == null || r.isEmpty() ? null : r;
        });
        if (b == null) return false;
        dispatcher.clickCanvas(b.x + b.width / 2, b.y + b.height / 2);
        return true;
    }

    private void setState(State s) { state.set(s); lastBankActionAtMs = 0L; }

    private static boolean areaContains(WorldArea a, WorldPoint p)
    {
        return p.getPlane() == a.getPlane()
            && p.getX() >= a.getX() && p.getX() < a.getX() + a.getWidth()
            && p.getY() >= a.getY() && p.getY() < a.getY() + a.getHeight();
    }

    private <T> T onClient(Supplier<T> s)
    {
        AtomicReference<T> ref = new AtomicReference<>();
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        clientThread.invokeLater(() -> {
            try { ref.set(s.get()); }
            catch (Throwable th) { log.warn("v3: onClient threw", th); }
            finally { latch.countDown(); }
        });
        try { latch.await(); }
        catch (InterruptedException ie) { Thread.currentThread().interrupt(); return null; }
        return ref.get();
    }
}
```

- [ ] **Step 4: Re-run test**

Run: `./gradlew :runelite-client:test --tests "net.runelite.client.plugins.recorder.scripts.ChickenFarmV3ScriptTest" -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/plugins/recorder/scripts/ChickenFarmV3Script.java \
        runelite-client/src/test/java/net/runelite/client/plugins/recorder/scripts/ChickenFarmV3ScriptTest.java
git commit -m "scripts: add ChickenFarmV3Script (drives trails via TrailWalker)"
```

### Task 22: Wire V3 into RecorderPlugin

**Files:**
- Modify: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/RecorderPlugin.java`

- [ ] **Step 1: Construct + hand to panel**

After the V2 wiring block in `startUp()` (right after the `panel.setChickenFarmV2(chickenFarmV2);` line), append:

```java
        // Chicken farm V3 — same outer FSM as V2 but uses the recorded
        // trail framework for the walking phases. Independent dispatcher
        // so V1/V2/V3 can coexist.
        HumanizedInputDispatcher v3Dispatcher = new HumanizedInputDispatcher(client, clientThread);
        TransportResolver v3Resolver = new TransportResolver(client);
        net.runelite.client.plugins.recorder.scripts.ChickenFarmV3Script chickenFarmV3 =
            new net.runelite.client.plugins.recorder.scripts.ChickenFarmV3Script(
                client, clientThread, v3Dispatcher, v3Resolver, trailRegistry);
        panel.setChickenFarmV3(chickenFarmV3);
```

(Note: `trailRegistry` was created earlier in `startUp` per Task 19. If for any reason the order has the V3 wiring before the registry, move it after.)

- [ ] **Step 2: Don't compile yet — panel setter lands in next task**

Skip compile until Task 23.

### Task 23: Add V3 controls to RecorderPanel

**Files:**
- Modify: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/RecorderPanel.java`

- [ ] **Step 1: Add V3 fields + setter**

Near the existing V2 fields:

```java
    // V3 — trail-network walker version. Lives next to V2 for direct comparison.
    private final JButton v3StartBtn = new JButton("Start");
    private final JButton v3StopBtn = new JButton("Stop");
    private final JLabel v3StatusLabel = new JLabel("V3: idle");
    private final JLabel v3KillsLabel = new JLabel("Kills: 0");
    private net.runelite.client.plugins.recorder.scripts.ChickenFarmV3Script chickenFarmV3;
```

Add the setter:

```java
    public void setChickenFarmV3(net.runelite.client.plugins.recorder.scripts.ChickenFarmV3Script s)
    {
        this.chickenFarmV3 = s;
        SwingUtilities.invokeLater(this::updateV3Controls);
    }

    private void updateV3Controls()
    {
        boolean ready = chickenFarmV3 != null;
        v3StartBtn.setEnabled(ready);
        v3StopBtn.setEnabled(ready);
        if (ready)
        {
            v3StatusLabel.setText("V3: " + chickenFarmV3.status());
            v3KillsLabel.setText("Kills: " + chickenFarmV3.killCount());
        }
    }
```

- [ ] **Step 2: Add V3 section to the Chicken Farm tab**

Find `buildChickenFarmTab()` and append a third section after the V2 section:

```java
        JPanel v3Box = new JPanel();
        v3Box.setLayout(new BoxLayout(v3Box, BoxLayout.Y_AXIS));
        v3Box.setBorder(BorderFactory.createTitledBorder("V3 (recorded trails)"));
        v3Box.setBackground(ColorScheme.DARK_GRAY_COLOR);
        v3Box.add(v3StatusLabel);
        v3Box.add(v3KillsLabel);
        JPanel v3Row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        v3Row.setBackground(ColorScheme.DARK_GRAY_COLOR);
        v3Row.add(v3StartBtn);
        v3Row.add(v3StopBtn);
        v3Box.add(v3Row);
        v3StartBtn.addActionListener(e -> { if (chickenFarmV3 != null) chickenFarmV3.start(); });
        v3StopBtn.addActionListener(e -> { if (chickenFarmV3 != null) chickenFarmV3.stop(); });
        out.add(v3Box);
        // Reuse the existing refreshTimer to update V3 status — it already
        // fires every ~600ms for V1/V2.
```

(Adjust the `out` variable name to whatever the chicken-farm-tab container is named in this codebase.)

In the existing `refreshTimer` loop where V1/V2 status updates happen, add `updateV3Controls();`.

- [ ] **Step 3: Compile + commit Tasks 22 + 23 together**

Run: `./gradlew :runelite-client:compileJava -q`
Expected: PASS.

Run: `./gradlew :runelite-client:test --tests "net.runelite.client.plugins.recorder.*" -q`
Expected: PASS — full plugin test suite.

```bash
git add runelite-client/src/main/java/net/runelite/client/plugins/recorder/RecorderPlugin.java \
        runelite-client/src/main/java/net/runelite/client/plugins/recorder/RecorderPanel.java
git commit -m "scripts: wire ChickenFarmV3 + Trail UI into RecorderPanel"
```

---

## Phase 8 — End-to-end verification

### Task 24: Full test-suite run

- [ ] **Step 1: Run the full recorder test set**

Run: `./gradlew :runelite-client:test --tests "net.runelite.client.plugins.recorder.*" --tests "net.runelite.client.sequence.*" -q`
Expected: PASS — all existing tests + the new ones.

- [ ] **Step 2: Compile-only sanity for the rest**

Run: `./gradlew :runelite-client:compileJava :runelite-client:compileTestJava -q`
Expected: PASS.

- [ ] **Step 3: Commit any test-stabilisation fixes**

(If the full suite uncovered a flake / unrelated issue introduced by an earlier task, fix it and commit. If clean, skip.)

### Task 25: In-game smoke test (manual)

This task is run by the user with the live client. The plan exits as soon as the user confirms the route works end-to-end.

- [ ] **Step 1: Build the client**

Run: `./gradlew :runelite-client:shadowJar -q`
Expected: PASS — produces `runelite-client/build/libs/client-*-shaded.jar`.

- [ ] **Step 2: Launch the client manually**

The user runs the client via their normal launcher (not via Claude). They log in to a test account and stand in Lumby bank.

- [ ] **Step 3: Record the OUTBOUND trail**

In the Recorder panel → Routes tab → Trails section:
1. Type `lumby-bank-to-pen` in the Name field.
2. Click **Record trail**.
3. Walk the entire route from Lumby bank to inside the chicken pen, including: climb-down stairs, walk across the castle yard, cross the stone bridge, walk to the chicken-pen approach, **open the gate**, walk into the pen.
4. Once inside the pen, click **Stop & save**.
5. Confirm the status label shows `Trails: saved "lumby-bank-to-pen" (N events)` where N is roughly 30-60.

- [ ] **Step 4: Inspect the JSON**

Open `~/.runelite/recorder/trails/lumby-bank-to-pen.json`. Confirm:
- Top-level fields: `version`, `name`, `recordedAt`, `events`.
- `events[]` contains alternating `TILE` entries with reasonable x/y progression and at least one `TRANSPORT` entry with `option: "Climb-down"`, one with `option: "Open"`.
- The `Open` entry has a non-zero `targetId`.

- [ ] **Step 5: Record the RETURN trail**

Walk back to the bank, recording under the name `pen-to-lumby-bank`. Click **Stop & save**.

- [ ] **Step 6: Test the planner via the Walk to… field**

In the Trails section, type the bank's centre tile (`3212,3217,2`) into the **Walk to…** field while standing in the pen. Click **Walk to…**. The bot should:
- Rotate the camera.
- Walk to the gate (clicking different parts of the path each click).
- Open the gate.
- Cross the bridge / yard.
- Climb the stairs.
- Walk into the bank area.

Confirm in the status label that the leg counter advances and lands on `Trails: ARRIVED`. **Per the click-variation requirement, watch the cursor on stairs/gates clicks — it should land on a different pixel each time.**

- [ ] **Step 7: Run V3 end-to-end**

In the Recorder panel → Chicken farm tab → V3 (recorded trails) section, click **Start**. The bot should:
- Bank: open the booth, deposit, close.
- Walk to the pen using the recorded trail (with click variation).
- Kill chickens until inventory full.
- Walk back via the recorded return trail.
- Repeat.

Let it loop for ~5 cycles (≈ 10 minutes). Confirm:
- V1 / V2 patterns still work alongside (start them in separate accounts if desired).
- Click locations on the stairs / gate visibly differ across cycles (the user can spot-check by watching the screen).

- [ ] **Step 8: Commit any tweaks discovered during smoke**

If the smoke test surfaces specific bugs (e.g. the planner's snap radius is too tight, the walker's pickAheadTile window is too back-loaded for short legs), file them as follow-up commits. The plan is "done" once the user confirms a 5-cycle clean V3 run.

---

## Done criteria

- [ ] All unit tests pass (`./gradlew :runelite-client:test`).
- [ ] User has recorded `lumby-bank-to-pen.json` and `pen-to-lumby-bank.json` under `~/.runelite/recorder/trails/`.
- [ ] V3 completes ≥ 5 bank ↔ pen cycles without intervention.
- [ ] User has visually confirmed click pixel-variation on the stairs and the pen gate (no bolt-centre repeats).

## Risks captured during planning

- The dispatcher's `rotateCameraToward` jitter is ±26°, not the 120-150° referenced in the spec. The smoke test will reveal if 26° is too narrow (camera always faces the destination); if so, widen the jitter range in a follow-up. Out of scope for this plan unless smoke fails for that specific reason.
- `pickAheadTile` biases toward the back half of the ahead window. On very short legs (< 4 tiles), this collapses to "always pick the last tile" — same as V2's behaviour, no regression.
- `MIN_REPEAT_PX = 6` may be too aggressive for tiny widget bounds (deposit-inv button is ~30 px wide, so 6 px is fine). If a click on a tight widget starves the sampler, the existing fallback ("accept the last sample") kicks in; verify in the smoke test.
- Two consecutive `Trail.save()` calls with the same name silently overwrite — documented behaviour. The panel could surface a confirm-overwrite dialog later; not in scope.
