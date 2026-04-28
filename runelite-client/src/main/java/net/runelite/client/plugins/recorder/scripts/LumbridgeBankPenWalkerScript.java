package net.runelite.client.plugins.recorder.scripts;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.plugins.recorder.transport.TransportResolver;
import net.runelite.client.plugins.recorder.walker.PathSpec;
import net.runelite.client.plugins.recorder.walker.UniversalWalker;
import net.runelite.client.sequence.dispatch.HumanizedInputDispatcher;

/**
 * Example script demonstrating how to use the
 * {@link net.runelite.client.plugins.recorder.walker walker framework}.
 * Replicates the macro path from {@link LumbridgeBankPenScript} —
 * Lumbridge bank ↔ chicken pen — but the walking is delegated to
 * {@link UniversalWalker} and described as a {@link PathSpec} of
 * waypoints rather than a hand-coded state machine.
 *
 * <p>The original {@link LumbridgeBankPenScript} is left untouched —
 * this class lives alongside it so the user can compare the two. About
 * 20 lines describe the entire route here vs. ~600 lines in the
 * original (the original also handles combat / banking — those concerns
 * stay with the caller; the walker only walks).
 *
 * <p>Wiring this into the panel is left as an exercise for the next
 * commit — the goal of this class is to prove the API surface is small
 * and the path description is purely declarative.
 */
@Slf4j
public final class LumbridgeBankPenWalkerScript
{
    // Identical landmarks to LumbridgeBankPenScript so the routes are
    // byte-for-byte comparable.
    private static final WorldArea STAIRS_LANDING_P0 = new WorldArea(3206, 3227, 4, 2, 0);
    private static final WorldArea CASTLE_YARD       = new WorldArea(3219, 3217, 9, 4, 0);
    private static final WorldArea STONE_BRIDGE      = new WorldArea(3237, 3225, 7, 2, 0);
    private static final WorldArea GOBLIN_FENCE      = new WorldArea(3259, 3234, 2, 4, 0);
    private static final WorldArea COW_FENCE         = new WorldArea(3249, 3254, 4, 4, 0);
    private static final WorldArea PEN_APPROACH      = new WorldArea(3238, 3289, 3, 8, 0);
    private static final WorldArea PEN_INTERIOR      = new WorldArea(3225, 3290, 13, 12, 0);

    /** Stair tiles. Picked from the route file — the verb resolver will
     *  find the actual staircase object on these tiles. */
    private static final WorldPoint STAIRS_TILE_P2_DOWN = new WorldPoint(3205, 3229, 2);
    private static final WorldPoint STAIRS_TILE_P1_DOWN = new WorldPoint(3205, 3229, 1);
    private static final WorldPoint STAIRS_TILE_P0_UP   = new WorldPoint(3205, 3229, 0);
    private static final WorldPoint STAIRS_TILE_P1_UP   = new WorldPoint(3205, 3229, 1);

    /** Pen gate tile — the wall object exposes "Open" when closed and
     *  "Close" when open; the framework handles both. */
    private static final WorldPoint PEN_GATE = new WorldPoint(3236, 3296, 0);

    private final UniversalWalker walker;

    public LumbridgeBankPenWalkerScript(Client client, ClientThread clientThread,
                                        HumanizedInputDispatcher dispatcher,
                                        TransportResolver resolver)
    {
        this.walker = new UniversalWalker(client, clientThread, dispatcher, resolver);
    }

    /** Build the bank → pen path. Plane 2 → 0 via the staircase, then
     *  walk the landmark chain to the gate, open it, walk into the
     *  pen interior. */
    public static PathSpec outbound()
    {
        return PathSpec.builder("lumbridge-out")
            .climbDown(STAIRS_TILE_P2_DOWN)        // 2 → 1
            .climbDown(STAIRS_TILE_P1_DOWN)        // 1 → 0
            .walk("stairs-landing", STAIRS_LANDING_P0)
            .walk("castle-yard", CASTLE_YARD)
            .walk("stone-bridge", STONE_BRIDGE)
            .walk("goblin-fence", GOBLIN_FENCE)
            .walk("cow-fence", COW_FENCE)
            .walk("pen-approach", PEN_APPROACH)
            .gate(PEN_GATE)
            .walk("pen", PEN_INTERIOR)
            .build();
    }

    /** Pen → bank path: reverse of {@link #outbound()}. */
    public static PathSpec returnToBank()
    {
        return PathSpec.builder("lumbridge-return")
            .walk("pen-approach", PEN_APPROACH)
            .gate(PEN_GATE)
            .walk("cow-fence", COW_FENCE)
            .walk("goblin-fence", GOBLIN_FENCE)
            .walk("stone-bridge", STONE_BRIDGE)
            .walk("castle-yard", CASTLE_YARD)
            .walk("stairs-landing", STAIRS_LANDING_P0)
            .climbUp(STAIRS_TILE_P0_UP)            // 0 → 1
            .climbUp(STAIRS_TILE_P1_UP)            // 1 → 2
            .build();
    }

    /** Drive {@code spec} to completion. Returns the walker status when
     *  the loop terminates. The caller controls the sleep cadence —
     *  600ms is a sweet spot for OSRS tick alignment. */
    public UniversalWalker.Status driveUntilDone(PathSpec spec, long sleepMs)
        throws InterruptedException
    {
        UniversalWalker.Status st;
        while (true)
        {
            st = walker.tick(spec);
            if (st == UniversalWalker.Status.ARRIVED
                || st == UniversalWalker.Status.STUCK
                || st == UniversalWalker.Status.ERROR)
            {
                return st;
            }
            Thread.sleep(sleepMs);
        }
    }

    public UniversalWalker walker() { return walker; }
}
