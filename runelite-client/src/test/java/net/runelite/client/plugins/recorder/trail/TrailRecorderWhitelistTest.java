package net.runelite.client.plugins.recorder.trail;

import org.junit.Test;
import static org.junit.Assert.*;

public class TrailRecorderWhitelistTest
{
    @Test
    public void whitelistContainsRegionTransitionVerbs()
    {
        // Spec list: Open, Close, Climb-up, Climb-down, Cross, Pass,
        // Squeeze-through, Jump, Climb, Climb-over, Enter, Exit,
        // Pay-toll, Squeeze-past.
        assertTrue(TrailRecorder.isTransportVerb("Open"));
        assertTrue(TrailRecorder.isTransportVerb("Close"));
        assertTrue(TrailRecorder.isTransportVerb("Climb-up"));
        assertTrue(TrailRecorder.isTransportVerb("Climb-down"));
        assertTrue(TrailRecorder.isTransportVerb("Cross"));
        assertTrue(TrailRecorder.isTransportVerb("Pass"));
        assertTrue(TrailRecorder.isTransportVerb("Squeeze-through"));
        assertTrue(TrailRecorder.isTransportVerb("Jump"));
        assertTrue(TrailRecorder.isTransportVerb("Climb"));
        assertTrue(TrailRecorder.isTransportVerb("Climb-over"));
        assertTrue(TrailRecorder.isTransportVerb("Enter"));
        assertTrue(TrailRecorder.isTransportVerb("Exit"));
        assertTrue(TrailRecorder.isTransportVerb("Pay-toll"));
        assertTrue(TrailRecorder.isTransportVerb("Squeeze-past"));
    }

    @Test
    public void whitelistRejectsCommonNonTransportVerbs()
    {
        assertFalse(TrailRecorder.isTransportVerb("Walk here"));
        assertFalse(TrailRecorder.isTransportVerb("Talk-to"));
        assertFalse(TrailRecorder.isTransportVerb("Attack"));
        assertFalse(TrailRecorder.isTransportVerb("Take"));
        assertFalse(TrailRecorder.isTransportVerb("Trade"));
        assertFalse(TrailRecorder.isTransportVerb("Examine"));
        assertFalse(TrailRecorder.isTransportVerb("Cancel"));
        assertFalse(TrailRecorder.isTransportVerb(""));
        assertFalse(TrailRecorder.isTransportVerb(null));
    }

    @Test
    public void whitelistIsCaseInsensitive()
    {
        assertTrue(TrailRecorder.isTransportVerb("climb-up"));
        assertTrue(TrailRecorder.isTransportVerb("OPEN"));
    }
}
