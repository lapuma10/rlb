package net.runelite.client.sequence.util;

import java.util.List;
import java.util.OptionalInt;
import javax.annotation.Nullable;
import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.client.game.ItemManager;
import net.runelite.http.api.item.ItemPrice;

/**
 * Item id ↔ name lookups for logging and human-readable diagnostics.
 *
 * <p>Threading: {@link #nameOf} / {@link #nameOrId} read the cached item
 * definition via {@link Client#getItemDefinition} which is NOT thread-safe.
 * Call from the client thread (engine {@code Step.onStart} / {@code check}
 * / {@code tick} all run there). From a worker thread, marshal:
 * <pre>{@code
 *   String name = dispatcher.runOnClient(() -> ItemNames.nameOrId(client, id));
 * }</pre>
 *
 * <p>{@link #findIdByName} delegates to {@link ItemManager#search}, which
 * walks an in-memory tradeable-item map; safe from any thread.
 */
public final class ItemNames {

    private ItemNames() {}

    /** id → display name. Returns {@code null} on invalid id, missing cache
     *  entry, or empty / "null" name. */
    @Nullable
    public static String nameOf(Client client, int itemId) {
        if (client == null || itemId <= 0) return null;
        ItemComposition def = client.getItemDefinition(itemId);
        if (def == null) return null;
        String name = def.getName();
        return (name == null || name.isEmpty() || "null".equals(name)) ? null : name;
    }

    /** id → name, with an {@code "item#<id>"} fallback for log lines that
     *  must never be null. Tolerates a {@code null} client (returns the
     *  fallback) so tests / non-client-thread callers can use it without
     *  wiring extra machinery. */
    public static String nameOrId(@Nullable Client client, int itemId) {
        String name = nameOf(client, itemId);
        return name != null ? name : ("item#" + itemId);
    }

    /**
     * Exact (case-insensitive) name → tradeable item id via
     * {@link ItemManager#search}.
     *
     * <p>Substring matches are intentionally NOT returned — typing "Cape"
     * should not collide with "Team cape 25". Only items that are tradeable
     * on the GE appear in the search index, so this won't resolve untradeable
     * items (quest items, placeholders, etc.).
     */
    public static OptionalInt findIdByName(ItemManager itemManager, String name) {
        if (itemManager == null || name == null || name.isEmpty()) {
            return OptionalInt.empty();
        }
        List<ItemPrice> hits = itemManager.search(name);
        for (ItemPrice hit : hits) {
            if (name.equalsIgnoreCase(hit.getName())) {
                return OptionalInt.of(hit.getId());
            }
        }
        return OptionalInt.empty();
    }
}
