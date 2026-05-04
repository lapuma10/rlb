package net.runelite.client.plugins.recorder.farm;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Unit tests for the pure formatting helpers added 2026-05-04 alongside
 * the Withdraw-X fast path:
 * <ul>
 *   <li>{@link BankInteraction#formatChatboxQty} — "Nk"/"Nm" collapse for
 *       clean multiples; plain digits otherwise.</li>
 *   <li>{@link BankInteraction#roundUpForFastTyping} — opt-in round-up
 *       that pairs with formatChatboxQty for coin sites.</li>
 * </ul>
 */
public class BankInteractionFormatTest
{
    // ─── formatChatboxQty ──────────────────────────────────────────────

    @Test
    public void formatChatboxQty_smallNumbers_plainDigits()
    {
        assertEquals("0",   BankInteraction.formatChatboxQty(0));
        assertEquals("1",   BankInteraction.formatChatboxQty(1));
        assertEquals("9",   BankInteraction.formatChatboxQty(9));
        assertEquals("14",  BankInteraction.formatChatboxQty(14));
        assertEquals("447", BankInteraction.formatChatboxQty(447));
        assertEquals("999", BankInteraction.formatChatboxQty(999));
    }

    @Test
    public void formatChatboxQty_cleanThousands_collapsedToK()
    {
        assertEquals("1k",    BankInteraction.formatChatboxQty(1_000));
        assertEquals("10k",   BankInteraction.formatChatboxQty(10_000));
        assertEquals("51k",   BankInteraction.formatChatboxQty(51_000));
        assertEquals("999k",  BankInteraction.formatChatboxQty(999_000));
        // Awkward but legal: 1003k stays as k since it's not a clean million.
        assertEquals("1003k", BankInteraction.formatChatboxQty(1_003_000));
    }

    @Test
    public void formatChatboxQty_cleanMillions_collapsedToM()
    {
        assertEquals("1m",   BankInteraction.formatChatboxQty(1_000_000));
        assertEquals("2m",   BankInteraction.formatChatboxQty(2_000_000));
        assertEquals("100m", BankInteraction.formatChatboxQty(100_000_000));
    }

    @Test
    public void formatChatboxQty_offByOne_fallsBackToDigits()
    {
        assertEquals("999",     BankInteraction.formatChatboxQty(999));
        assertEquals("1001",    BankInteraction.formatChatboxQty(1_001));
        assertEquals("50432",   BankInteraction.formatChatboxQty(50_432));
        assertEquals("1002213", BankInteraction.formatChatboxQty(1_002_213));
        // Clean k but not clean m — still formats as k.
        assertEquals("1001k",   BankInteraction.formatChatboxQty(1_001_000));
    }

    @Test
    public void formatChatboxQty_negative_fallsThroughToDigits()
    {
        // Not meaningful for withdraws but documents the contract: no
        // mangled output.
        assertEquals("-1",   BankInteraction.formatChatboxQty(-1));
        assertEquals("-100", BankInteraction.formatChatboxQty(-100));
    }

    // ─── roundUpForFastTyping ──────────────────────────────────────────

    @Test
    public void roundUpForFastTyping_underThreshold_passthrough()
    {
        // Below 10_000 the overshoot ratio isn't worth a couple of chars.
        assertEquals(0,    BankInteraction.roundUpForFastTyping(0));
        assertEquals(1,    BankInteraction.roundUpForFastTyping(1));
        assertEquals(447,  BankInteraction.roundUpForFastTyping(447));
        assertEquals(9_999, BankInteraction.roundUpForFastTyping(9_999));
    }

    @Test
    public void roundUpForFastTyping_atOrAboveThreshold_roundsUp()
    {
        assertEquals(10_000,    BankInteraction.roundUpForFastTyping(10_000));   // already clean
        assertEquals(11_000,    BankInteraction.roundUpForFastTyping(10_001));
        assertEquals(51_000,    BankInteraction.roundUpForFastTyping(50_432));
        assertEquals(1_003_000, BankInteraction.roundUpForFastTyping(1_002_213));
    }

    @Test
    public void roundUpForFastTyping_alreadyClean_passthrough()
    {
        assertEquals(51_000,    BankInteraction.roundUpForFastTyping(51_000));
        assertEquals(1_000_000, BankInteraction.roundUpForFastTyping(1_000_000));
    }

    @Test
    public void roundUpAndFormat_compose()
    {
        // The two helpers are designed to compose: roundUp then format
        // gives the typed string the caller actually sees.
        int rounded = BankInteraction.roundUpForFastTyping(50_432);
        assertEquals("51k", BankInteraction.formatChatboxQty(rounded));

        rounded = BankInteraction.roundUpForFastTyping(1_002_213);
        assertEquals("1003k", BankInteraction.formatChatboxQty(rounded));

        // Under threshold: no rounding, no collapse — types literal.
        rounded = BankInteraction.roundUpForFastTyping(447);
        assertEquals("447", BankInteraction.formatChatboxQty(rounded));
    }
}
