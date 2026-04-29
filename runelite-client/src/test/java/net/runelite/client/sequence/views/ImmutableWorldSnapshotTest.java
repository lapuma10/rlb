package net.runelite.client.sequence.views;

import org.junit.Test;
import static org.junit.Assert.*;

public class ImmutableWorldSnapshotTest {

    @Test
    public void defaults_whenNothingSet() {
        ImmutableWorldSnapshot s = ImmutableWorldSnapshot.builder().build();
        assertEquals(0, s.tick());
        assertNull(s.player());
        assertNotNull(s.inventory());
        assertNotNull(s.bank());
        assertNotNull(s.widgets());
        assertNotNull(s.interaction());
        assertNotNull(s.events());
    }

    @Test
    public void tickOverrideIsPreserved() {
        ImmutableWorldSnapshot s = ImmutableWorldSnapshot.builder().tick(7).build();
        assertEquals(7, s.tick());
    }

    @Test
    public void bankOverrideIsUsed_othersRemainDefaults() {
        BankView customBank = new BankView() {
            @Override public boolean open()                            { return true; }
            @Override public boolean ready()                           { return true; }
            @Override public boolean pinUp()                           { return false; }
            @Override public BankItemAvailability availability(int id) { return BankItemAvailability.absent(); }
        };

        ImmutableWorldSnapshot s = ImmutableWorldSnapshot.builder()
            .tick(7)
            .bank(customBank)
            .build();

        assertEquals(7, s.tick());
        assertSame(customBank, s.bank());
        // remaining views still default
        assertEquals(28, s.inventory().freeSlots());
        assertFalse(s.widgets().isVisible(999));
        assertEquals(InteractionMode.WORLD, s.interaction().mode());
        assertEquals(-1, s.events().lastInventoryChangeTick());
    }

    @Test
    public void defaultInventoryView_isEmpty() {
        InventoryView inv = ImmutableWorldSnapshot.builder().build().inventory();
        assertEquals(28, inv.size());
        assertEquals(28, inv.freeSlots());
        assertFalse(inv.isFull());
        assertTrue(inv.items().isEmpty());
        assertFalse(inv.contains(4151));
        assertEquals(0, inv.count(4151));
    }

    @Test
    public void defaultBankView_isClosed() {
        BankView bank = ImmutableWorldSnapshot.builder().build().bank();
        assertFalse(bank.open());
        assertFalse(bank.ready());
        assertFalse(bank.pinUp());
        assertEquals(Presence.UNKNOWN, bank.availability(995).presence());
    }

    @Test
    public void defaultWidgetView_allFalse() {
        WidgetView w = ImmutableWorldSnapshot.builder().build().widgets();
        assertFalse(w.isVisible(1));
        assertFalse(w.isHidden(1));
        assertTrue(w.visibleRootIds().isEmpty());
    }

    @Test
    public void defaultInteractionView_isWorld() {
        InteractionView iv = ImmutableWorldSnapshot.builder().build().interaction();
        assertEquals(InteractionMode.WORLD, iv.mode());
        assertTrue(iv.worldInteractionAvailable());
        assertTrue(iv.movementAvailable());
        assertFalse(iv.blockingInterface().isPresent());
    }

    @Test
    public void defaultEventFacts_allMinusOne() {
        EventFacts ef = ImmutableWorldSnapshot.builder().build().events();
        assertEquals(-1, ef.lastInventoryChangeTick());
        assertEquals(-1, ef.lastBankContainerChangeTick());
        assertEquals(-1, ef.lastBlockingInterfaceChangeTick());
        assertEquals(-1, ef.lastPlayerAnimationChangeTick());
    }
}
