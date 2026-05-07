package net.runelite.client.plugins.recorder.worldmap;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameTick;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.recorder.RecorderConfig;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/** V2 minimap debug overlay. Paints WorldMemory state on the minimap so
 *  the seed pass + acceptance tests can be visually verified.
 *
 *  <p>Default colours (per spec):
 *  <ul>
 *    <li>green — known walkable tiles</li>
 *    <li>yellow — transport endpoints</li>
 *    <li>blue — active planned route</li>
 *  </ul>
 *  Toggleable extras (off by default): red blocked, orange stale,
 *  purple entities.
 *
 *  <p>Performance: cached point lists are recomputed on each GameTick
 *  (~600ms cadence). {@link #render} only iterates the cached list
 *  and draws — no per-frame work touches the WorldMemory indices.
 *  At most {@link #MAX_POINTS_PER_LAYER} points are kept per layer
 *  so a busy region cannot spike paint time.
 *
 *  <p>Wiring is indirect: the V2 executor pushes its current planned
 *  route via {@link #publishActiveRoute}; the panel "Clear debug
 *  overlay route" button calls {@link #clearActiveRoute}. Both no-op
 *  when no overlay is registered. */
public final class WorldMapMinimapOverlay extends Overlay
{
    private static final int MAX_POINTS_PER_LAYER = 256;
    /** Half-width of the visible minimap area in tiles, in WorldPoint
     *  space. The minimap displays roughly 21×21 tiles around the
     *  player; we scan a slightly bigger window so tiles near the edge
     *  still appear when the player moves. */
    private static final int MINIMAP_VISIBLE_RADIUS = 12;

    private static final Color WALKABLE = new Color(60, 220, 90, 200);
    private static final Color BLOCKED = new Color(220, 60, 60, 180);
    private static final Color TRANSPORT = new Color(240, 220, 60, 230);
    private static final Color ROUTE = new Color(60, 140, 255, 230);
    private static final Color STALE = new Color(255, 150, 30, 220);
    private static final Color ENTITY = new Color(190, 80, 220, 220);

    private static final BasicStroke STROKE_THIN = new BasicStroke(1f);
    private static final BasicStroke STROKE_THICK = new BasicStroke(2f);

    private static final AtomicReference<WorldMapMinimapOverlay> LIVE = new AtomicReference<>();

    private final Client client;
    private final RecorderConfig config;
    private final MapStore mapStore;
    private final EntityIndex entityIndex;
    private final TransportIndex transportIndex;

    /** Cached projection inputs. The lists themselves are immutable
     *  snapshots; we swap whole references atomically on each tick so
     *  render() never sees a half-built list. WorldPoints (not screen
     *  Points) are cached because the camera/zoom can change between
     *  tick and render — we project per-frame using the live
     *  Perspective state. */
    private final AtomicReference<Snapshot> cached =
        new AtomicReference<>(Snapshot.EMPTY);
    private final AtomicReference<List<WorldPoint>> activeRoute =
        new AtomicReference<>(Collections.emptyList());

    public WorldMapMinimapOverlay(Client client, RecorderConfig config,
                                   MapStore mapStore, EntityIndex entityIndex,
                                   TransportIndex transportIndex)
    {
        this.client = client;
        this.config = config;
        this.mapStore = mapStore;
        this.entityIndex = entityIndex;
        this.transportIndex = transportIndex;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
        setPriority(Overlay.PRIORITY_LOW);
        LIVE.set(this);
    }

    /** Called by the plugin on shutdown. Drops the static handle so any
     *  in-flight V2 executor publish becomes a no-op. */
    public void detach()
    {
        LIVE.compareAndSet(this, null);
        cached.set(Snapshot.EMPTY);
        activeRoute.set(Collections.emptyList());
    }

    /** Push the V2 executor's currently planned route. Pass {@code null}
     *  or an empty list to clear. No-op if no overlay is registered. */
    public static void publishActiveRoute(@Nullable List<WorldPoint> route)
    {
        WorldMapMinimapOverlay o = LIVE.get();
        if (o == null) return;
        o.activeRoute.set(route == null ? Collections.emptyList()
            : List.copyOf(route));
    }

    /** Convenience for the panel "Clear debug overlay route" button. */
    public static void clearActiveRoute()
    {
        publishActiveRoute(null);
    }

    @Subscribe
    public void onGameTick(GameTick tick)
    {
        if (!config.showWorldMapOverlay())
        {
            // Clear cache when the overlay is off so re-enabling shows
            // fresh data on the next tick rather than whatever was
            // cached at toggle-off time.
            if (cached.get() != Snapshot.EMPTY) cached.set(Snapshot.EMPTY);
            return;
        }
        Player self = client.getLocalPlayer();
        if (self == null || self.getWorldLocation() == null)
        {
            cached.set(Snapshot.EMPTY);
            return;
        }
        WorldPoint player = self.getWorldLocation();
        Snapshot s = recompute(player);
        cached.set(s);
    }

    @Override
    public Dimension render(Graphics2D g)
    {
        if (!config.showWorldMapOverlay()) return null;
        WorldView wv = client.getTopLevelWorldView();
        if (wv == null) return null;
        Snapshot s = cached.get();
        if (s == Snapshot.EMPTY) return null;

        g.setStroke(STROKE_THIN);

        if (config.overlayShowBlocked())
        {
            g.setColor(BLOCKED);
            for (WorldPoint p : s.blocked) drawTile(g, wv, p);
        }
        g.setColor(WALKABLE);
        for (WorldPoint p : s.walkable) drawTile(g, wv, p);

        g.setStroke(STROKE_THICK);
        g.setColor(TRANSPORT);
        for (WorldPoint p : s.transports) drawTile(g, wv, p);

        if (config.overlayShowEntities())
        {
            g.setColor(ENTITY);
            for (WorldPoint p : s.entities) drawTile(g, wv, p);
        }
        if (config.overlayShowStale())
        {
            g.setColor(STALE);
            for (WorldPoint p : s.stale) drawTile(g, wv, p);
        }

        List<WorldPoint> route = activeRoute.get();
        if (!route.isEmpty())
        {
            g.setColor(ROUTE);
            for (WorldPoint p : route) drawTile(g, wv, p);
        }
        return null;
    }

    private void drawTile(Graphics2D g, WorldView wv, WorldPoint wp)
    {
        if (wp.getPlane() != wv.getPlane()) return;
        LocalPoint lp = LocalPoint.fromWorld(wv, wp);
        if (lp == null) return;
        int lx = lp.getX() & -Perspective.LOCAL_TILE_SIZE;
        int ly = lp.getY() & -Perspective.LOCAL_TILE_SIZE;
        Point mp1 = Perspective.localToMinimap(client, new LocalPoint(lx, ly, wv.getId()));
        Point mp2 = Perspective.localToMinimap(client,
            new LocalPoint(lx, ly + Perspective.LOCAL_TILE_SIZE, wv.getId()));
        Point mp3 = Perspective.localToMinimap(client,
            new LocalPoint(lx + Perspective.LOCAL_TILE_SIZE,
                ly + Perspective.LOCAL_TILE_SIZE, wv.getId()));
        Point mp4 = Perspective.localToMinimap(client,
            new LocalPoint(lx + Perspective.LOCAL_TILE_SIZE, ly, wv.getId()));
        if (mp1 == null || mp2 == null || mp3 == null || mp4 == null) return;
        Polygon poly = new Polygon();
        poly.addPoint(mp1.getX(), mp1.getY());
        poly.addPoint(mp2.getX(), mp2.getY());
        poly.addPoint(mp3.getX(), mp3.getY());
        poly.addPoint(mp4.getX(), mp4.getY());
        g.drawPolygon(poly);
    }

    /** Build a fresh point-list snapshot for the current player tile.
     *  All scans use the WorldMemory indices (no live Scene reads), so
     *  this is safe to call from the GameTick handler without further
     *  marshalling. */
    private Snapshot recompute(WorldPoint player)
    {
        Snapshot s = new Snapshot();
        int plane = player.getPlane();
        int playerRegionId = RegionIds.regionIdFor(player.getX(), player.getY());

        // Pull tiles from the player's region + the 8 neighbours so the
        // overlay still has data when the player straddles a region
        // boundary. With the radius cap below most points fall in just
        // 1-2 of these regions.
        int rx = (playerRegionId >> 8) & 0xff;
        int ry = playerRegionId & 0xff;
        for (int dx = -1; dx <= 1 && needMoreTiles(s); dx++)
        {
            for (int dy = -1; dy <= 1 && needMoreTiles(s); dy++)
            {
                int regionId = (((rx + dx) & 0xff) << 8) | ((ry + dy) & 0xff);
                RegionChunkSnapshot snap = mapStore.snapshotFor(regionId);
                if (snap == null) continue;
                for (RegionChunkSnapshot.TileEntry t : snap.tiles())
                {
                    if (t.plane != plane) continue;
                    if (Math.abs(t.x - player.getX()) > MINIMAP_VISIBLE_RADIUS) continue;
                    if (Math.abs(t.y - player.getY()) > MINIMAP_VISIBLE_RADIUS) continue;
                    boolean isBlocked =
                        (t.movement & CollisionDataFlag.BLOCK_MOVEMENT_FULL) != 0;
                    if (isBlocked)
                    {
                        if (s.blocked.size() < MAX_POINTS_PER_LAYER)
                            s.blocked.add(new WorldPoint(t.x, t.y, t.plane));
                    }
                    else if (s.walkable.size() < MAX_POINTS_PER_LAYER)
                    {
                        s.walkable.add(new WorldPoint(t.x, t.y, t.plane));
                    }
                }
            }
        }

        // Transport endpoints — every edge whose fromTile is in range
        // (the toTile is shown by the active route if it's on the path).
        for (TransportEdge e : transportIndex.getAll())
        {
            WorldPoint from = e.fromTile();
            if (Math.abs(from.getX() - player.getX()) > MINIMAP_VISIBLE_RADIUS) continue;
            if (Math.abs(from.getY() - player.getY()) > MINIMAP_VISIBLE_RADIUS) continue;
            if (from.getPlane() != plane) continue;
            s.transports.add(from);
            if (s.transports.size() >= MAX_POINTS_PER_LAYER) break;
        }

        // Entity sightings — pull from the player's region + neighbours.
        for (int dx = -1; dx <= 1 && s.entities.size() < MAX_POINTS_PER_LAYER; dx++)
        {
            for (int dy = -1; dy <= 1 && s.entities.size() < MAX_POINTS_PER_LAYER; dy++)
            {
                int regionId = (((rx + dx) & 0xff) << 8) | ((ry + dy) & 0xff);
                addEntities(s.entities, entityIndex.npcsInRegion(regionId), plane, player);
                addEntities(s.entities, entityIndex.objectsInRegion(regionId), plane, player);
            }
        }
        return s;
    }

    private static boolean needMoreTiles(Snapshot s)
    {
        return s.walkable.size() < MAX_POINTS_PER_LAYER
            || s.blocked.size() < MAX_POINTS_PER_LAYER;
    }

    private static void addEntities(List<WorldPoint> out,
                                    List<EntitySighting> sightings, int plane,
                                    WorldPoint player)
    {
        for (EntitySighting s : sightings)
        {
            WorldPoint t = s.lastTile;
            if (t.getPlane() != plane) continue;
            if (Math.abs(t.getX() - player.getX()) > MINIMAP_VISIBLE_RADIUS) continue;
            if (Math.abs(t.getY() - player.getY()) > MINIMAP_VISIBLE_RADIUS) continue;
            out.add(t);
            if (out.size() >= MAX_POINTS_PER_LAYER) return;
        }
    }

    /** Immutable cached snapshot of all rendered point lists. Swapped
     *  atomically per tick so render() always sees a coherent set. */
    private static final class Snapshot
    {
        static final Snapshot EMPTY = new Snapshot();
        final List<WorldPoint> walkable = new ArrayList<>();
        final List<WorldPoint> blocked = new ArrayList<>();
        final List<WorldPoint> transports = new ArrayList<>();
        final List<WorldPoint> entities = new ArrayList<>();
        final List<WorldPoint> stale = new ArrayList<>();   // populated in V2.5+
    }
}
