package net.runelite.client.sequence.activities.ge;

import java.util.Map;

/**
 * Per-item OSRS Grand Exchange buy limits over a rolling 4-hour window.
 *
 * <p>Limits vary by item; the table below is a small starter set covering
 * common bot targets. For unknown items we fall back to {@link #DEFAULT_LIMIT}
 * — conservative enough that the bot never blindly burns through an
 * unfamiliar item's limit, large enough that common-volume trades aren't
 * spuriously capped.
 *
 * <p>Lookup: {@link #limitFor(int)}. Per-account user overrides live in
 * the persisted {@link BuyLimitLedger} JSON (TODO when the user-facing UI
 * for editing limits lands).
 */
public final class BuyLimitTable {

    private BuyLimitTable() {}

    /** Conservative fallback for items not in the table. Most "bulk"
     *  consumables sit at 6_000 or higher; rares can be as low as 5–10.
     *  Unknown items get 6_000 — bot operators who want tighter control
     *  should add a per-item override. */
    public static final int DEFAULT_LIMIT = 6_000;

    /** Hardcoded buy-limit values for common items. Sourced from OSRS
     *  community references (Wiki / GE-Tracker). Not exhaustive — extend
     *  here, or layer per-account overrides in {@link BuyLimitLedger}. */
    private static final Map<Integer, Integer> KNOWN = Map.ofEntries(
        // Coins itself has no limit (it's not "bought" in the GE sense).
        // Treat as effectively unbounded so it never appears here.

        // Food / cooking ingredients
        Map.entry(2309, 11_000),  // Bread
        Map.entry(2313,  6_000),  // Pie dish
        Map.entry(2347,  6_000),  // Cake tin
        Map.entry(1925,  6_000),  // Bowl
        Map.entry(1923,  6_000),  // Empty pot
        Map.entry(1931,  6_000),  // Pot of flour
        Map.entry(1937,  6_000),  // Pot
        Map.entry(1933, 11_000),  // Knife
        Map.entry(2138, 11_000),  // Raw chicken
        Map.entry(2140, 11_000),  // Cooked chicken
        Map.entry(317,  13_500),  // Raw shrimps
        Map.entry(379,   6_000),  // Lobster
        Map.entry(385,   6_000),  // Shark

        // Logs (firemaking / fletching)
        Map.entry(1511, 25_000),  // Logs (regular)
        Map.entry(1521, 13_500),  // Oak logs
        Map.entry(1519, 11_000),  // Willow logs
        Map.entry(1517,  6_000),  // Maple logs
        Map.entry(1515,  6_000),  // Yew logs
        Map.entry(1513,  6_000),  // Magic logs

        // Ores / bars
        Map.entry(436,  13_500),  // Copper ore
        Map.entry(438,  13_500),  // Tin ore
        Map.entry(440,  13_500),  // Iron ore
        Map.entry(453,  13_500),  // Coal
        Map.entry(444,   6_000),  // Gold ore
        Map.entry(2349,  6_000),  // Bronze bar
        Map.entry(2351,  6_000),  // Iron bar
        Map.entry(2353,  6_000),  // Steel bar

        // Runes / runecrafting
        Map.entry(556,  25_000),  // Air rune
        Map.entry(554,  25_000),  // Fire rune
        Map.entry(555,  25_000),  // Water rune
        Map.entry(557,  25_000),  // Earth rune
        Map.entry(558,  25_000),  // Mind rune
        Map.entry(559,  25_000),  // Body rune

        // Mid-tier gear (lower limits)
        Map.entry(4151,    70),   // Abyssal whip
        Map.entry(1215,   100)    // Dragon dagger
    );

    public static int limitFor(int itemId) {
        Integer v = KNOWN.get(itemId);
        return v != null ? v : DEFAULT_LIMIT;
    }

    /** True iff we have a hardcoded entry for this item; false → caller is
     *  using {@link #DEFAULT_LIMIT}. Useful for surfacing "unknown item —
     *  using fallback limit" warnings to the user. */
    public static boolean hasKnownLimit(int itemId) {
        return KNOWN.containsKey(itemId);
    }
}
