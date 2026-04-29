package net.runelite.client.sequence.internal;

import javax.annotation.Nullable;
import net.runelite.client.sequence.PlayerView;
import net.runelite.client.sequence.WorldSnapshot;
import net.runelite.client.sequence.views.GrandExchangeView;
import net.runelite.client.sequence.views.InteractionView;
import net.runelite.client.sequence.views.InventoryView;

/**
 * Immutable per-tick snapshot composed by {@link ClientObserver}. Each view
 * is captured at observation time so the rest of the engine can consult them
 * off the client thread without re-reading live RuneLite state.
 *
 * <p>Used internally by the production observer pipeline. Tests use a hand-
 * rolled {@code WorldSnapshot} with whichever views they need to mock.
 */
public record ImmutableWorldSnapshot(
    int tick,
    @Nullable PlayerView playerView,
    InventoryView inventoryView,
    InteractionView interactionView,
    GrandExchangeView grandExchangeView
) implements WorldSnapshot {

    @Override public int tick()                              { return tick; }
    @Override public PlayerView player()                     { return playerView; }
    @Override public InventoryView inventory()               { return inventoryView; }
    @Override public InteractionView interaction()           { return interactionView; }
    @Override public GrandExchangeView grandExchange()       { return grandExchangeView; }
}
