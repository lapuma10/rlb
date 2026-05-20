package net.runelite.client.plugins.recorder.quest;

import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.plugins.recorder.nav.v2.V2Navigator;
import net.runelite.client.plugins.recorder.npc.NpcInteraction;
import net.runelite.client.plugins.recorder.quest.steps.CombineItemsStep;
import net.runelite.client.plugins.recorder.quest.steps.InteractWithObjectStep;
import net.runelite.client.plugins.recorder.quest.steps.NavWalkStep;
import net.runelite.client.plugins.recorder.quest.steps.ReplayTrailStep;
import net.runelite.client.plugins.recorder.quest.steps.TakeGroundItemStep;
import net.runelite.client.plugins.recorder.quest.steps.TalkToNpcStep;
import net.runelite.client.plugins.recorder.quest.steps.UseItemOnObjectStep;
import net.runelite.client.plugins.recorder.scene.SceneScanner;
import net.runelite.client.plugins.recorder.trail.TrailPath;
import net.runelite.client.plugins.recorder.trail.TrailWalker;
import net.runelite.client.sequence.WorldSnapshot;
import net.runelite.client.sequence.activities.WalkStep;
import net.runelite.client.sequence.activities.banking.HaveAtLeastInInventoryStep;
import net.runelite.client.sequence.composite.LinearSequence;
import net.runelite.client.sequence.composite.Selector;
import net.runelite.client.sequence.dispatch.HumanizedInputDispatcher;

/**
 * Ernest the Chicken — the first quest script. Translated from a working
 * OSBot reference; item ids, tile coords, and dialog options are 1:1.
 *
 * <p>Construct via {@link #build(...)} (static factory rather than a
 * constructor so we can fail fast on item-id lookup before mutating
 * any LinearSequence state).
 *
 * <p>See {@code docs/superpowers/specs/2026-05-18-ernest-the-chicken-design.md}
 * for the full design rationale.
 */
@Slf4j
public final class ErnestTheChicken {

    private ErnestTheChicken() {}

    /** Dialog options for both Veronica and Professor Oddenstein — both
     *  NPCs use the same option-array trick (first-match-wins across the
     *  whole dialogue tree, so the same array drives every interaction). */
    private static final String[] DIALOG_OPTIONS = new String[]{
        "Yes.",
        "Aha, sounds like a quest. I'll help.",
        "I'm looking for a guy called Ernest.",
        "Change him back this instant!",
    };

    // ─── Tiles & areas (1:1 with OSBot reference) ───────────────────────────

    private static final WorldPoint VERONICA_TILE        = new WorldPoint(3110, 3327, 0);
    private static final WorldPoint STAIRS_BASE          = new WorldPoint(3109, 3361, 0);
    private static final WorldPoint PROFESSOR_TILE       = new WorldPoint(3109, 3365, 2);
    private static final WorldPoint FISH_FOOD_ROOM       = new WorldPoint(3109, 3357, 1);
    private static final WorldPoint POISON_DOOR_1        = new WorldPoint(3102, 3371, 0);
    private static final WorldPoint POISON_DOOR_2        = new WorldPoint(3099, 3367, 0);
    private static final WorldPoint POISON_ROOM          = new WorldPoint(3098, 3366, 0);
    private static final WorldPoint LIVINGROOM_BOOKCASE  = new WorldPoint(3098, 3359, 0);
    private static final WorldPoint LADDER_DOWN_TILE     = new WorldPoint(3092, 3361, 0);
    private static final WorldPoint SPADE_TILE           = new WorldPoint(3120, 3359, 0);
    private static final WorldPoint BACKDOOR_TILE        = new WorldPoint(3102, 3379, 0);
    private static final WorldPoint COMPOST_TILE         = new WorldPoint(3086, 3360, 0);
    private static final WorldPoint FOUNTAIN_TILE        = new WorldPoint(3089, 3336, 0);
    private static final WorldPoint SKELETON_ROOM_WALK   = new WorldPoint(3107, 3367, 0);
    private static final WorldPoint RUBBER_TUBE_TILE     = new WorldPoint(3111, 3367, 0);
    private static final WorldPoint EXIT_LEVER_TILE      = new WorldPoint(3096, 3357, 0);

    private static final WorldPoint DRAYNOR_BANK_ARRIVED = new WorldPoint(3092, 3243, 0);

    /** Builds the full Ernest sequence.
     *
     *  @param lumbridgeToDraynor pre-loaded trail; preflight replays it
     *      when the player starts at Lumbridge bank P2. May be null only
     *      if the caller guarantees the player is already at Draynor bank
     *      (preflight will then ALREADY_SATISFIED on the inventory-less
     *      check). */
    public static LinearSequence build(Client client,
                                       ClientThread clientThread,
                                       NpcInteraction npc,
                                       SceneScanner scanner,
                                       HumanizedInputDispatcher dispatcher,
                                       TrailWalker trailWalker,
                                       V2Navigator nav,
                                       TrailPath lumbridgeToDraynor,
                                       TrailPath draynorBankToManor,
                                       BooleanSupplier abortRequested) {
        // Item ids — compile-time constants from RuneLite's gameval. Quest
        // items aren't in the GE search index so we can't ItemManager-lookup
        // them; we hardcode the ids that have been stable since classic RS.
        final int fishFood      = ItemID.FISH_FOOD;          // 272
        final int poison        = ItemID.POISON;             // 273
        final int poisonedFood  = ItemID.POISONED_FISH_FOOD; // 274
        final int spade         = ItemID.SPADE;              // 952
        final int key           = ItemID.CLOSET_KEY;         // 275 — Ernest's compost-heap key
        final int pressureGauge = ItemID.PRESSURE_GAUGE;     // 271
        final int rubberTube    = ItemID.RUBBER_TUBE;        // 276
        final int oilCan        = ItemID.OIL_CAN;            // 277

        Predicate<WorldSnapshot> questStarted  = snap ->
            Quest.ERNEST_THE_CHICKEN.getState(client) != QuestState.NOT_STARTED;
        Predicate<WorldSnapshot> questFinished = snap ->
            Quest.ERNEST_THE_CHICKEN.getState(client) == QuestState.FINISHED;
        // Bounding-box check so the lumby→draynor trail-replay step is
        // skipped any time the player is already past Lumbridge — at the
        // bank, in the village, or anywhere up to the manor. Tight
        // distance-from-bank radius (8) would only cover the bank itself
        // and stuck the script when the user started mid-route.
        Predicate<WorldSnapshot> playerAtDraynor    = inDraynorOrManorArea();
        // "Reached Veronica" = within talking radius OR past her tile —
        // either we walked to her (fresh run) or we're past her already
        // (resume from mid-quest). The trail walker is monotonic-forward,
        // so if we're past Veronica without this, the step would never
        // satisfy and would time out.
        Predicate<WorldSnapshot> playerNearVeronica = playerNear(VERONICA_TILE, 6)
            .or(pastTile(VERONICA_TILE));
        // "Reached stairs base" = at the tile OR past it (upper floors).
        Predicate<WorldSnapshot> playerAtStairsBase = playerNear(STAIRS_BASE, 2)
            .or(pastTile(STAIRS_BASE));

        return new LinearSequence("ErnestTheChicken")
            // Preflight — get us to Draynor bank if at Lumbridge bank P2.
            .then(new ReplayTrailStep(client, trailWalker, lumbridgeToDraynor,
                "Lumby→Draynor", playerAtDraynor, abortRequested))

            // Walk past Veronica using the recorded draynor_bank_to_manor trail.
            // Stop when player is near her tile; talk; then resume the same trail
            // to the manor stairs. TrailWalker.reset() + chooseLegIndex picks up
            // from the current player position on the second invocation.
            .then(new ReplayTrailStep(client, trailWalker, draynorBankToManor,
                "Bank→Veronica", playerNearVeronica, abortRequested))
            .then(new TalkToNpcStep(npc, clientThread, "Veronica", null, DIALOG_OPTIONS, questStarted))

            // Resume the same trail to the stairs base inside the manor.
            .then(new ReplayTrailStep(client, trailWalker, draynorBankToManor,
                "Veronica→StairsBase", playerAtStairsBase, abortRequested))
            // Draynor Manor has two staircase flights: p=0→p=1 (south stairs),
            // then p=1→p=2 (id=11511, ~4 tiles NW on p=1). The professor's exact
            // tile is blocked in the static collision map (NPC occupancy), so nav
            // can't BFS to it. We climb both flights explicitly, then use a wide
            // arrival radius — the player lands ~4 tiles from the professor after
            // the second climb, which passes withinArrival immediately.
            .then(new InteractWithObjectStep(scanner, clientThread, "Staircase", 5, "Climb-up", playerOnPlane(1)))
            .then(new InteractWithObjectStep(scanner, clientThread, 11511, 6, "Climb-up", playerOnPlane(2)))
            .then(new InteractWithObjectStep(scanner, clientThread, 11470, 4, "Open", doorNotPresent(scanner, 11470, 5)))
            .then(new NavWalkStep(nav, PROFESSOR_TILE, 5))
            // skipWhen = already has any quest item (mid-quest resume); doneWhen = snap->true
            // so the step always advances once the dialogue completes on a fresh run.
            .then(new TalkToNpcStep(npc, clientThread, "Professor Oddenstein", null, DIALOG_OPTIONS,
                hasAny(fishFood, poison, oilCan, key, pressureGauge, rubberTube),
                snap -> true))

            // Fish food — middle floor. Plane change from p2 → p1 handled by nav transports.
            .then(new NavWalkStep(nav, FISH_FOOD_ROOM, 2))
            .then(new TakeGroundItemStep(scanner, clientThread, fishFood, 8, "Fish food"))

            // Poison — through two doors on ground floor. Plane change p1 → p0.
            .then(new NavWalkStep(nav, POISON_DOOR_1, 1))
            .then(new InteractWithObjectStep(scanner, clientThread, "Door", 3, "Open", afterTicks(2)))
            .then(new NavWalkStep(nav, POISON_DOOR_2, 1))
            .then(new InteractWithObjectStep(scanner, clientThread, "Door", 3, "Open", afterTicks(2)))
            .then(new NavWalkStep(nav, POISON_ROOM, 2))
            .then(new TakeGroundItemStep(scanner, clientThread, poison, 8, "Poison"))

            // Living-room bookcase — opens the hidden door.
            .then(new NavWalkStep(nav, LIVINGROOM_BOOKCASE, 2))
            .then(new InteractWithObjectStep(scanner, clientThread, "Bookcase", 4, "Search", afterTicks(3)))

            // Combine poison + fish food.
            .then(new CombineItemsStep(dispatcher, poison, fishFood, poisonedFood, "Poisoned fish food"))

            // Lever puzzle → Oil can (skipped when already in inventory).
            .then(buildLeverPuzzle(client, clientThread, scanner, dispatcher, nav, oilCan))

            // Spade → compost heap → Key. Spade is across the manor.
            .then(new NavWalkStep(nav, SPADE_TILE, 1))
            .then(new TakeGroundItemStep(scanner, clientThread, spade, 5, "Spade"))
            .then(new NavWalkStep(nav, BACKDOOR_TILE, 1))
            .then(new NavWalkStep(nav, COMPOST_TILE, 2))
            .then(new InteractWithObjectStep(scanner, clientThread, "Compost heap", 4, "Search",
                invHas(key)))

            // Fountain → Pressure gauge (use poisoned fish food, wait, search).
            .then(new NavWalkStep(nav, FOUNTAIN_TILE, 2))
            .then(new UseItemOnObjectStep(scanner, clientThread, dispatcher, poisonedFood,
                "Fountain", 4, "PoisonedFood→Fountain", afterTicks(15)))   // ~9s wait for piranhas
            .then(new InteractWithObjectStep(scanner, clientThread, "Fountain", 4, "Search",
                invHas(pressureGauge)))

            // Skeleton room → Rubber tube. Cross-manor again.
            .then(new NavWalkStep(nav, SKELETON_ROOM_WALK, 2))
            .then(new WalkStep(RUBBER_TUBE_TILE, 1))   // in-scene from SKELETON_ROOM_WALK
            .then(new TakeGroundItemStep(scanner, clientThread, rubberTube, 6, "Rubber tube"))

            // Final hand-in — Professor Oddenstein assembles the machine.
            // Nav cannot BFS to the professor's exact tile (NPC-blocked); climb
            // both staircase flights explicitly, same as the initial visit.
            .then(new NavWalkStep(nav, STAIRS_BASE, 3))
            .then(new InteractWithObjectStep(scanner, clientThread, "Staircase", 5, "Climb-up", playerOnPlane(1)))
            .then(new InteractWithObjectStep(scanner, clientThread, 11511, 6, "Climb-up", playerOnPlane(2)))
            .then(new InteractWithObjectStep(scanner, clientThread, 11470, 4, "Open", doorNotPresent(scanner, 11470, 5)))
            .then(new NavWalkStep(nav, PROFESSOR_TILE, 5))
            .then(new TalkToNpcStep(npc, clientThread, "Professor Oddenstein", null, DIALOG_OPTIONS, questFinished));
    }

    // ─── Lever puzzle ───────────────────────────────────────────────────────

    /**
     * Wraps the basement lever sequence in a {@link Selector} that
     * short-circuits when the Oil can is already in inventory. On a fresh
     * run (or a crash where we lost the can), the inner LinearSequence
     * runs the canonical 14-pull + 8-door-open + ladder route from the
     * OSBot reference.
     *
     * <p>Re-running the inner sequence from an arbitrary lever state may
     * leave levers wrong mid-route — accept as documented in the spec.
     * If we observe in-game that re-runs don't recover, fall back to
     * per-lever varbit checks.
     */
    private static net.runelite.client.sequence.Step buildLeverPuzzle(
            Client client, ClientThread ct, SceneScanner ss,
            HumanizedInputDispatcher disp, V2Navigator nav, int oilCanId) {

        // Door object ids from the OSBot reference — each id picks the
        // exact door instance among the basement clusters where multiple
        // doors share the display name "Door".
        final int DOOR_145 = 145;
        final int DOOR_140 = 140;
        final int DOOR_143 = 143;
        final int DOOR_138 = 138;
        final int DOOR_137 = 137;
        final int DOOR_142 = 142;
        final int DOOR_141 = 141;

        LinearSequence inner = new LinearSequence("LeverPuzzleInner")
            // Down the ladder first — could be cross-scene from the bookcase room.
            .then(new NavWalkStep(nav, LADDER_DOWN_TILE, 1))
            .then(new InteractWithObjectStep(ss, ct, "Ladder", 3, "Climb-down", playerYAbove(9000)))

            // Pull B (start), south to A.
            .then(new InteractWithObjectStep(ss, ct, "Lever B", 8, "Pull", afterTicks(3)))
            .then(new WalkStep(new WorldPoint(3108, 9745, 0), 1))
            .then(new InteractWithObjectStep(ss, ct, "Lever A", 8, "Pull", afterTicks(2)))

            // North through door, pull D, back south through door.
            // (OSBot uses generic openDoor() here — no id specified, falls
            //  back to closest "Door" name match.)
            .then(new WalkStep(new WorldPoint(3108, 9757, 0), 1))
            .then(new InteractWithObjectStep(ss, ct, "Door", 3, "Open", afterTicks(2)))
            .then(new WalkStep(new WorldPoint(3108, 9767, 0), 1))
            .then(new InteractWithObjectStep(ss, ct, "Lever D", 8, "Pull", afterTicks(2)))
            .then(new WalkStep(new WorldPoint(3108, 9759, 0), 1))
            .then(new InteractWithObjectStep(ss, ct, "Door", 3, "Open", afterTicks(2)))

            // East to alt B, pull B + A again.
            .then(new WalkStep(new WorldPoint(3118, 9752, 0), 1))
            .then(new InteractWithObjectStep(ss, ct, "Lever B", 8, "Pull", afterTicks(2)))
            .then(new InteractWithObjectStep(ss, ct, "Lever A", 8, "Pull", afterTicks(2)))

            // NW corner — three doors to F/E room (ids 145, 140, 143).
            .then(new WalkStep(new WorldPoint(3102, 9757, 0), 1))
            .then(new InteractWithObjectStep(ss, ct, DOOR_145, 3, "Open", afterTicks(2)))
            .then(new WalkStep(new WorldPoint(3101, 9760, 0), 1))
            .then(new InteractWithObjectStep(ss, ct, DOOR_140, 3, "Open", afterTicks(2)))
            .then(new WalkStep(new WorldPoint(3097, 9762, 0), 1))
            .then(new InteractWithObjectStep(ss, ct, DOOR_143, 3, "Open", afterTicks(2)))
            .then(new WalkStep(new WorldPoint(3096, 9765, 0), 1))

            // Pull F, then E.
            .then(new InteractWithObjectStep(ss, ct, "Lever F", 8, "Pull", afterTicks(2)))
            .then(new InteractWithObjectStep(ss, ct, "Lever E", 8, "Pull", afterTicks(2)))

            // East through two doors to C (ids 138, 137).
            .then(new WalkStep(new WorldPoint(3099, 9765, 0), 1))
            .then(new InteractWithObjectStep(ss, ct, DOOR_138, 3, "Open", afterTicks(2)))
            .then(new WalkStep(new WorldPoint(3104, 9765, 0), 1))
            .then(new InteractWithObjectStep(ss, ct, DOOR_137, 3, "Open", afterTicks(2)))
            .then(new WalkStep(new WorldPoint(3112, 9760, 0), 1))
            .then(new InteractWithObjectStep(ss, ct, "Lever C", 8, "Pull", afterTicks(2)))

            // Back west, pull E again. (OSBot script jumps to coord 1097 here
            // — a deliberate "walk fails silently, closest-lever fires
            //  regardless" trick. Preserved verbatim from the working ref.)
            .then(new WalkStep(new WorldPoint(3106, 9765, 0), 1))
            .then(new InteractWithObjectStep(ss, ct, DOOR_137, 3, "Open", afterTicks(2)))
            .then(new WalkStep(new WorldPoint(3101, 9765, 0), 1))
            .then(new InteractWithObjectStep(ss, ct, DOOR_138, 3, "Open", afterTicks(2)))
            .then(new WalkStep(new WorldPoint(1097, 9767, 0), 1))   // intentional — see note above
            .then(new InteractWithObjectStep(ss, ct, "Lever E", 8, "Pull", afterTicks(2)))

            // Back east, then south through doors (ids 138, 142, 145, 141).
            .then(new WalkStep(new WorldPoint(3099, 9765, 0), 1))
            .then(new InteractWithObjectStep(ss, ct, DOOR_138, 3, "Open", afterTicks(2)))
            .then(new WalkStep(new WorldPoint(3102, 9764, 0), 1))
            .then(new InteractWithObjectStep(ss, ct, DOOR_142, 3, "Open", afterTicks(2)))
            .then(new WalkStep(new WorldPoint(3102, 9759, 0), 1))
            .then(new InteractWithObjectStep(ss, ct, DOOR_145, 3, "Open", afterTicks(2)))
            .then(new WalkStep(new WorldPoint(3101, 9755, 0), 1))
            .then(new InteractWithObjectStep(ss, ct, DOOR_141, 3, "Open", afterTicks(2)))

            // Oil can pickup.
            .then(new WalkStep(new WorldPoint(3093, 9755, 0), 1))
            .then(new TakeGroundItemStep(ss, ct, oilCanId, 5, "Oil can"))

            // Back east through door (id 141), climb up.
            .then(new WalkStep(new WorldPoint(3099, 9755, 0), 1))
            .then(new InteractWithObjectStep(ss, ct, DOOR_141, 3, "Open", afterTicks(2)))
            .then(new WalkStep(new WorldPoint(3117, 9753, 0), 1))
            .then(new InteractWithObjectStep(ss, ct, "Ladder", 3, "Climb-up", playerYBelow(9000)))

            // Final lever to exit secret wall.
            .then(new WalkStep(EXIT_LEVER_TILE, 1))
            .then(new InteractWithObjectStep(ss, ct, "Lever", 5, "Pull", afterTicks(3)));

        // Selector short-circuit: if we already have the can, skip the puzzle.
        return new Selector("LeverPuzzleOrSkip")
            .option(new HaveAtLeastInInventoryStep(oilCanId, 1, "Oil can"))
            .option(inner);
    }

    // ─── Predicate helpers ──────────────────────────────────────────────────

    private static Predicate<WorldSnapshot> invHas(int itemId) {
        return snap -> snap.inventory().contains(itemId);
    }

    private static Predicate<WorldSnapshot> hasAny(int... itemIds) {
        return snap -> {
            for (int id : itemIds) {
                if (snap.inventory().contains(id)) return true;
            }
            return false;
        };
    }

    /** "Player is past {@code milestone}" — true when the player is on
     *  any plane higher than the milestone, or on the same plane but
     *  further north (Ernest's flow is always north-bound: bank →
     *  Veronica → stairs → upper floors). Combined with the milestone's
     *  arrival predicate via {@code .or(...)} so resume-from-mid-quest
     *  doesn't get stuck trying to walk backward along a trail. */
    private static Predicate<WorldSnapshot> pastTile(WorldPoint milestone) {
        return snap -> {
            if (snap.player() == null) return false;
            WorldPoint here = snap.player().worldLocation();
            if (here == null) return false;
            if (here.getPlane() > milestone.getPlane()) return true;
            return here.getPlane() == milestone.getPlane()
                && here.getY() > milestone.getY();
        };
    }

    /** Loose "we're already in Draynor / the manor" check used as the
     *  ALREADY_SATISFIED predicate for the Lumby→Draynor trail step.
     *  Bounding box covers the bank, the village, the manor courtyard
     *  and inside the manor (any plane). If the user starts the script
     *  anywhere in this box, step 1 is skipped — there's no point trying
     *  to replay a trail that starts 100+ tiles away in Lumbridge. */
    private static Predicate<WorldSnapshot> inDraynorOrManorArea() {
        return snap -> {
            if (snap.player() == null) return false;
            WorldPoint here = snap.player().worldLocation();
            if (here == null) return false;
            return here.getX() >= 3070 && here.getX() <= 3130
                && here.getY() >= DRAYNOR_BANK_ARRIVED.getY() && here.getY() <= 3380;
        };
    }

    private static Predicate<WorldSnapshot> playerNear(WorldPoint target, int radius) {
        return snap -> {
            if (snap.player() == null) return false;
            WorldPoint here = snap.player().worldLocation();
            return here != null && here.getPlane() == target.getPlane()
                && here.distanceTo(target) <= radius;
        };
    }

    private static Predicate<WorldSnapshot> playerOnPlane(int plane) {
        return snap -> snap.player() != null
            && snap.player().worldLocation() != null
            && snap.player().worldLocation().getPlane() == plane;
    }

    private static Predicate<WorldSnapshot> playerYAbove(int y) {
        return snap -> snap.player() != null
            && snap.player().worldLocation() != null
            && snap.player().worldLocation().getY() > y;
    }

    private static Predicate<WorldSnapshot> playerYBelow(int y) {
        return snap -> snap.player() != null
            && snap.player().worldLocation() != null
            && snap.player().worldLocation().getY() < y;
    }

    /** True when the closed-state door object {@code id} is absent from the
     *  scene within {@code radius} tiles — i.e., the door is already open
     *  (OSRS replaces the closed-door object id with a different open-door id
     *  when interacted with). Using this as {@code doneWhen} in
     *  {@link InteractWithObjectStep} means the step is ALREADY_SATISFIED if
     *  the door is open, and waits for the swap otherwise. Must be called on
     *  the client thread (safe inside engine tick / onStart / check). */
    private static Predicate<WorldSnapshot> doorNotPresent(SceneScanner scanner, int id, int radius) {
        return snap -> {
            SceneScanner.Match m = scanner.findGameObjectById(id, radius);
            return m == null || m.gameObject == null;
        };
    }

    /** Stateful predicate: returns false on first call (records baseline
     *  tick), true once {@code n} engine ticks have elapsed. Each call
     *  to {@link #afterTicks} returns a FRESH predicate — never reuse a
     *  single instance across multiple steps. */
    private static Predicate<WorldSnapshot> afterTicks(int n) {
        final int[] startTick = { -1 };
        return snap -> {
            if (startTick[0] < 0) {
                startTick[0] = snap.tick();
                return false;
            }
            return snap.tick() - startTick[0] >= n;
        };
    }

}
