package net.runelite.client.sequence.activities.banking;

import net.runelite.api.coords.WorldPoint;
import net.runelite.client.sequence.PlayerView;
import net.runelite.client.sequence.WorldSnapshot;
import net.runelite.client.sequence.affordance.AffordanceReport;
import net.runelite.client.sequence.affordance.BlockingInterface;
import net.runelite.client.sequence.views.*;

import java.util.*;

/**
 * Fluent builder for {@link WorldSnapshot} fixtures used in banking tests.
 * Delegates to {@link ImmutableWorldSnapshot.Builder} internally.
 */
public final class BankSnapBuilder {

    private int tick = 0;
    private PlayerView player = null;

    // Inventory state
    private final List<ItemStack> invItems = new ArrayList<>();
    private int freeSlotsOverride = -1; // -1 means derive from invItems

    // Bank state
    private boolean bankOpen = false;
    private boolean bankReady = false;
    private boolean bankPinUp = false;
    private final Map<Integer, BankItemAvailability> bankItems = new HashMap<>();

    // Widget state — root widget IDs that are "visible"
    private final Set<Integer> visibleRootIds = new HashSet<>();

    // Interaction state
    private InteractionMode mode = InteractionMode.WORLD;
    private boolean worldAvailable = true;
    private BlockingInterface blocker = null;

    // EventFacts
    private int lastInvChangeTick = -1;
    private int lastBankContainerChangeTick = -1;
    private int lastBlockingInterfaceChangeTick = -1;
    private int lastPlayerAnimationChangeTick = -1;

    public BankSnapBuilder() {}

    public BankSnapBuilder tick(int t) {
        this.tick = t;
        return this;
    }

    public BankSnapBuilder player(PlayerView p) {
        this.player = p;
        return this;
    }

    /**
     * Convenience: build a minimal {@link PlayerView} at the given tile, idle.
     */
    public BankSnapBuilder player(WorldPoint location) {
        this.player = new PlayerView() {
            @Override public WorldPoint worldLocation() { return location; }
            @Override public int animation()            { return -1; }
            @Override public boolean isIdle()           { return true; }
            @Override public int health()               { return 100; }
            @Override public int maxHealth()            { return 100; }
        };
        return this;
    }

    /**
     * Add an inventory item (slot auto-assigned sequentially). Also decrements freeSlots
     * unless {@link #freeSlots(int)} is called later to override.
     */
    public BankSnapBuilder invItem(int itemId, int quantity) {
        int slot = invItems.size();
        invItems.add(new ItemStack(slot, itemId, quantity));
        return this;
    }

    /** Override the free-slots count directly (ignores derived count from invItem calls). */
    public BankSnapBuilder freeSlots(int n) {
        this.freeSlotsOverride = n;
        return this;
    }

    public BankSnapBuilder bankOpen(boolean open) {
        this.bankOpen = open;
        return this;
    }

    public BankSnapBuilder bankReady(boolean ready) {
        this.bankReady = ready;
        return this;
    }

    public BankSnapBuilder bankPinUp(boolean pinUp) {
        this.bankPinUp = pinUp;
        return this;
    }

    public BankSnapBuilder bankItem(int itemId, int knownCount, boolean visible) {
        bankItems.put(itemId, BankItemAvailability.present(knownCount, visible));
        return this;
    }

    public BankSnapBuilder bankItemAbsent(int itemId) {
        bankItems.put(itemId, BankItemAvailability.absent());
        return this;
    }

    public BankSnapBuilder bankItemUnknown(int itemId) {
        bankItems.put(itemId, BankItemAvailability.unknown());
        return this;
    }

    public BankSnapBuilder mode(InteractionMode m) {
        this.mode = m;
        return this;
    }

    public BankSnapBuilder worldAvailable(boolean available) {
        this.worldAvailable = available;
        return this;
    }

    public BankSnapBuilder blocker(BlockingInterface b) {
        this.blocker = b;
        return this;
    }

    public BankSnapBuilder lastInvChangeTick(int t) {
        this.lastInvChangeTick = t;
        return this;
    }

    public BankSnapBuilder lastBankContainerChangeTick(int t) {
        this.lastBankContainerChangeTick = t;
        return this;
    }

    public BankSnapBuilder lastBlockingInterfaceChangeTick(int t) {
        this.lastBlockingInterfaceChangeTick = t;
        return this;
    }

    public BankSnapBuilder lastPlayerAnimationChangeTick(int t) {
        this.lastPlayerAnimationChangeTick = t;
        return this;
    }

    /** Mark a root widget ID as visible in the WidgetView. */
    public BankSnapBuilder widgetVisible(int rootWidgetId) {
        visibleRootIds.add(rootWidgetId);
        return this;
    }

    public WorldSnapshot build() {
        // InventoryView
        final List<ItemStack> frozenItems = List.copyOf(invItems);
        final int computedFreeSlots = freeSlotsOverride >= 0
            ? freeSlotsOverride
            : Math.max(0, 28 - frozenItems.size());
        final int fs = computedFreeSlots;

        InventoryView inventoryView = new InventoryView() {
            @Override public int size()               { return 28; }
            @Override public int freeSlots()          { return fs; }
            @Override public boolean isFull()         { return fs == 0; }
            @Override public List<ItemStack> items()  { return frozenItems; }
            @Override public boolean contains(int id) {
                return frozenItems.stream().anyMatch(i -> i.itemId() == id);
            }
            @Override public int count(int id) {
                return frozenItems.stream()
                    .filter(i -> i.itemId() == id)
                    .mapToInt(ItemStack::quantity)
                    .sum();
            }
        };

        // BankView
        final Map<Integer, BankItemAvailability> frozenBank = Map.copyOf(bankItems);
        final boolean bo = bankOpen, br = bankReady, bp = bankPinUp;
        BankView bankView = new BankView() {
            @Override public boolean open()  { return bo; }
            @Override public boolean ready() { return br; }
            @Override public boolean pinUp() { return bp; }
            @Override public BankItemAvailability availability(int id) {
                return frozenBank.getOrDefault(id, BankItemAvailability.unknown());
            }
        };

        // WidgetView
        final Set<Integer> frozenVisible = Set.copyOf(visibleRootIds);
        WidgetView widgetView = new WidgetView() {
            @Override public boolean isVisible(int id)      { return frozenVisible.contains(id); }
            @Override public boolean isHidden(int id)       { return !frozenVisible.contains(id); }
            @Override public Set<Integer> visibleRootIds()  { return frozenVisible; }
        };

        // InteractionView
        final InteractionMode m = mode;
        final boolean wa = worldAvailable;
        final BlockingInterface bl = blocker;
        InteractionView interactionView = new InteractionView() {
            @Override public InteractionMode mode()                          { return m; }
            @Override public boolean worldInteractionAvailable()             { return wa; }
            @Override public boolean movementAvailable()                     { return wa; }
            @Override public Optional<BlockingInterface> blockingInterface() {
                return bl != null ? Optional.of(bl) : Optional.empty();
            }
            @Override public AffordanceReport affordances()                  { return AffordanceReport.allAllowed(); }
        };

        // EventFacts
        final int liCT = lastInvChangeTick;
        final int lbCT = lastBankContainerChangeTick;
        final int lbiCT = lastBlockingInterfaceChangeTick;
        final int lpaCT = lastPlayerAnimationChangeTick;
        EventFacts eventFacts = new EventFacts() {
            @Override public int lastInventoryChangeTick()          { return liCT; }
            @Override public int lastBankContainerChangeTick()      { return lbCT; }
            @Override public int lastBlockingInterfaceChangeTick()  { return lbiCT; }
            @Override public int lastPlayerAnimationChangeTick()    { return lpaCT; }
        };

        return ImmutableWorldSnapshot.builder()
            .tick(tick)
            .player(player)
            .inventory(inventoryView)
            .bank(bankView)
            .widgets(widgetView)
            .interaction(interactionView)
            .events(eventFacts)
            .build();
    }
}
