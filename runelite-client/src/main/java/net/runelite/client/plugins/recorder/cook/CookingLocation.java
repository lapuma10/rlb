package net.runelite.client.plugins.recorder.cook;

import net.runelite.api.coords.WorldArea;
import net.runelite.client.plugins.recorder.walker.PathSpec;

/**
 * Config record for a cooking location.
 *
 * <p>A location bundles together the bank/cook areas, the walking
 * specs between them, and the heat-source kind (logs-on-the-ground
 * fire, or a fixed cooking range). The script picks one of these and
 * runs.
 */
public final class CookingLocation
{
    public enum SourceKind
    {
        /** Use a Tinderbox + Logs spawn on the ground to make a fire. */
        FIRE_FROM_LOGS,
        /** Use a fixed cooking Range game object. */
        RANGE
    }

    private final String label;
    private final SourceKind kind;
    private final WorldArea bankArea;
    private final WorldArea cookArea;
    private final PathSpec bankToCook;
    private final PathSpec cookToBank;
    /** Composition-name pattern for the heat source. Matched
     *  case-insensitively against {@code ObjectComposition.getName()}.
     *  e.g. "Fire" for fire-on-logs locations, "Range" for ranges. */
    private final String heatSourceName;
    /** Ground-item id for the log spawn (FIRE_FROM_LOGS only). 0 for
     *  ranges. Different log types (oak / willow / yew) all light the
     *  same way but have distinct ids — locations that pick a tier
     *  set this to the matching {@code ItemID} constant. */
    private final int groundLogsItemId;
    /** {@code ObjectID} constants for the bank booths at this location.
     *  Used by the sequence-engine banking steps to find a booth to click
     *  when the NPC scan finds no bankers.  Empty array = NPC-only location. */
    private final int[] bankBoothIds;

    private CookingLocation(Builder b)
    {
        this.label = b.label;
        this.kind = b.kind;
        this.bankArea = b.bankArea;
        this.cookArea = b.cookArea;
        this.bankToCook = b.bankToCook;
        this.cookToBank = b.cookToBank;
        this.heatSourceName = b.heatSourceName;
        this.groundLogsItemId = b.groundLogsItemId;
        this.bankBoothIds = b.bankBoothIds.clone();
    }

    public String label() { return label; }
    public SourceKind kind() { return kind; }
    public WorldArea bankArea() { return bankArea; }
    public WorldArea cookArea() { return cookArea; }
    public PathSpec bankToCook() { return bankToCook; }
    public PathSpec cookToBank() { return cookToBank; }
    public String heatSourceName() { return heatSourceName; }
    public int groundLogsItemId() { return groundLogsItemId; }
    /** Defensive copy of the bank-booth ObjectID array. */
    public int[] bankBoothIds() { return bankBoothIds.clone(); }

    public static Builder builder() { return new Builder(); }

    @Override public String toString() { return label; }

    public static final class Builder
    {
        private String label;
        private SourceKind kind;
        private WorldArea bankArea;
        private WorldArea cookArea;
        private PathSpec bankToCook;
        private PathSpec cookToBank;
        private String heatSourceName;
        private int groundLogsItemId;
        private int[] bankBoothIds = new int[0];

        public Builder label(String s) { this.label = s; return this; }
        public Builder kind(SourceKind k) { this.kind = k; return this; }
        public Builder bankArea(WorldArea a) { this.bankArea = a; return this; }
        public Builder cookArea(WorldArea a) { this.cookArea = a; return this; }
        public Builder bankToCook(PathSpec p) { this.bankToCook = p; return this; }
        public Builder cookToBank(PathSpec p) { this.cookToBank = p; return this; }
        public Builder heatSourceName(String s) { this.heatSourceName = s; return this; }
        public Builder groundLogsItemId(int id) { this.groundLogsItemId = id; return this; }
        public Builder bankBoothIds(int... ids) { this.bankBoothIds = ids == null ? new int[0] : ids; return this; }

        public CookingLocation build()
        {
            if (label == null || label.isBlank())
                throw new IllegalArgumentException("label required");
            if (kind == null)
                throw new IllegalArgumentException("kind required");
            if (bankArea == null || cookArea == null)
                throw new IllegalArgumentException("bankArea + cookArea required");
            if (bankToCook == null || cookToBank == null)
                throw new IllegalArgumentException("bankToCook + cookToBank PathSpecs required");
            if (heatSourceName == null || heatSourceName.isBlank())
                throw new IllegalArgumentException("heatSourceName required");
            if (kind == SourceKind.FIRE_FROM_LOGS && groundLogsItemId <= 0)
                throw new IllegalArgumentException("groundLogsItemId required for FIRE_FROM_LOGS");
            return new CookingLocation(this);
        }
    }
}
