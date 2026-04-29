package net.runelite.client.sequence.views;

import org.junit.Test;
import static org.junit.Assert.*;

public class BankItemAvailabilityTest {

    @Test
    public void unknown_hasUnknownPresence() {
        BankItemAvailability a = BankItemAvailability.unknown();
        assertEquals(Presence.UNKNOWN, a.presence());
    }

    @Test
    public void unknown_knownCountIsEmpty() {
        assertTrue(BankItemAvailability.unknown().knownCount().isEmpty());
    }

    @Test
    public void absent_hasAbsentPresence() {
        BankItemAvailability a = BankItemAvailability.absent();
        assertEquals(Presence.ABSENT, a.presence());
    }

    @Test
    public void absent_knownCountIsEmpty() {
        assertTrue(BankItemAvailability.absent().knownCount().isEmpty());
    }

    @Test
    public void present_hasPresentPresence() {
        BankItemAvailability a = BankItemAvailability.present(10, true);
        assertEquals(Presence.PRESENT, a.presence());
    }

    @Test
    public void present_knownCountMatchesArgument() {
        BankItemAvailability a = BankItemAvailability.present(5, true);
        assertEquals(5, a.knownCount().getAsInt());
    }

    @Test
    public void present_visibleTrue() {
        assertTrue(BankItemAvailability.present(5, true).visible());
    }

    @Test
    public void present_visibleFalse() {
        assertFalse(BankItemAvailability.present(5, false).visible());
    }

    @Test
    public void factoriesHaveDistinctPresences() {
        assertNotEquals(BankItemAvailability.unknown().presence(),
                        BankItemAvailability.absent().presence());
        assertNotEquals(BankItemAvailability.unknown().presence(),
                        BankItemAvailability.present(1, false).presence());
        assertNotEquals(BankItemAvailability.absent().presence(),
                        BankItemAvailability.present(1, false).presence());
    }
}
