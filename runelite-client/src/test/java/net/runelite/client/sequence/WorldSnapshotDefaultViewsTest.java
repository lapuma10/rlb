package net.runelite.client.sequence;

import net.runelite.client.sequence.views.GrandExchangeView;
import net.runelite.client.sequence.views.InteractionMode;
import net.runelite.client.sequence.views.InteractionView;
import net.runelite.client.sequence.views.InventoryView;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Foundation test F5: a {@link WorldSnapshot} implementation that does NOT
 * override {@code inventory()}, {@code interaction()}, or {@code grandExchange()}
 * gets sensible null-object defaults. This keeps existing fixtures (and
 * tests written before Task 5 added the new views) compiling unchanged.
 */
public class WorldSnapshotDefaultViewsTest {

    @Test
    public void minimalSnapshotReturnsEmptyInventoryView() {
        WorldSnapshot s = minimal(0);
        InventoryView inv = s.inventory();
        assertNotNull(inv);
        assertEquals("default freeSlots is 28 (full empty inventory)", 28, inv.freeSlots());
        assertEquals("count of any id is zero", 0, inv.count(995));
        assertFalse("contains is false for any id", inv.contains(995));
        assertFalse("isFull is false for the empty default", inv.isFull());
        assertEquals(28, inv.size());
        assertTrue("items list is empty", inv.items().isEmpty());
    }

    @Test
    public void minimalSnapshotReturnsEmptyInteractionView() {
        WorldSnapshot s = minimal(0);
        InteractionView i = s.interaction();
        assertNotNull(i);
        assertEquals("default mode is WORLD (free to act)", InteractionMode.WORLD, i.mode());
        assertTrue("worldInteractionAvailable defaults to true", i.worldInteractionAvailable());
        assertTrue("movementAvailable defaults to true", i.movementAvailable());
        assertFalse("no blocking interface in the empty default",
            i.blockingInterface().isPresent());
    }

    @Test
    public void minimalSnapshotReturnsEmptyGrandExchangeView() {
        WorldSnapshot s = minimal(0);
        GrandExchangeView ge = s.grandExchange();
        assertNotNull(ge);
        assertFalse("ge is closed by default", ge.open());
        assertFalse("ge offer-setup is closed by default", ge.offerSetupOpen());
        assertFalse("ge collect is closed by default", ge.collectOpen());
        assertEquals("all 8 slots are empty", 8, ge.emptySlotCount());
        assertEquals("first empty slot is 0", java.util.OptionalInt.of(0), ge.firstEmptySlot());
        assertEquals("offers list has 8 EMPTY rows", 8, ge.offers().size());
        ge.offers().forEach(o -> assertTrue("each default offer is EMPTY", o.isEmpty()));
        assertTrue("offersFor any (id,side) is empty in the default",
            ge.offersFor(4151, net.runelite.client.sequence.views.OfferSide.BUY).isEmpty());
    }

    private static WorldSnapshot minimal(int tick) {
        return new WorldSnapshot() {
            public int tick() { return tick; }
            public PlayerView player() { return null; }
        };
    }
}
