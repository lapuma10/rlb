package net.runelite.client.plugins.recorder.scripts;

import java.io.IOException;
import java.nio.file.Files;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.plugins.recorder.trail.TrailRegistry;
import net.runelite.client.sequence.dispatch.HumanizedInputDispatcher;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class ChickenFarmV3ScriptTest
{
    @Test
    public void freshScriptIsIdle() throws IOException
    {
        // TrailRegistry and TransportResolver are final — use a real registry
        // pointed at an empty temp dir, and pass null for the resolver
        // (the constructor accepts it but never stores it; the script only
        // uses it if start() is called, which this test does not do).
        TrailRegistry reg = new TrailRegistry(Files.createTempDirectory("v3-test-"));
        ChickenFarmV3Script s = new ChickenFarmV3Script(
            mock(Client.class), mock(ClientThread.class),
            mock(HumanizedInputDispatcher.class),
            null, reg);
        assertEquals(ChickenFarmV3Script.State.IDLE, s.state());
    }

    @Test
    public void trailNamesAreThoseSpecified()
    {
        // The script reads two specific trail names from the registry —
        // 'lumby-bank-to-pen' and 'pen-to-lumby-bank'. These are the
        // names the user is told to record.
        assertEquals("lumby-bank-to-pen", ChickenFarmV3Script.OUTBOUND_TRAIL_NAME);
        assertEquals("pen-to-lumby-bank", ChickenFarmV3Script.RETURN_TRAIL_NAME);
    }
}
