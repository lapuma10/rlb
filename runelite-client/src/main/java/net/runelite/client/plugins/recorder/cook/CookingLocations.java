package net.runelite.client.plugins.recorder.cook;

import java.util.List;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.plugins.recorder.walker.PathSpec;

/**
 * Registry of {@link CookingLocation} entries the script can run at.
 *
 * <p>Add new locations here. Each entry is a self-contained
 * {@link CookingLocation} — the script asks for one by label and
 * doesn't hard-code coordinates anywhere else.
 */
public final class CookingLocations
{
    private CookingLocations() {}

    /** Lumbridge Castle plane 2 — bank booth + log spawns inside the
     *  same room. The cookable trip is a couple-tile walk; both paths
     *  are short single-area walks.
     *
     *  <p>Coordinates verified against
     *  {@code ChickenFarmV2Script.BANK_AREA} (3208,3220 5x6 plane=2)
     *  and {@code starter-script}'s log-spawn observations
     *  (3205-3208, 3224-3225, plane=2). */
    public static final CookingLocation LUMBRIDGE_CASTLE_P2 = CookingLocation.builder()
        .label("Lumbridge Castle (P2)")
        .kind(CookingLocation.SourceKind.FIRE_FROM_LOGS)
        .bankArea(new WorldArea(3208, 3220, 5, 6, 2))
        .cookArea(new WorldArea(3205, 3223, 4, 4, 2))
        .bankToCook(PathSpec.builder("lumby-p2-bank-to-cook")
            .walk("logs-spawn", new WorldArea(3205, 3223, 4, 4, 2))
            .build())
        .cookToBank(PathSpec.builder("lumby-p2-cook-to-bank")
            .walk("bank", new WorldArea(3208, 3220, 5, 6, 2))
            .build())
        .heatSourceName("Fire")
        .groundLogsItemId(ItemID.LOGS)
        .build();

    public static List<CookingLocation> all()
    {
        return List.of(LUMBRIDGE_CASTLE_P2);
    }

    public static CookingLocation byLabel(String label)
    {
        for (CookingLocation l : all())
        {
            if (l.label().equalsIgnoreCase(label)) return l;
        }
        return null;
    }
}
