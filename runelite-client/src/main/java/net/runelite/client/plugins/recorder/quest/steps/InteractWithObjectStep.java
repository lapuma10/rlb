package net.runelite.client.plugins.recorder.quest.steps;

import java.util.function.Predicate;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.plugins.recorder.quest.QuestStepThreads;
import net.runelite.client.plugins.recorder.scene.SceneScanner;
import net.runelite.client.sequence.*;
import net.runelite.client.sequence.blackboard.Blackboard;
import net.runelite.client.sequence.blackboard.BlackboardKey;
import net.runelite.client.sequence.blackboard.BlackboardScope;
import net.runelite.client.sequence.internal.ActionRequest;

/**
 * Find a game object on the loaded scene and dispatch a verb click on it.
 * Workhorse for door Open, lever Pull, bookcase Search, ladder Climb-up /
 * Climb-down, fountain Use, compost-heap Search — anything the OSRS engine
 * exposes as a right-click verb on a {@code GameObject}.
 *
 * <p>Two constructors: lookup by name (when the display name is unique
 * within the search radius), or by object id (when multiple objects share
 * the same name — e.g. the Ernest lever-puzzle basement, where eight
 * distinct "Door" objects sit within ~10 tiles of each other and the
 * specific id is the only safe disambiguator).
 *
 * <p>Completion is gated by a caller-supplied predicate ({@code doneWhen})
 * — typically "player moved to expected tile", "varbit advanced", or
 * "inventory contains the searched item". Steps stay dumb; the quest
 * recipe owns the per-instance success semantics.
 */
@Slf4j
public final class InteractWithObjectStep implements Step {

    private final SceneScanner scanner;
    private final ClientThread clientThread;
    /** Set when looked up by name; null when looked up by id. */
    private final String objectName;
    /** Set when looked up by id; 0 when looked up by name. */
    private final int objectId;
    private final int searchRadius;
    private final String verb;
    private final Predicate<WorldSnapshot> doneWhen;

    /** Lookup by display name (closest match within radius). Use only when
     *  the name uniquely identifies one scene object — for door/lever
     *  clusters use the id constructor. */
    public InteractWithObjectStep(SceneScanner scanner,
                                  ClientThread clientThread,
                                  String objectName,
                                  int searchRadius,
                                  String verb,
                                  Predicate<WorldSnapshot> doneWhen) {
        this.scanner = scanner;
        this.clientThread = clientThread;
        this.objectName = objectName;
        this.objectId = 0;
        this.searchRadius = searchRadius;
        this.verb = verb;
        this.doneWhen = doneWhen;
    }

    /** Lookup by object id. Use when several scene objects share a display
     *  name (Ernest lever-puzzle doors) and only the id picks the right one.
     *  The id is the closed-state id; the dispatcher's verb match handles
     *  the "Open" action. */
    public InteractWithObjectStep(SceneScanner scanner,
                                  ClientThread clientThread,
                                  int objectId,
                                  int searchRadius,
                                  String verb,
                                  Predicate<WorldSnapshot> doneWhen) {
        if (objectId <= 0) throw new IllegalArgumentException("objectId must be > 0");
        this.scanner = scanner;
        this.clientThread = clientThread;
        this.objectName = null;
        this.objectId = objectId;
        this.searchRadius = searchRadius;
        this.verb = verb;
        this.doneWhen = doneWhen;
    }

    private String targetLabel() { return objectName != null ? objectName : ("id#" + objectId); }

    @Override public String name()                                          { return verb + "(" + targetLabel() + ")"; }
    @Override public int priority()                                         { return 50; }
    @Override public int timeoutTicks()                                     { return 20; }
    @Override public PreemptionPolicy preemptionPolicy()                    { return PreemptionPolicy.WHEN_SAFE; }
    @Override public boolean isSafeToPause(WorldSnapshot s, Blackboard b)   { return true; }
    @Override public boolean canStart(WorldSnapshot s, Blackboard b)        { return s.player() != null; }

    @Override
    public void onStart(StepContext ctx) {
        Blackboard step = ctx.bb().scope(BlackboardScope.STEP);

        if (doneWhen.test(ctx.snapshot())) {
            step.put(K_OUTCOME, Outcome.ALREADY_SATISFIED);
            log.info("quest-interact: {}({}) already satisfied — skipping dispatch", verb, targetLabel());
            return;
        }

        SceneScanner.Match match = QuestStepThreads.onClient(clientThread,
            () -> objectName != null
                ? scanner.findGameObjectByName(objectName, searchRadius)
                : scanner.findGameObjectById(objectId, searchRadius));
        if (match == null || match.gameObject == null || match.tile == null) {
            log.warn("quest-interact: {} not found within {} tiles — step will time out & retry",
                targetLabel(), searchRadius);
            return;
        }

        ActionRequest req = ActionRequest.builder()
            .kind(ActionRequest.Kind.CLICK_GAME_OBJECT)
            .channel(ActionRequest.Channel.MOUSE)
            .tile(match.tile)
            .objectId(match.gameObject.getId())
            .verb(verb)
            .build();
        ctx.dispatcher().dispatch(req);
    }

    @Override public void onEvent(Object e, StepContext ctx) {}
    @Override public void tick(StepContext ctx) {}

    @Override
    public Completion check(WorldSnapshot s, Blackboard bb) {
        Blackboard step = bb.scope(BlackboardScope.STEP);
        if (step.get(K_OUTCOME).orElse(Outcome.FRESH) == Outcome.ALREADY_SATISFIED) {
            return new Completion.Succeeded("doneWhen already true at onStart");
        }
        if (doneWhen.test(s)) {
            return new Completion.Succeeded(verb + "(" + targetLabel() + ") doneWhen satisfied");
        }
        return Completion.RUNNING;
    }

    @Override
    public Recovery onFailure(Failure f, WorldSnapshot s, Blackboard bb) {
        return new Recovery.Retry(3);
    }

    private enum Outcome { FRESH, ALREADY_SATISFIED }

    private static final BlackboardKey<Outcome> K_OUTCOME =
        BlackboardKey.of("interactObject.outcome", Outcome.class);
}
