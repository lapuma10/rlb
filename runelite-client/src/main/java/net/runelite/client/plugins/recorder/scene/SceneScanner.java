package net.runelite.client.plugins.recorder.scene;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Player;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.TileItem;
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

    /** Find the closest TileItem (ground item) within {@code radius}
     *  tiles of the player matching {@code itemId}. Returns null if
     *  none in range. Per OSRS engine, ground items are NOT
     *  GameObjects — they live on a tile's
     *  {@link Tile#getGroundItems()} list. */
    public Match findTileItemById(int itemId, int radius)
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
                List<TileItem> items = t.getGroundItems();
                if (items == null) continue;
                for (TileItem ti : items)
                {
                    if (ti == null) continue;
                    if (ti.getId() != itemId) continue;
                    int cheb = Math.max(Math.abs(dx), Math.abs(dy));
                    if (cheb >= bestDist) continue;
                    WorldPoint wp = new WorldPoint(
                        here.getX() + dx, here.getY() + dy, plane);
                    bestDist = cheb;
                    best = new Match(wp, null, ti);
                }
            }
        }
        return best;
    }
}
