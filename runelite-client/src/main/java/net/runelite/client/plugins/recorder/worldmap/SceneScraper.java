package net.runelite.client.plugins.recorder.worldmap;

import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.CollisionData;
import net.runelite.api.DecorativeObject;
import net.runelite.api.GameObject;
import net.runelite.api.GroundObject;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Player;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.TileObject;
import net.runelite.api.WallObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;

/**
 * Client-thread scraper: reads the 18×18 tile window centered on the player
 * into {@link RegionChunkBuilder}s (one per touched region), then publishes
 * them atomically via {@link MapStore}.
 *
 * <p>Also enumerates game objects in the window and records any with at least
 * one non-null action to both the builder and the {@link EntityIndex}.
 *
 * <p><b>Threading:</b> MUST be called only on the client thread.
 * The scrape is bounded by {@link WorldMemoryConfig#scrapeBudgetNanos}; if the
 * budget expires mid-scan the scrape is abandoned and no {@code publish} is
 * called for any region.
 */
@Slf4j
public final class SceneScraper
{
    private final Client client;
    private final MapStore mapStore;
    private final EntityIndex entityIndex;
    private final WorldMemoryConfig config;

    public SceneScraper(Client client, MapStore mapStore,
                        EntityIndex entityIndex, WorldMemoryConfig config)
    {
        this.client = client;
        this.mapStore = mapStore;
        this.entityIndex = entityIndex;
        this.config = config;
    }

    /**
     * Entry point — called from {@code RecorderPlugin}'s
     * {@code @Subscribe onGameTick} handler (Phase 4 wiring), already on
     * the client thread.
     *
     * @param wv  the top-level world view; null means not yet loaded
     * @param now epoch-ms timestamp for sighting records
     */
    public void scan(WorldView wv, long now)
    {
        // Step 1: guard — skip instance regions entirely (they don't persist).
        if (wv == null) return;
        if (wv.isInstance()) return;

        Player self = client.getLocalPlayer();
        if (self == null) return;
        WorldPoint here = self.getWorldLocation();
        if (here == null) return;

        Scene scene = wv.getScene();
        if (scene == null) return;

        CollisionData[] collisionMaps = wv.getCollisionMaps();
        if (collisionMaps == null) return;

        Tile[][][] sceneTiles = scene.getTiles();
        if (sceneTiles == null) return;

        int baseX = wv.getBaseX();
        int baseY = wv.getBaseY();
        int radius = config.scrapeWindowRadius;

        long startNs = System.nanoTime();

        // Step 2: collect builders keyed by regionId.
        // We build them all first, then publish only if we finish within budget.
        Map<Integer, RegionChunkBuilder> builders = new HashMap<>();

        int playerPlane = here.getPlane();

        for (int dx = -radius; dx <= radius; dx++)
        {
            for (int dy = -radius; dy <= radius; dy++)
            {
                // Budget check — abort if we've run over.
                if (System.nanoTime() - startNs > config.scrapeBudgetNanos)
                {
                    log.debug("[worldmap] scrape budget exceeded, abandoning scrape");
                    return;
                }

                int worldX = here.getX() + dx;
                int worldY = here.getY() + dy;

                int sceneX = worldX - baseX;
                int sceneY = worldY - baseY;

                // Only process tiles within the loaded 104×104 scene array.
                if (sceneX < 0 || sceneY < 0
                    || sceneX >= wv.getSizeX()
                    || sceneY >= wv.getSizeY()) continue;

                // Step 3: group by region, get/create builder.
                int regionId = RegionIds.regionIdFor(worldX, worldY);
                RegionChunkBuilder builder = builders.computeIfAbsent(
                    regionId, mapStore::builderFor);

                // Step 4: write collision flags for all planes.
                for (int plane = 0; plane < collisionMaps.length; plane++)
                {
                    CollisionData cd = collisionMaps[plane];
                    if (cd == null) continue;
                    int[][] flags = cd.getFlags();
                    if (flags == null
                        || sceneX >= flags.length
                        || sceneY >= flags[sceneX].length) continue;

                    int movement = flags[sceneX][sceneY];
                    // Skip the engine's "off-scene" sentinel (0x00ffffff —
                    // every block bit set). The scraper sees this for tiles
                    // inside the scrape window but outside the actively-
                    // loaded scene chunks (the engine returns it for any
                    // tile whose collision data isn't truly resident). If
                    // we wrote it, the planner would treat those tiles as
                    // hard walls, blocking corridors the bot can actually
                    // traverse — exactly the live failure that surfaced
                    // this. Leaving the prior tile data (or absence) lets
                    // the planner treat unknown tiles as crossable instead.
                    if (movement == 0x00ffffff) continue;
                    builder.setTile(worldX, worldY, plane, movement);
                }

                // Step 5: enumerate game objects on the player's plane tile.
                if (playerPlane >= 0 && playerPlane < sceneTiles.length)
                {
                    Tile[][] planeTiles = sceneTiles[playerPlane];
                    if (planeTiles != null
                        && sceneX < planeTiles.length
                        && planeTiles[sceneX] != null
                        && sceneY < planeTiles[sceneX].length)
                    {
                        Tile tile = planeTiles[sceneX][sceneY];
                        if (tile != null)
                        {
                            WorldPoint tileWp = new WorldPoint(worldX, worldY, playerPlane);
                            recordObjects(tile, tileWp, builder, now);
                        }
                    }
                }
            }
        }

        // Step 7: publish all region builders (only reached if budget was OK).
        int totalTiles = 0, totalObjects = 0;
        for (Map.Entry<Integer, RegionChunkBuilder> entry : builders.entrySet())
        {
            RegionChunkBuilder b = entry.getValue();
            b.lastScrapedAt = now;
            b.gameRevision = client.getRevision();
            totalTiles += b.tiles.size();
            totalObjects += b.objects.size();
            mapStore.publish(entry.getKey(), b);
        }
        long elapsedNs = System.nanoTime() - startNs;
        if (log.isDebugEnabled() && !builders.isEmpty())
        {
            log.debug("[worldmap] scrape regions={} tiles={} objects={} durationMs={}",
                builders.size(), totalTiles, totalObjects, elapsedNs / 1_000_000);
        }
    }

    /** Enumerate the four object slots on a tile; record any with actions. */
    private void recordObjects(Tile tile, WorldPoint tileWp,
                               RegionChunkBuilder builder, long now)
    {
        // GameObjects (up to ~5 per tile)
        GameObject[] gos = tile.getGameObjects();
        if (gos != null)
        {
            for (GameObject go : gos)
            {
                if (go == null) continue;
                recordTileObject(go.getId(), tileWp, builder, now);
            }
        }

        // WallObject
        WallObject wall = tile.getWallObject();
        if (wall != null)
        {
            recordTileObject(wall.getId(), tileWp, builder, now);
        }

        // DecorativeObject
        DecorativeObject deco = tile.getDecorativeObject();
        if (deco != null)
        {
            recordTileObject(deco.getId(), tileWp, builder, now);
        }

        // GroundObject
        GroundObject ground = tile.getGroundObject();
        if (ground != null)
        {
            recordTileObject(ground.getId(), tileWp, builder, now);
        }
    }

    private void recordTileObject(int id, WorldPoint tileWp,
                                  RegionChunkBuilder builder, long now)
    {
        ObjectComposition def = client.getObjectDefinition(id);
        if (def == null) return;

        // Unwrap impostors (e.g. varbit-swapped objects like closed/open doors).
        if (def.getImpostorIds() != null)
        {
            try
            {
                ObjectComposition imp = def.getImpostor();
                if (imp != null) def = imp;
            }
            catch (Throwable ignored)
            {
                // Fall back to base def if impostor lookup throws.
            }
        }

        String name = def.getName();
        if (name == null || name.isEmpty()) return;

        String[] actions = def.getActions();
        if (actions == null) return;

        // Must have at least one non-null, non-empty action to be recordable.
        boolean hasAction = false;
        for (String a : actions)
        {
            if (a != null && !a.isEmpty())
            {
                hasAction = true;
                break;
            }
        }
        if (!hasAction) return;

        builder.recordObject(id, name, tileWp, actions, now);
        entityIndex.recordObjectSighting(id, name, tileWp, now);
    }
}
