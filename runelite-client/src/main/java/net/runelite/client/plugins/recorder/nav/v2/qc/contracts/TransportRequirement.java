package net.runelite.client.plugins.recorder.nav.v2.qc.contracts;

/** Lane-6 scaffolding mirror of spec §3 {@code TransportRequirement}.
 *  Composable predicate over (skill, varbit, varplayer, inventory,
 *  equipment, membership, spellbook) read via {@link NavigationContext}. */
public interface TransportRequirement
{
    boolean satisfiedBy(NavigationContext ctx);
}
