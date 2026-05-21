package net.runelite.client.plugins.recorder.scene;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.DecorativeObject;
import net.runelite.api.GameObject;
import net.runelite.api.GroundObject;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Player;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.TileItem;
import net.runelite.api.WallObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;

/**
 * Generic scene-scan helpers — find a GameObject by composition name,
 * or a TileItem (ground item) by item id, within a Chebyshev radius
 * around the player.
 *
 * <p>Lifted from the cooking bot's primitives. Mining, fishing, and
 * any future "interact with the closest X" script can reuse the same
 * scan rather than reimplementing the spiral / impostor unwrapping.
 *
 * <p><b>Threading:</b> all scan methods read scene state and MUST be
 * called from the client thread. The cooking + mining workers hop via
 * their existing {@code onClient} helpers.
 */
@Slf4j
public final class SceneScanner
{
    /** Result of a scan. {@link #tile} is always set on a successful
     *  match; {@link #gameObject} or {@link #tileItem} is set depending
     *  on which kind was scanned. */
    public static final class Match
    {
        public final WorldPoint tile;
        public final GameObject gameObject;
        public final TileItem tileItem;

        public Match(WorldPoint t, GameObject go, TileItem ti)
        { this.tile = t; this.gameObject = go; this.tileItem = ti; }
    }

    private final Client client;

    public SceneScanner(Client client) { this.client = client; }

    /** Find the closest GameObject within {@code radius} tiles of the
     *  player whose composition name (after impostor unwrap) matches
     *  {@code namePattern} case-insensitively. Returns null if none in
     *  range / scene unloaded / player missing. */
    public Match findGameObjectByName(String namePattern, int radius)
    {
        Player self = client.getLocalPlayer();
        if (self == null) return null;
        WorldPoint here = self.getWorldLocation();
        if (here == null) return null;
        WorldView wv = client.getTopLevelWorldView();
        if (wv == null) return null;
        Scene scene = wv.getScene();
        if (scene == null) return null;
        Tile[][][] tiles = scene.getTiles();
        int plane = here.getPlane();
        if (tiles == null || plane < 0 || plane >= tiles.length) return null;
        Tile[][] planeTiles = tiles[plane];

        int hereSx = here.getX() - wv.getBaseX();
        int hereSy = here.getY() - wv.getBaseY();

        Match best = null;
        int bestDist = Integer.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++)
        {
            for (int dy = -radius; dy <= radius; dy++)
            {
                int sx = hereSx + dx, sy = hereSy + dy;
                if (sx < 0 || sy < 0
                    || sx >= planeTiles.length
                    || sy >= planeTiles[0].length) continue;
                Tile t = planeTiles[sx][sy];
                if (t == null) continue;
                GameObject[] gos = t.getGameObjects();
                if (gos == null) continue;
                for (GameObject go : gos)
                {
                    if (go == null) continue;
                    ObjectComposition def = client.getObjectDefinition(go.getId());
                    if (def == null) continue;
                    if (def.getImpostorIds() != null)
                    {
                        try
                        {
                            ObjectComposition imp = def.getImpostor();
                            if (imp != null) def = imp;
                        }
                        catch (Throwable ignored) { /* base def */ }
                    }
                    String name = def.getName();
                    if (name == null) continue;
                    if (!name.equalsIgnoreCase(namePattern)) continue;
                    int cheb = Math.max(Math.abs(dx), Math.abs(dy));
                    if (cheb >= bestDist) continue;
                    LocalPoint lp = go.getLocalLocation();
                    if (lp == null) continue;
                    WorldPoint wp = WorldPoint.fromLocal(client, lp);
                    bestDist = cheb;
                    best = new Match(wp, go, null);
                }
            }
        }
        return best;
    }

    /** Find the closest GameObject within {@code radius} tiles of the
     *  player whose {@code getId()} equals {@code objectId}. Returns null
     *  if none in range / scene unloaded / player missing.
     *
     *  <p>Use this when several scene objects share the same display name
     *  (e.g. multiple "Door" GameObjects with different rotations / states
     *  in close proximity — Ernest's basement lever puzzle has eight
     *  distinct doors named "Door" within ~10 tiles of each other, so
     *  name-based lookup picks the wrong one). The OSBot reference scripts
     *  use the same id-based disambiguation. */
    public Match findGameObjectById(int objectId, int radius)
    {
        Player self = client.getLocalPlayer();
        if (self == null) return null;
        WorldPoint here = self.getWorldLocation();
        if (here == null) return null;
        WorldView wv = client.getTopLevelWorldView();
        if (wv == null) return null;
        Scene scene = wv.getScene();
        if (scene == null) return null;
        Tile[][][] tiles = scene.getTiles();
        int plane = here.getPlane();
        if (tiles == null || plane < 0 || plane >= tiles.length) return null;
        Tile[][] planeTiles = tiles[plane];

        int hereSx = here.getX() - wv.getBaseX();
        int hereSy = here.getY() - wv.getBaseY();

        Match best = null;
        int bestDist = Integer.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++)
        {
            for (int dy = -radius; dy <= radius; dy++)
            {
                int sx = hereSx + dx, sy = hereSy + dy;
                if (sx < 0 || sy < 0
                    || sx >= planeTiles.length
                    || sy >= planeTiles[0].length) continue;
                Tile t = planeTiles[sx][sy];
                if (t == null) continue;
                GameObject[] gos = t.getGameObjects();
                if (gos == null) continue;
                for (GameObject go : gos)
                {
                    if (go == null) continue;
                    if (go.getId() != objectId) continue;
                    int cheb = Math.max(Math.abs(dx), Math.abs(dy));
                    if (cheb >= bestDist) continue;
                    LocalPoint lp = go.getLocalLocation();
                    if (lp == null) continue;
                    WorldPoint wp = WorldPoint.fromLocal(client, lp);
                    bestDist = cheb;
                    best = new Match(wp, go, null);
                }
            }
        }
        return best;
    }

    /** Find the closest object on the loaded scene matching {@code objectId},
     *  searching ALL four scene-object kinds —
     *  {@link GameObject}, {@link WallObject}, {@link DecorativeObject},
     *  {@link GroundObject} — within {@code radius} tiles (Chebyshev) of
     *  the player. Returns the object's tile {@link WorldPoint}, or null
     *  if no match in range.
     *
     *  <p>This is the right lookup for "click that specific obstacle by
     *  id" use cases (agility courses, scripted shortcuts, recorded
     *  transports) where the authoring data only knows the object id
     *  and a rough proximity. Trusting per-tile authoring is brittle —
     *  walls and decoratives in particular routinely attach to a
     *  neighboring tile than the one a human would point at. Scanning
     *  by id finds the actual rendered instance, then the dispatcher's
     *  {@link net.runelite.client.plugins.recorder.transport.TransportResolver}
     *  re-resolves it against the same tile when building the click.
     *
     *  <p>Plane comes from the player; mixed-plane obstacles (e.g. a
     *  staircase the player isn't on) are not returned because they
     *  wouldn't be clickable from the player's current floor anyway. */
    @javax.annotation.Nullable
    public WorldPoint findObjectTileById(int objectId, int radius)
    {
        Player self = client.getLocalPlayer();
        if (self == null) return null;
        WorldPoint here = self.getWorldLocation();
        if (here == null) return null;
        WorldView wv = client.getTopLevelWorldView();
        if (wv == null) return null;
        Scene scene = wv.getScene();
        if (scene == null) return null;
        Tile[][][] tiles = scene.getTiles();
        int plane = here.getPlane();
        if (tiles == null || plane < 0 || plane >= tiles.length) return null;
        Tile[][] planeTiles = tiles[plane];

        int hereSx = here.getX() - wv.getBaseX();
        int hereSy = here.getY() - wv.getBaseY();

        WorldPoint best = null;
        int bestDist = Integer.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++)
        {
            for (int dy = -radius; dy <= radius; dy++)
            {
                int sx = hereSx + dx, sy = hereSy + dy;
                if (sx < 0 || sy < 0
                    || sx >= planeTiles.length
                    || sy >= planeTiles[0].length) continue;
                Tile t = planeTiles[sx][sy];
                if (t == null) continue;
                int cheb = Math.max(Math.abs(dx), Math.abs(dy));
                if (cheb >= bestDist) continue;

                // GameObject[] — tightropes, gaps, crates, most interactive scenery.
                GameObject[] gos = t.getGameObjects();
                if (gos != null)
                {
                    for (GameObject go : gos)
                    {
                        if (go == null || go.getId() != objectId) continue;
                        LocalPoint lp = go.getLocalLocation();
                        if (lp == null) continue;
                        best = WorldPoint.fromLocal(client, lp);
                        bestDist = cheb;
                        break;
                    }
                    if (bestDist == cheb) continue;   // already matched here
                }
                // WallObject — rough wall, narrow wall, doors, gates.
                WallObject wo = t.getWallObject();
                if (wo != null && wo.getId() == objectId)
                {
                    LocalPoint lp = wo.getLocalLocation();
                    if (lp != null)
                    {
                        best = WorldPoint.fromLocal(client, lp);
                        bestDist = cheb;
                        continue;
                    }
                }
                // DecorativeObject — rope/ladder decorations on some courses.
                DecorativeObject deco = t.getDecorativeObject();
                if (deco != null && deco.getId() == objectId)
                {
                    LocalPoint lp = deco.getLocalLocation();
                    if (lp != null)
                    {
                        best = WorldPoint.fromLocal(client, lp);
                        bestDist = cheb;
                        continue;
                    }
                }
                // GroundObject — some platforms / floor obstacles.
                GroundObject ground = t.getGroundObject();
                if (ground != null && ground.getId() == objectId)
                {
                    LocalPoint lp = ground.getLocalLocation();
                    if (lp != null)
                    {
                        best = WorldPoint.fromLocal(client, lp);
                        bestDist = cheb;
                    }
                }
            }
        }
        return best;
    }

    /** Find the closest TileItem (ground item) within {@code radius}
     *  tiles of the player matching {@code itemId}. Returns null if
     *  none in range. Per OSRS engine, ground items are NOT
     *  GameObjects — they live on a tile's
     *  {@link Tile#getGroundItems()} list. */
    public Match findTileItemById(int itemId, int radius)
    {
        return findTileItemByIdRandomNear(itemId, radius, 0);
    }

    /** Like {@link #findTileItemById} but, when multiple matches are in
     *  range, picks uniformly at random among those within
     *  {@code jitter} tiles (Chebyshev) of the closest match. With
     *  {@code jitter == 0} this collapses to strict-closest. With a
     *  small positive jitter (e.g. 2), repeat trips to a cluster of
     *  identical ground items (log piles at a cooking spot) won't
     *  always lock onto the same tile — kills the bot-tell of always
     *  lighting on log #1. */
    public Match findTileItemByIdRandomNear(int itemId, int radius, int jitter)
    {
        Player self = client.getLocalPlayer();
        if (self == null) return null;
        WorldPoint here = self.getWorldLocation();
        if (here == null) return null;
        WorldView wv = client.getTopLevelWorldView();
        if (wv == null) return null;
        Scene scene = wv.getScene();
        if (scene == null) return null;
        Tile[][][] tiles = scene.getTiles();
        int plane = here.getPlane();
        if (tiles == null || plane < 0 || plane >= tiles.length) return null;
        Tile[][] planeTiles = tiles[plane];

        int hereSx = here.getX() - wv.getBaseX();
        int hereSy = here.getY() - wv.getBaseY();

        java.util.List<Match> matches = new java.util.ArrayList<>();
        java.util.List<Integer> dists = new java.util.ArrayList<>();
        int bestDist = Integer.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++)
        {
            for (int dy = -radius; dy <= radius; dy++)
            {
                int sx = hereSx + dx, sy = hereSy + dy;
                if (sx < 0 || sy < 0
                    || sx >= planeTiles.length
                    || sy >= planeTiles[0].length) continue;
                Tile t = planeTiles[sx][sy];
                if (t == null) continue;
                List<TileItem> items = t.getGroundItems();
                if (items == null) continue;
                for (TileItem ti : items)
                {
                    if (ti == null) continue;
                    if (ti.getId() != itemId) continue;
                    int cheb = Math.max(Math.abs(dx), Math.abs(dy));
                    WorldPoint wp = new WorldPoint(
                        here.getX() + dx, here.getY() + dy, plane);
                    matches.add(new Match(wp, null, ti));
                    dists.add(cheb);
                    if (cheb < bestDist) bestDist = cheb;
                }
            }
        }
        if (matches.isEmpty()) return null;
        if (jitter <= 0 || matches.size() == 1)
        {
            // Collect all matches at exactly bestDist and pick one randomly
            // so that ties (multiple spawns equidistant from the player) don't
            // always resolve to the same scan-order winner.
            java.util.List<Match> atBest = new java.util.ArrayList<>();
            for (int i = 0; i < matches.size(); i++)
            {
                if (dists.get(i) == bestDist) atBest.add(matches.get(i));
            }
            if (atBest.isEmpty()) return matches.get(0);
            return atBest.get((int) (Math.random() * atBest.size()));
        }
        // Gather all matches within bestDist + jitter tiles, then pick
        // uniformly. Same closest band → no surprise picks far away.
        int cutoff = bestDist + jitter;
        java.util.List<Match> nearBest = new java.util.ArrayList<>();
        for (int i = 0; i < matches.size(); i++)
        {
            if (dists.get(i) <= cutoff) nearBest.add(matches.get(i));
        }
        if (nearBest.isEmpty()) return matches.get(0);
        int idx = (int) (Math.random() * nearBest.size());
        return nearBest.get(idx);
    }
}
