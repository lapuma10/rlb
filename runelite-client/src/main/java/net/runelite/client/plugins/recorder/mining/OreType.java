/*
 * Copyright (c) 2026, RuneLite
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.client.plugins.recorder.mining;

import net.runelite.api.AnimationID;
import net.runelite.api.ItemID;
import java.util.Set;

/**
 * Per-ore metadata: item id of the dropped ore, level requirement, base XP.
 *
 * <p>Rocks are detected by the verb their {@link net.runelite.api.ObjectComposition}
 * advertises ("Mine"), so we don't need a hard-coded full↔depleted object id
 * pair table — see {@link MiningStateTracker}. The {@code oreItemId} field is
 * the canonical drop, used by {@code PowerMineStrategy} for the "drop ores
 * from inventory" path.
 *
 * <p>Animation IDs are global across ore types (the swing animation depends
 * on the wielded pickaxe, not the rock), so they live in
 * {@link #MINING_ANIMATIONS} as a single set rather than per-enum.
 */
public enum OreType
{
    CLAY("Clay",        ItemID.CLAY,          1,   5.0),
    COPPER("Copper",    ItemID.COPPER_ORE,    1,  17.5),
    TIN("Tin",          ItemID.TIN_ORE,       1,  17.5),
    IRON("Iron",        ItemID.IRON_ORE,     15,  35.0),
    SILVER("Silver",    ItemID.SILVER_ORE,   20,  40.0),
    COAL("Coal",        ItemID.COAL,         30,  50.0),
    GOLD("Gold",        ItemID.GOLD_ORE,     40,  65.0),
    MITHRIL("Mithril",  ItemID.MITHRIL_ORE,  55,  80.0),
    ADAMANTITE("Adamantite", ItemID.ADAMANTITE_ORE, 70,  95.0),
    RUNITE("Runite",    ItemID.RUNITE_ORE,   85, 125.0);

    private final String displayName;
    private final int oreItemId;
    private final int levelReq;
    private final double baseXp;

    OreType(String displayName, int oreItemId, int levelReq, double baseXp)
    {
        this.displayName = displayName;
        this.oreItemId = oreItemId;
        this.levelReq = levelReq;
        this.baseXp = baseXp;
    }

    public String displayName() { return displayName; }
    public int oreItemId() { return oreItemId; }
    public int levelReq() { return levelReq; }
    public double baseXp() { return baseXp; }

    /**
     * Every mining-style player animation we know about. The wielded pickaxe
     * dictates which one is active; we accept the union so a user mid-loop
     * pickaxe-swap doesn't fool the tracker. Includes Motherlode Mine
     * variants (same skill, different scene) and special pickaxes
     * (infernal, 3a, crystal, gilded).
     *
     * <p>Source: {@link AnimationID} constants (line ~195 onward in
     * {@code runelite-api/.../AnimationID.java}).
     */
    public static final Set<Integer> MINING_ANIMATIONS = Set.of(
        AnimationID.MINING_BRONZE_PICKAXE,
        AnimationID.MINING_IRON_PICKAXE,
        AnimationID.MINING_STEEL_PICKAXE,
        AnimationID.MINING_BLACK_PICKAXE,
        AnimationID.MINING_MITHRIL_PICKAXE,
        AnimationID.MINING_ADAMANT_PICKAXE,
        AnimationID.MINING_RUNE_PICKAXE,
        AnimationID.MINING_GILDED_PICKAXE,
        AnimationID.MINING_DRAGON_PICKAXE,
        AnimationID.MINING_DRAGON_PICKAXE_UPGRADED,
        AnimationID.MINING_DRAGON_PICKAXE_OR,
        AnimationID.MINING_DRAGON_PICKAXE_OR_TRAILBLAZER,
        AnimationID.MINING_INFERNAL_PICKAXE,
        AnimationID.MINING_3A_PICKAXE,
        AnimationID.MINING_CRYSTAL_PICKAXE,
        AnimationID.MINING_TRAILBLAZER_PICKAXE,
        AnimationID.MINING_TRAILBLAZER_PICKAXE_2,
        AnimationID.MINING_TRAILBLAZER_PICKAXE_3,
        // Motherlode Mine variants
        AnimationID.MINING_MOTHERLODE_BRONZE,
        AnimationID.MINING_MOTHERLODE_IRON,
        AnimationID.MINING_MOTHERLODE_STEEL,
        AnimationID.MINING_MOTHERLODE_BLACK,
        AnimationID.MINING_MOTHERLODE_MITHRIL,
        AnimationID.MINING_MOTHERLODE_ADAMANT,
        AnimationID.MINING_MOTHERLODE_RUNE,
        AnimationID.MINING_MOTHERLODE_GILDED,
        AnimationID.MINING_MOTHERLODE_DRAGON,
        AnimationID.MINING_MOTHERLODE_DRAGON_UPGRADED,
        AnimationID.MINING_MOTHERLODE_DRAGON_OR,
        AnimationID.MINING_MOTHERLODE_DRAGON_OR_TRAILBLAZER
    );

    /** True if {@code animationId} is a recognised mining swing — the
     *  authoritative "am I currently mining?" signal for {@link MiningStateTracker}. */
    public static boolean isMiningAnimation(int animationId)
    {
        return MINING_ANIMATIONS.contains(animationId);
    }
}
