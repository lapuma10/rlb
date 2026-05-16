package net.runelite.client.plugins.recorder.nav.v2.qc.contracts;

/** Lane-6 scaffolding mirror of spec §3 {@code CollisionFlags}. Bit-set
 *  per-direction-of-travel collision data, as returned by Lane 2's
 *  {@code CollisionView.flagsAt(WorldPoint)}. Mirrors the Jagex
 *  {@code CollisionDataFlag} surface but stays type-distinct so the
 *  Lane-2 owner can replace the int with a richer record without
 *  changing call sites in the harness. */
public record CollisionFlags(int raw, Source source, int plane)
{
    public enum Source
    {
        GLOBAL_SNAPSHOT,
        LIVE_OVERLAY,
        UNKNOWN
    }
}
