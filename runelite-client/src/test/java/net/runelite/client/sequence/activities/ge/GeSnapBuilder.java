package net.runelite.client.sequence.activities.ge;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.sequence.PlayerView;
import net.runelite.client.sequence.WorldSnapshot;
import net.runelite.client.sequence.affordance.BlockingInterface;
import net.runelite.client.sequence.views.GrandExchangeOfferView;
import net.runelite.client.sequence.views.GrandExchangeView;
import net.runelite.client.sequence.views.InteractionMode;
import net.runelite.client.sequence.views.InteractionView;
import net.runelite.client.sequence.views.InventoryView;
import net.runelite.client.sequence.views.ItemStack;
import net.runelite.client.sequence.views.OfferSide;
import net.runelite.client.sequence.views.OfferStatus;

/**
 * Fluent {@link WorldSnapshot} builder for GE-domain tests. Produces a
 * snapshot with custom {@link InventoryView} / {@link InteractionView} /
 * {@link GrandExchangeView}, plus a {@link PlayerView}.
 *
 * <p>Methods are additive — call only the ones a given test needs; the rest
 * default to engine-empty.
 */
public final class GeSnapBuilder {

    private int tick;
    private WorldPoint playerLocation;
    private final List<ItemStack> inventory = new ArrayList<>();
    private InteractionMode mode = InteractionMode.WORLD;
    private boolean worldInteractionAvailable = true;
    private boolean movementAvailable = true;
    private BlockingInterface blocker;
    private boolean geOpen, geSetupOpen, geCollectOpen;
    private boolean searchResultsPopulated;
    private int chatboxPromptMode;
    private final GrandExchangeOfferView[] offers = new GrandExchangeOfferView[8];

    public GeSnapBuilder() {
        for (int i = 0; i < 8; i++) offers[i] = GrandExchangeOfferView.empty(i);
    }

    public GeSnapBuilder tick(int t) { this.tick = t; return this; }

    public GeSnapBuilder player(int x, int y, int plane) {
        this.playerLocation = new WorldPoint(x, y, plane);
        return this;
    }

    public GeSnapBuilder invItem(int itemId, int quantity) {
        int slot = inventory.size();
        inventory.add(new ItemStack(slot, itemId, quantity));
        return this;
    }

    public GeSnapBuilder invCoins(int amount) {
        return invItem(995, amount);
    }

    public GeSnapBuilder mode(InteractionMode m) { this.mode = m; return this; }

    public GeSnapBuilder worldInteractionAvailable(boolean v) {
        this.worldInteractionAvailable = v;
        return this;
    }

    public GeSnapBuilder blocker(BlockingInterface bi) {
        this.blocker = bi;
        this.worldInteractionAvailable = bi == null;
        return this;
    }

    public GeSnapBuilder geOpen(boolean v) {
        this.geOpen = v;
        if (v) this.mode = InteractionMode.GRAND_EXCHANGE;
        return this;
    }

    public GeSnapBuilder geSetupOpen(boolean v) { this.geSetupOpen = v; return this; }
    public GeSnapBuilder searchResultsPopulated(boolean v) { this.searchResultsPopulated = v; return this; }
    public GeSnapBuilder chatboxPromptMode(int v) { this.chatboxPromptMode = v; return this; }

    public GeSnapBuilder geCollectOpen(boolean v) { this.geCollectOpen = v; return this; }

    public GeSnapBuilder offer(int slot, OfferSide side, OfferStatus status,
                               int itemId, int requestedQty, int completedQty,
                               int priceEach) {
        if (slot < 0 || slot >= 8) throw new IllegalArgumentException("slot must be 0..7");
        offers[slot] = new GrandExchangeOfferView(slot, side, status,
            itemId, requestedQty, completedQty, priceEach,
            completedQty * priceEach);
        return this;
    }

    public WorldSnapshot build() {
        InventoryView inv = new BuiltInventoryView(List.copyOf(inventory));
        InteractionView interaction = new BuiltInteractionView(
            mode, worldInteractionAvailable, movementAvailable,
            Optional.ofNullable(blocker));
        GrandExchangeView ge = new BuiltGrandExchangeView(
            geOpen, geSetupOpen, geCollectOpen, searchResultsPopulated,
            chatboxPromptMode, List.of(offers.clone()));
        PlayerView pv = playerLocation == null ? null : new BuiltPlayerView(playerLocation);
        return new BuiltSnapshot(tick, pv, inv, interaction, ge);
    }

    private record BuiltSnapshot(
        int tick, PlayerView player, InventoryView inv,
        InteractionView interaction, GrandExchangeView ge
    ) implements WorldSnapshot {
        @Override public int tick()                          { return tick; }
        @Override public PlayerView player()                 { return player; }
        @Override public InventoryView inventory()           { return inv; }
        @Override public InteractionView interaction()       { return interaction; }
        @Override public GrandExchangeView grandExchange()   { return ge; }
    }

    private record BuiltPlayerView(WorldPoint loc) implements PlayerView {
        @Override public WorldPoint worldLocation() { return loc; }
        @Override public int animation()            { return -1; }
        @Override public boolean isIdle()           { return true; }
        @Override public int health()               { return 99; }
        @Override public int maxHealth()            { return 99; }
    }

    private record BuiltInventoryView(List<ItemStack> items) implements InventoryView {
        @Override public int size()                  { return 28; }
        @Override public int freeSlots()             { return 28 - items.size(); }
        @Override public boolean isFull()            { return items.size() >= 28; }
        @Override public List<ItemStack> items()     { return items; }
        @Override public boolean contains(int id)    {
            for (ItemStack s : items) if (s.itemId() == id) return true;
            return false;
        }
        @Override public int count(int id) {
            int n = 0;
            for (ItemStack s : items) if (s.itemId() == id) n += s.quantity();
            return n;
        }
    }

    private record BuiltInteractionView(
        InteractionMode mode, boolean worldInteractionAvailable, boolean movementAvailable,
        Optional<BlockingInterface> blockingInterface
    ) implements InteractionView {
        @Override public net.runelite.client.sequence.affordance.AffordanceReport affordances() {
            return net.runelite.client.sequence.affordance.AffordanceReport.allAllowed();
        }
    }

    private record BuiltGrandExchangeView(
        boolean open, boolean offerSetupOpen, boolean collectOpen,
        boolean searchResultsPopulated, int chatboxPromptMode,
        List<GrandExchangeOfferView> offers
    ) implements GrandExchangeView {
        @Override public boolean chatboxPromptOpen()      { return chatboxPromptMode != 0; }
        @Override public OptionalInt firstEmptySlot() {
            for (GrandExchangeOfferView o : offers) {
                if (o.isEmpty()) return OptionalInt.of(o.slot());
            }
            return OptionalInt.empty();
        }
        @Override public int emptySlotCount() {
            int n = 0;
            for (GrandExchangeOfferView o : offers) if (o.isEmpty()) n++;
            return n;
        }
        @Override public List<GrandExchangeOfferView> offersFor(int itemId, OfferSide side) {
            List<GrandExchangeOfferView> out = new ArrayList<>();
            for (GrandExchangeOfferView o : offers) {
                if (o.itemId() == itemId && o.side() == side && !o.isEmpty()) out.add(o);
            }
            return List.copyOf(out);
        }
    }
}
