package net.runelite.client.sequence.activities.ge;

import net.runelite.client.sequence.SequenceState;
import net.runelite.client.sequence.WorldSnapshot;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class OpenGrandExchangeStepTest {

    @Test
    public void alreadyOpenSucceedsImmediately() {
        WorldSnapshot s = new GeSnapBuilder().tick(0).geOpen(true).build();
        RecordingGeActions actions = new RecordingGeActions();
        GeEngineHarness h = new GeEngineHarness().queue(s, s);
        h.run(new OpenGrandExchangeStep(actions));
        h.advance(2);

        assertEquals(SequenceState.IDLE, h.state());
        assertEquals("already-satisfied path: no dispatch", 0, actions.callCount());
    }

    @Test
    public void closedThenOpensAfterDispatch() {
        WorldSnapshot closed = new GeSnapBuilder().tick(0).geOpen(false).build();
        WorldSnapshot opening = new GeSnapBuilder().tick(1).geOpen(false).build();
        WorldSnapshot opened = new GeSnapBuilder().tick(2).geOpen(true).build();
        RecordingGeActions actions = new RecordingGeActions();
        GeEngineHarness h = new GeEngineHarness().queue(closed, opening, opened);
        h.run(new OpenGrandExchangeStep(actions));
        h.advance(3);

        assertEquals(SequenceState.IDLE, h.state());
        assertEquals(1, actions.callCount());
        assertEquals("openGrandExchange()", actions.calls().get(0));
    }

    @Test
    public void neverOpensTimesOut() {
        WorldSnapshot s = new GeSnapBuilder().tick(0).geOpen(false).build();
        RecordingGeActions actions = new RecordingGeActions();
        GeEngineHarness h = new GeEngineHarness().queue(s, s, s, s, s, s, s, s, s, s, s, s, s);
        h.run(new OpenGrandExchangeStep(actions));
        // timeoutTicks=10 + 3 retries; just exercise enough ticks to terminate.
        h.advance(60);

        assertEquals("never-open eventually fails after retries", SequenceState.FAILED, h.state());
        assertTrue("at least one dispatch attempted", actions.callCount() >= 1);
    }
}
