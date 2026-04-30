package net.runelite.client.sequence.internal;

import java.util.Arrays;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.client.sequence.views.BankItemAvailability;
import net.runelite.client.sequence.views.BankView;

/**
 * Per-tick observer for bank widget and container state.
 *
 * <p>All client reads happen on the caller's thread — callers must be on the
 * client thread (same invariant as {@link ClientObserver}).
 */
final class BankObserver {
    private final Client client;
    private final WidgetObserver widgetObserver;

    private long prevContainerHash = Long.MIN_VALUE;
    private int lastContainerChangeTick = -1;

    BankObserver(Client client, WidgetObserver widgetObserver) {
        this.client = client;
        this.widgetObserver = widgetObserver;
    }

    /** Returns the last tick on which the bank container contents changed, or -1 if never. */
    int lastChangeTick() {
        return lastContainerChangeTick;
    }

    // ---- per-tick checks --------------------------------------------------------

    /** True when the bank main widget is present and visible. */
    boolean open() {
        return widgetObserver.isVisible(InterfaceID.Bankmain.UNIVERSE);
    }

    /** True when the bank PIN keypad is visible (PIN screen is up). */
    boolean pinUp() {
        return widgetObserver.isVisible(InterfaceID.BankpinKeypad.UNIVERSE);
    }

    /**
     * True when the bank container is populated.
     * We use {@code container != null} as the basic check; a freshly-opened bank
     * may take one tick to populate, but a non-null container is a reliable signal.
     */
    boolean ready() {
        ItemContainer c = client.getItemContainer(InventoryID.BANK);
        return c != null;
    }

    // ---- snapshot ---------------------------------------------------------------

    /** Reads bank state, updates change tracking, and returns a {@link BankView}. */
    BankView snapshot(int currentTick) {
        final boolean isOpen   = open();
        final boolean isPinUp  = pinUp();
        final boolean isReady  = ready();

        // Update container change tick when bank container hash changes.
        if (isReady) {
            ItemContainer c = client.getItemContainer(InventoryID.BANK);
            if (c != null) {
                long hash = containerHash(c);
                if (hash != prevContainerHash) {
                    prevContainerHash = hash;
                    lastContainerChangeTick = currentTick;
                }
            }
        }

        return new BankView() {
            @Override public boolean open()  { return isOpen; }
            @Override public boolean ready() { return isReady; }
            @Override public boolean pinUp() { return isPinUp; }

            @Override
            public BankItemAvailability availability(int itemId) {
                if (!isReady) return BankItemAvailability.unknown();
                ItemContainer c = client.getItemContainer(InventoryID.BANK);
                if (c == null) return BankItemAvailability.unknown();
                int total = 0;
                for (Item it : c.getItems()) {
                    if (it != null && it.getId() == itemId && it.getQuantity() > 0) {
                        total += it.getQuantity();
                    }
                }
                if (total <= 0) return BankItemAvailability.absent();
                // Stackability of the un-noted form decides whether a withdraw
                // of qty>1 needs one or qty inventory slots. Item def reads are
                // safe here — caller is on the client thread (BankObserver
                // contract).
                boolean stackable = false;
                try {
                    var def = client.getItemDefinition(itemId);
                    if (def != null) stackable = def.isStackable();
                } catch (Exception ignored) {
                    // Definition unavailable for some reason — assume non-
                    // stackable (the safe pessimistic default).
                }
                // visible=true: per spec §5.2 full scroll-visibility tracking is deferred;
                // every present item is reported as visible.
                return BankItemAvailability.present(total, true, stackable);
            }
        };
    }

    // ---- helpers ----------------------------------------------------------------

    private static long containerHash(ItemContainer c) {
        Item[] items = c.getItems();
        long h = 1L;
        for (int i = 0; i < items.length; i++) {
            Item it = items[i];
            if (it == null) continue;
            h = 31L * h + i;
            h = 31L * h + it.getId();
            h = 31L * h + it.getQuantity();
        }
        return h;
    }
}
