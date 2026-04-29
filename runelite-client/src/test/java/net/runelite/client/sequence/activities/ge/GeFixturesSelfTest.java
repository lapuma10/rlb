package net.runelite.client.sequence.activities.ge;

import net.runelite.client.sequence.WorldSnapshot;
import net.runelite.client.sequence.views.OfferSide;
import net.runelite.client.sequence.views.OfferStatus;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Smoke check that {@link GeSnapBuilder} wires every view it claims to,
 *  and that {@link RecordingGeActions} captures call order. */
public class GeFixturesSelfTest {

    @Test
    public void snapBuilderProducesAFullSnapshot() {
        WorldSnapshot s = new GeSnapBuilder()
            .tick(7)
            .player(3160, 3490, 0)
            .invCoins(1_000_000)
            .invItem(4151, 1)
            .geOpen(true)
            .geSetupOpen(false)
            .offer(0, OfferSide.BUY, OfferStatus.ACTIVE, 4151, 100, 25, 1500)
            .build();

        assertEquals(7, s.tick());
        assertEquals(3160, s.player().worldLocation().getX());
        assertEquals(1_000_000, s.inventory().count(995));
        assertEquals(1, s.inventory().count(4151));
        assertEquals(28 - 2, s.inventory().freeSlots());
        assertTrue(s.grandExchange().open());
        assertFalse(s.grandExchange().offerSetupOpen());
        assertEquals(7, s.grandExchange().emptySlotCount());
        assertEquals(java.util.OptionalInt.of(1), s.grandExchange().firstEmptySlot());
        assertEquals(1, s.grandExchange().offersFor(4151, OfferSide.BUY).size());
    }

    @Test
    public void emptyBuilderProducesEmptyDefaults() {
        WorldSnapshot s = new GeSnapBuilder().build();
        assertEquals(0, s.tick());
        assertEquals(28, s.inventory().freeSlots());
        assertFalse(s.grandExchange().open());
        assertEquals(8, s.grandExchange().emptySlotCount());
    }

    @Test
    public void recordingActionsCapturesCallOrder() {
        RecordingGeActions a = new RecordingGeActions();
        a.openGrandExchange();
        a.clickOfferSlotButton(0, OfferSide.BUY);
        a.selectItem(4151, "Abyssal whip");
        a.setQuantity(1);
        a.setPrice(1_500_000);
        a.confirmOffer();

        assertEquals(6, a.callCount());
        assertEquals("openGrandExchange()", a.calls().get(0));
        assertEquals("clickOfferSlotButton(slot=0, side=BUY)", a.calls().get(1));
        assertEquals("selectItem(itemId=4151, name=Abyssal whip)", a.calls().get(2));
        assertEquals("setQuantity(1)", a.calls().get(3));
        assertEquals("setPrice(1500000)", a.calls().get(4));
        assertEquals("confirmOffer()", a.calls().get(5));
    }
}
