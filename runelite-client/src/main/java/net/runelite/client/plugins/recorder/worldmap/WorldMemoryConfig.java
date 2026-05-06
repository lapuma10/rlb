package net.runelite.client.plugins.recorder.worldmap;

/**
 * Tunable knobs for the WorldMemory subsystem. Spec values; live tuning
 * happens via RuneLite config later if needed.
 */
public final class WorldMemoryConfig
{
    /** Scrape every N game ticks. Spec: every 2 ticks (~1.2s). */
    public final int scrapeEveryNTicks = 2;

    /** Window radius around player to scrape (window is 2R+1 wide). */
    public final int scrapeWindowRadius = 9;          // 18×18 effective

    /** Per-tick scrape budget. If a scrape would exceed this in nanos,
     *  skip and log; lastScrapedAt does NOT advance. */
    public final long scrapeBudgetNanos = 2_000_000;  // 2 ms

    /** Flush dirty chunks every N seconds. */
    public final int flushEverySeconds = 30;

    /** A* / Dijkstra caps. */
    public final int maxPathLength = 128;
    public final int maxExpandedTiles = 10_000;

    /** Ranking weights for findInteractTile. */
    public final double rankWeightPathLength = 0.6;
    public final double rankWeightChebyshevToTarget = 0.4;

    /** MapStore in-memory chunk count cap (LRU beyond this). */
    public final int memoryResidentChunkCount = 24;
}
