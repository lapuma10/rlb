package net.runelite.client.plugins.recorder.combat;

import net.runelite.api.Skill;
import java.util.Optional;

/**
 * Maps the four combat-style widget slots to the skill they primarily train.
 *
 * <p>Widget slot layout for the two weapon classes we support:
 * <ul>
 *   <li><b>Unarmed (weapon-type 0)</b>: idx 0 = Punch = {@link Skill#ATTACK},
 *       idx 1 = Kick = {@link Skill#STRENGTH}, idx 2 = Block = {@link Skill#DEFENCE}.
 *       Index 3 is unavailable unarmed.</li>
 *   <li><b>Most one-hand swords/scimitars/axes (4-style weapons)</b>: idx 0 = Accurate
 *       = {@link Skill#ATTACK}, idx 1 = Aggressive = {@link Skill#STRENGTH},
 *       idx 2 = Defensive = {@link Skill#DEFENCE}, idx 3 = Controlled = shared
 *       (trains all three at 1.33× rate).</li>
 * </ul>
 *
 * <p>The widget ids for these slots are defined in
 * {@code InterfaceID.CombatInterface._0} through {@code ._3}.
 * VarPlayer 43 ({@code COM_MODE} in {@code VarPlayerID}) reports the currently
 * selected index and matches the {@link #widgetIndex()} of these constants.
 */
public enum CombatStyleIndex
{
    ATTACK_PRIMARY(0),    // Accurate / Punch — trains ATTACK
    STRENGTH_PRIMARY(1),  // Aggressive / Kick — trains STRENGTH
    DEFENCE_PRIMARY(2),   // Defensive / Block — trains DEFENCE
    SHARED_CONTROLLED(3); // Controlled — trains all three (only for some weapon types)

    private final int widgetIndex;

    CombatStyleIndex(int widgetIndex)
    {
        this.widgetIndex = widgetIndex;
    }

    /** Zero-based index of the combat-style button widget (matches VarPlayer 43 values). */
    public int widgetIndex()
    {
        return widgetIndex;
    }

    /**
     * Returns the {@link CombatStyleIndex} that directly trains the given
     * melee skill, or {@link Optional#empty()} if the skill is not a
     * supported melee combat skill.
     *
     * <p>Note: {@link #SHARED_CONTROLLED} is intentionally excluded because it
     * trains all three skills at a reduced rate rather than one primary skill.
     * The rotation logic selects a <em>primary</em> style per skill.
     *
     * @param s the skill to query
     * @return an Optional containing the style that trains {@code s} primarily
     */
    public static Optional<CombatStyleIndex> forSkill(Skill s)
    {
        return switch (s)
        {
            case ATTACK -> Optional.of(ATTACK_PRIMARY);
            case STRENGTH -> Optional.of(STRENGTH_PRIMARY);
            case DEFENCE -> Optional.of(DEFENCE_PRIMARY);
            default -> Optional.empty();
        };
    }
}
