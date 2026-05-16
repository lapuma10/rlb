package net.runelite.client.plugins.recorder.nav.v2.qc.contracts;

import net.runelite.api.ItemContainer;
import net.runelite.api.Skill;

/** Lane-6 scaffolding mirror of spec §3 {@code PlayerState}.
 *  Snapshotted at plan entry by Lane 2's {@code WorldSnapshotBuilder};
 *  immutable for the duration of one plan call. */
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
