package net.runelite.client.plugins.recorder.nav.v2.collision;

import net.runelite.api.ItemContainer;
import net.runelite.api.Skill;

/** Immutable snapshot of the player's capability state, captured at
 *  the same client-thread instant as the matching {@link WorldSnapshot}.
 *
 *  <p>Per spec §3, this is consumed by {@code TransportRequirement.satisfiedBy}
 *  (Lane 4) and other read-only callers. {@code ItemContainer} references
 *  are NOT necessarily defensive copies — RuneLite's containers
 *  themselves are mostly immutable per-tick, but consumers should treat
 *  the returned object as read-only and not write to it.
 *
 *  <p>{@link #varbit(int)} / {@link #varplayer(int)} are lazy: invoking
 *  them after the snapshot point may marshall back to the client thread
 *  for a fresh read. The plan-call entry pre-fetches the varbits it
 *  knows it needs; out-of-band callers pay the marshall cost. This is
 *  documented per {@link PlayerStateBuilder}'s contract. */
public interface PlayerState
{
    /** The real (un-boosted) level for the given skill. */
    int skillLevel(Skill skill);

    /** The boosted level — what the game uses for level-gated actions. */
    int boostedLevel(Skill skill);

    /** Varbit value at capture time, or freshly fetched if {@link PlayerStateBuilder}
     *  was configured lazy. */
    int varbit(int varbitId);

    /** Varplayer value — same lifecycle as {@link #varbit(int)}. */
    int varplayer(int varpId);

    /** Inventory snapshot. Treat as read-only. */
    ItemContainer inventory();

    /** Equipment snapshot. Treat as read-only. */
    ItemContainer equipment();

    /** True iff the player is on a members' world. Drives membership-gated
     *  transport requirement checks. */
    boolean isMember();
}
