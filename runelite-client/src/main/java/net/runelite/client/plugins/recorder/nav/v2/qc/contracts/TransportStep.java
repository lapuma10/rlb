package net.runelite.client.plugins.recorder.nav.v2.qc.contracts;

/** Lane-6 scaffolding mirror of spec §3 {@code TransportStep}. */
public non-sealed interface TransportStep extends PathStep
{
    TransportLeg transport();
}
