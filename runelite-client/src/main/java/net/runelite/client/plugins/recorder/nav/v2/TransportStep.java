package net.runelite.client.plugins.recorder.nav.v2;

/** Spec §3 (Lane 1 contract; Lane 5 file owner): a transport segment in
 *  a {@link V2Path}. The executor switches on this step type to drive
 *  the typed verb-on-object / item-use pipeline. */
public non-sealed interface TransportStep extends PathStep
{
    TransportLeg transport();
}
