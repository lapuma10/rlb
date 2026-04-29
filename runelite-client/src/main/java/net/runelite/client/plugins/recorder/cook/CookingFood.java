package net.runelite.client.plugins.recorder.cook;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.gameval.ItemID;

/**
 * Table of cookable raw foods with their raw / cooked / burnt item IDs.
 *
 * <p>Used by the cooking script to (a) drive the panel's food picker,
 * (b) detect successful cooks and burnt failures, (c) decide what to
 * withdraw from the bank.
 *
 * <p>Burnt-fish IDs are tier-shared in OSRS: BURNTFISH1 (323) covers
 * anchovies + sardine, BURNTFISH2 (343) herring, BURNTFISH3 (357) trout +
 * salmon. Shrimp has its own BURNT_SHRIMP (7954). Lobster / swordfish /
 * shark each have their own burnt items.
 */
public final class CookingFood
{
    private CookingFood() {}

    public static final class Entry
    {
        public final String label;
        public final int rawId;
        public final int cookedId;
        public final int burntId;

        public Entry(String label, int rawId, int cookedId, int burntId)
        {
            this.label = label;
            this.rawId = rawId;
            this.cookedId = cookedId;
            this.burntId = burntId;
        }

        @Override public String toString() { return label; }
    }

    private static final Map<Integer, Entry> BY_RAW = new LinkedHashMap<>();

    private static void put(Entry e)
    {
        BY_RAW.put(e.rawId, e);
    }

    static
    {
        // Order matters — drives the panel dropdown order, lowest-level
        // food first. Burnt IDs are the tier-shared BURNTFISH1..3 for
        // most fish; shrimp / lobster / swordfish / shark / chicken /
        // meat / monkfish each have their own dedicated burnt-item id.
        // Beef / rat meat / bear meat all cook into the same COOKED_MEAT
        // (id 2142) and burn into the same BURNT_MEAT (id 2146).
        put(new Entry("Chicken",   ItemID.RAW_CHICKEN,   ItemID.COOKED_CHICKEN, ItemID.BURNT_CHICKEN));
        put(new Entry("Beef",      ItemID.RAW_BEEF,      ItemID.COOKED_MEAT,    ItemID.BURNT_MEAT));
        put(new Entry("Rat meat",  ItemID.RAW_RAT_MEAT,  ItemID.COOKED_MEAT,    ItemID.BURNT_MEAT));
        put(new Entry("Bear meat", ItemID.RAW_BEAR_MEAT, ItemID.COOKED_MEAT,    ItemID.BURNT_MEAT));
        put(new Entry("Shrimps",   ItemID.RAW_SHRIMP,    ItemID.SHRIMP,         ItemID.BURNT_SHRIMP));
        put(new Entry("Anchovies", ItemID.RAW_ANCHOVIES, ItemID.ANCHOVIES,      ItemID.BURNTFISH1));
        put(new Entry("Sardine",   ItemID.RAW_SARDINE,   ItemID.SARDINE,        ItemID.BURNTFISH1));
        put(new Entry("Herring",   ItemID.RAW_HERRING,   ItemID.HERRING,        ItemID.BURNTFISH2));
        put(new Entry("Trout",     ItemID.RAW_TROUT,     ItemID.TROUT,          ItemID.BURNTFISH3));
        put(new Entry("Salmon",    ItemID.RAW_SALMON,    ItemID.SALMON,         ItemID.BURNTFISH3));
        put(new Entry("Lobster",   ItemID.RAW_LOBSTER,   ItemID.LOBSTER,        ItemID.BURNT_LOBSTER));
        put(new Entry("Swordfish", ItemID.RAW_SWORDFISH, ItemID.SWORDFISH,      ItemID.BURNT_SWORDFISH));
        put(new Entry("Monkfish",  ItemID.RAW_MONKFISH,  ItemID.MONKFISH,       ItemID.BURNT_MONKFISH));
        put(new Entry("Shark",     ItemID.RAW_SHARK,     ItemID.SHARK,          ItemID.BURNT_SHARK));
    }

    public static List<Entry> all() { return List.copyOf(BY_RAW.values()); }

    public static Entry byRawId(int rawId) { return BY_RAW.get(rawId); }

    public static Entry byLabel(String label)
    {
        for (Entry e : BY_RAW.values())
        {
            if (e.label.equalsIgnoreCase(label)) return e;
        }
        return null;
    }
}
