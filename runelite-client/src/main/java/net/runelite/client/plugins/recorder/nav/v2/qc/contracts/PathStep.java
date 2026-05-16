package net.runelite.client.plugins.recorder.nav.v2.qc.contracts;

/** Lane-6 scaffolding mirror of spec §3 {@code PathStep}. Sealed.
 *  Lane 5 produces the production sealed hierarchy under
 *  {@code nav.v2.PathStep}; this mirror exists so Lane-6 tests can
 *  compile without it. */
public sealed interface PathStep permits WalkStep, TransportStep
{
}
