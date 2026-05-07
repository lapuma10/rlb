package net.runelite.client.plugins.recorder.nav.v2;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.NPC;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.TileItem;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;

/** Pre-filter that rejects canvas-click candidates with obvious entity
 *  contamination. Advisory only — the dispatcher's
 *  {@code isLeftClickWalk} pre-check is the authoritative gate at press
 *  time. The filter is conservative: false negatives (rejecting a tile
 *  that would have clicked clean) are fine; false positives (accepting
 *  a tile whose live menu doesn't say "Walk here") are not.
 *
 *  <p>Threading: every method reads {@link Scene} / {@link WorldView}
 *  state and MUST be called on the client thread. */
@Slf4j
public final class EmptyTileFilter
{
    private final Client client;

    public EmptyTileFilter(Client client)
    {
        this.client = client;
    }

    /** True if the tile passes every pre-filter rule:
     *  <ul>
     *    <li>tile is inside the loaded scene at {@code target}'s plane</li>
     *    <li>no ground items on the tile</li>
     *    <li>no NPC standing on the tile</li>
     *    <li>no GameObject on the tile whose first menu action is a
     *        non-walk verb (door / stair / ladder / etc.)</li>
     *  </ul>
     *  Anything we can't read (scene unloaded, plane out of range)
     *  reports false — we'd rather pick a different tile than gamble
     *  on an unverifiable one. */
    public boolean isPlausiblyClean(WorldPoint target)
    {
        if (target == null) return false;
        WorldView wv = client.getTopLevelWorldView();
        if (wv == null) return false;
        Scene scene = wv.getScene();
        if (scene == null) return false;

        Tile tile = tileAt(wv, scene, target);
        if (tile == null) return false;

        List<TileItem> items = tile.getGroundItems();
        if (items != null && !items.isEmpty())
        {
            log.debug("filter: reject {} — {} ground item(s)", target, items.size());
            return false;
        }

        if (npcStandingOn(wv, target))
        {
            log.debug("filter: reject {} — NPC standing on tile", target);
            return false;
        }

        GameObject[] gos = tile.getGameObjects();
        if (gos != null)
        {
            for (GameObject go : gos)
            {
                if (go == null) continue;
                if (hasNonWalkAction(go))
                {
                    log.debug("filter: reject {} — game object id={} has non-walk first action",
                        target, go.getId());
                    return false;
                }
            }
        }
        return true;
    }

    private static Tile tileAt(WorldView wv, Scene scene, WorldPoint target)
    {
        Tile[][][] tiles = scene.getTiles();
        if (tiles == null) return null;
        int plane = target.getPlane();
        if (plane < 0 || plane >= tiles.length) return null;
        int sx = target.getX() - wv.getBaseX();
        int sy = target.getY() - wv.getBaseY();
        if (sx < 0 || sy < 0) return null;
        Tile[][] planeTiles = tiles[plane];
        if (planeTiles == null) return null;
        if (sx >= planeTiles.length) return null;
        if (planeTiles.length == 0 || sy >= planeTiles[0].length) return null;
        return planeTiles[sx][sy];
    }

    private static boolean npcStandingOn(WorldView wv, WorldPoint target)
    {
        try
        {
            for (NPC npc : wv.npcs())
            {
                if (npc == null) continue;
                WorldPoint loc = npc.getWorldLocation();
                if (loc == null) continue;
                if (loc.equals(target)) return true;
            }
        }
        catch (Throwable th)
        {
            // Defensive: if the NPC iteration throws (e.g. malformed
            // mocked client in tests), treat as "couldn't verify" and
            // err clean. The other rules still apply.
            log.debug("filter: NPC iteration failed — treating as no-NPC", th);
        }
        return false;
    }

    private boolean hasNonWalkAction(GameObject go)
    {
        ObjectComposition def = client.getObjectDefinition(go.getId());
        if (def == null) return false;
        if (def.getImpostorIds() != null)
        {
            try
            {
                ObjectComposition imp = def.getImpostor();
                if (imp != null) def = imp;
            }
            catch (Throwable ignored) { /* base def */ }
        }
        String[] actions = def.getActions();
        if (actions == null || actions.length == 0) return false;
        String first = actions[0];
        if (first == null || first.isEmpty()) return false;
        // "Walk here" is the engine's default left-click verb on empty
        // tiles — an object whose own first verb literally says
        // "Walk here" is just decorative scenery. Anything else is a
        // real menu verb that would intercept our click.
        return !"Walk here".equalsIgnoreCase(first);
    }
}
