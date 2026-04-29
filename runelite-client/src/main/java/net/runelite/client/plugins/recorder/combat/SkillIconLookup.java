package net.runelite.client.plugins.recorder.combat;

import net.runelite.api.Skill;
import net.runelite.api.gameval.InterfaceID;

/** Maps a {@link Skill} to its icon widget id inside the Stats sidebar
 *  panel (the panel a real player opens by clicking the "Stats" sidebar
 *  tab). The widget id is what {@link
 *  net.runelite.client.sequence.dispatch.PixelResolver#resolveWidget}
 *  needs to produce a humanized click/hover pixel inside the icon's
 *  bounds.
 *
 *  <p>Currently only the three melee combat skills are mapped — they're
 *  the universe of skills the chicken-farm V3 trainer can rotate
 *  through. Add ranged/magic/hp here if/when those rotations land. */
final class SkillIconLookup
{
    private SkillIconLookup() {}

    /** Returns the {@code InterfaceID.Stats} icon widget id for
     *  {@code skill}, or {@code -1} if we don't have a mapping (caller
     *  should skip the hover entirely when this happens). */
    static int widgetIdFor(Skill skill)
    {
        if (skill == null) return -1;
        switch (skill)
        {
            case ATTACK:   return InterfaceID.Stats.ATTACK;
            case STRENGTH: return InterfaceID.Stats.STRENGTH;
            case DEFENCE:  return InterfaceID.Stats.DEFENCE;
            default:       return -1;
        }
    }
}
