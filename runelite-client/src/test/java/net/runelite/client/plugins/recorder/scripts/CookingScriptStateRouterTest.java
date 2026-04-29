package net.runelite.client.plugins.recorder.scripts;

import org.junit.Test;

import static org.junit.Assert.assertNotNull;

/**
 * Minimal structural test for {@link CookingScript.State} routing:
 * verifies that the enum has both BANKING_LEGACY and BANKING_VIA_ENGINE,
 * and that no lingering BANKING constant remains.
 *
 * <p>End-to-end banking behaviour is covered by BankingSequenceFactoryTest.
 */
public class CookingScriptStateRouterTest
{
    @Test
    public void enumHasBankingLegacy()
    {
        // Throws IllegalArgumentException if the constant doesn't exist.
        CookingScript.State state = CookingScript.State.valueOf("BANKING_LEGACY");
        assertNotNull(state);
    }

    @Test
    public void enumHasBankingViaEngine()
    {
        CookingScript.State state = CookingScript.State.valueOf("BANKING_VIA_ENGINE");
        assertNotNull(state);
    }

    @Test(expected = IllegalArgumentException.class)
    public void enumHasNoBankingConstant()
    {
        // The old BANKING constant must be gone — renamed to BANKING_LEGACY.
        CookingScript.State.valueOf("BANKING");
    }

    @Test
    public void ownerTokenIsNonNull()
    {
        assertNotNull(CookingScript.OWNER_TOKEN);
    }

    @Test
    public void ownerTokenIsNotEmpty()
    {
        org.junit.Assert.assertFalse(CookingScript.OWNER_TOKEN.isEmpty());
    }
}
