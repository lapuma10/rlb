package net.runelite.client.plugins.recorder.worldmap;

/**
 * Tunable knobs for the WorldMemory subsystem. Spec values; live tuning
 * happens via RuneLite config later if needed.
 */
public final class WorldMemoryConfig
{
    /** Scrape every N game ticks. Spec: every 2 ticks (~1.2s). */
    public final int scrapeEveryNTicks = 2;

    /** Window radius around player to scrape (window is 2R+1 wide).
     *  Bumped 9 → 25 → 52 after live bank↔pen testing. The OSRS loaded
     *  scene is 104×104 tiles, so radius 52 captures EXACTLY the
     *  engine-loaded scene every scrape — no tile the engine has
     *  loaded is left out. Live failure that motivated the final bump:
     *  bot walked bank → pen, captured a narrow strip along its path,
     *  but the planner's known-walkable cone preferred a 110-tile
     *  bridge/church detour over the 75-tile direct corridor because
     *  the direct corridor still had unknown gaps the 25-tile window
     *  didn't reach.  At radius 52 a single trip captures the full
     *  surrounding ~10000-tile area; the planner can pick straight
     *  routes from then on. */
    public final int scrapeWindowRadius = 52;

    /** Per-tick scrape budget. If a scrape would exceed this in nanos,
     *  skip and log; lastScrapedAt does NOT advance. Bumped 2 ms →
     *  8 ms → 20 ms to accommodate the full-scene scrape window
     *  (radius 52 ⇒ 105×105 = 11025 cells × 4 planes ≈ 44k array
     *  reads + ~20k builder writes per tick). With scrapeEveryNTicks=2
     *  this is 20 ms every 1.2 s = 1.7 % CPU — well under the budget
     *  for an OSRS tick and lossless on modern hardware. */
    public final long scrapeBudgetNanos = 20_000_000;  // 20 ms

    /** Flush dirty chunks every N seconds. */
    public final int flushEverySeconds = 30;

    /** A* / Dijkstra caps. */
    public final int maxPathLength = 128;
    /** Bumped from 10k → 50k after the live bank↔pen failure: cross-plane
     *  plans through partially-scraped corridors can need ~15-20k
     *  expansions even with a near-admissible heuristic. 50k provides
     *  headroom for Lumbridge-class routes; HashMap state stays well
     *  under 10 MB. */
    public final int maxExpandedTiles = 50_000;

    /** Ranking weights for findInteractTile. */
    public final double rankWeightPathLength = 0.6;
    public final double rankWeightChebyshevToTarget = 0.4;

    /** MapStore in-memory chunk count cap (LRU beyond this). */
    public final int memoryResidentChunkCount = 24;
}
