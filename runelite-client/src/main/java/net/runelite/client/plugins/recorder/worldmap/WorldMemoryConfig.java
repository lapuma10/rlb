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
     *  Bumped from 9 → 25 after the live bank↔pen failure: at radius
     *  9 the scraper only captures tiles within ~9 of the bot's
     *  exact path, so a corridor walked once leaves huge gaps in
     *  region snapshots. The OSRS loaded scene is 104×104 tiles
     *  (~52 either side); 25 captures a 51×51 = 2601-tile window per
     *  tick — well within the loaded scene and well within the 2 ms
     *  scrape budget on modern hardware. Future bump to ~52 once
     *  the scrape budget is widened to match. */
    public final int scrapeWindowRadius = 25;

    /** Per-tick scrape budget. If a scrape would exceed this in nanos,
     *  skip and log; lastScrapedAt does NOT advance. Bumped from 2 ms
     *  → 8 ms to accommodate the wider scrape window (radius 25 ⇒ 2601
     *  cells × 4 planes ≈ 10k array reads + ~5k builder writes per
     *  tick). 8 ms is 1.3% of an OSRS tick (600 ms) — comfortable. */
    public final long scrapeBudgetNanos = 8_000_000;  // 8 ms

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
