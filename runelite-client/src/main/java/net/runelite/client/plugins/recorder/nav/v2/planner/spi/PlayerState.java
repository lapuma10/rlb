package net.runelite.client.plugins.recorder.nav.v2.planner.spi;

import net.runelite.api.ItemContainer;
import net.runelite.api.Skill;

/** Spec §3 contract: immutable player capability snapshot.
 *
 *  <p><b>Local mock</b>: matches Lane 2's
 *  {@code nav/v2/collision/PlayerState}. Integration consolidates.
 *
 *  <p>Consumed by {@link net.runelite.client.plugins.recorder.nav.v2.transport.TransportRequirement#satisfiedBy(NavigationContext)}
 *  to gate transport availability. */
public interface PlayerState
{
	int skillLevel(Skill skill);
	int boostedLevel(Skill skill);
	int varbit(int varbitId);
	int varplayer(int varpId);
	ItemContainer inventory();
	ItemContainer equipment();
	boolean isMember();
}
