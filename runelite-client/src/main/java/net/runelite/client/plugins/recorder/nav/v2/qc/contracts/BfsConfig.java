package net.runelite.client.plugins.recorder.nav.v2.qc.contracts;

/** Lane-6 scaffolding mirror of spec §3 {@code BfsConfig}. Lane 3 owns
 *  the production type — carries the (cardinal-fixed, diagonal-permuted)
 *  expansion order seed and the 128×128 bound mentioned in §4 Lane 3. */
public record BfsConfig(long routeSeed, int diagonalPermutation, int maxBound)
{
    public static BfsConfig defaults() { return new BfsConfig(0L, 0, 128); }
}
