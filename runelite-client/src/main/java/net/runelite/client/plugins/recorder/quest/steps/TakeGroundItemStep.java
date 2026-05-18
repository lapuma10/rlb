package net.runelite.client.plugins.recorder.quest.steps;

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
 * Pick up a tile item by id within {@code searchRadius} of the player.
 * The completion gate is implicit: inventory contains the item id. No
 * caller predicate needed — for ground-item pickup the "did we get it"
 * question is always inv-contains.
 *
 * <p>For Ernest: fish food, poison, spade, oil can, rubber tube, key.
 */
@Slf4j
public final class TakeGroundItemStep implements Step {

    private final SceneScanner scanner;
    private final ClientThread clientThread;
    private final int itemId;
    private final int searchRadius;
    private final String displayName;

    public TakeGroundItemStep(SceneScanner scanner,
                              ClientThread clientThread,
                              int itemId,
                              int searchRadius,
                              String displayName) {
        if (itemId <= 0) throw new IllegalArgumentException("itemId must be > 0");
        this.scanner = scanner;
        this.clientThread = clientThread;
        this.itemId = itemId;
        this.searchRadius = searchRadius;
        this.displayName = displayName != null ? displayName : ("item#" + itemId);
    }

    @Override public String name()                                          { return "Take(" + displayName + ")"; }
    @Override public int priority()                                         { return 50; }
    @Override public int timeoutTicks()                                     { return 15; }
    @Override public PreemptionPolicy preemptionPolicy()                    { return PreemptionPolicy.WHEN_SAFE; }
    @Override public boolean isSafeToPause(WorldSnapshot s, Blackboard b)   { return true; }
    @Override public boolean canStart(WorldSnapshot s, Blackboard b)        { return s.player() != null; }

    @Override
    public void onStart(StepContext ctx) {
        Blackboard step = ctx.bb().scope(BlackboardScope.STEP);

        if (ctx.snapshot().inventory().contains(itemId)) {
            step.put(K_OUTCOME, Outcome.ALREADY_SATISFIED);
            log.info("quest-take: {} already in inventory — skipping dispatch", displayName);
            return;
        }

        SceneScanner.Match match = QuestStepThreads.onClient(clientThread,
            () -> scanner.findTileItemById(itemId, searchRadius));
        if (match == null || match.tileItem == null || match.tile == null) {
            log.warn("quest-take: {} not found on ground within {} tiles — will retry",
                displayName, searchRadius);
            return;
        }

        ActionRequest req = ActionRequest.builder()
            .kind(ActionRequest.Kind.CLICK_GROUND_ITEM)
            .channel(ActionRequest.Channel.MOUSE)
            .tile(match.tile)
            .itemId(itemId)
            .verb("Take")
            .build();
        ctx.dispatcher().dispatch(req);
    }

    @Override public void onEvent(Object e, StepContext ctx) {}
    @Override public void tick(StepContext ctx) {}

    @Override
    public Completion check(WorldSnapshot s, Blackboard bb) {
        Blackboard step = bb.scope(BlackboardScope.STEP);
        if (step.get(K_OUTCOME).orElse(Outcome.FRESH) == Outcome.ALREADY_SATISFIED) {
            return new Completion.Succeeded(displayName + " already in inventory");
        }
        if (s.inventory().contains(itemId)) {
            return new Completion.Succeeded(displayName + " picked up");
        }
        return Completion.RUNNING;
    }

    @Override
    public Recovery onFailure(Failure f, WorldSnapshot s, Blackboard bb) {
        return new Recovery.Retry(3);
    }

    private enum Outcome { FRESH, ALREADY_SATISFIED }

    private static final BlackboardKey<Outcome> K_OUTCOME =
        BlackboardKey.of("takeGroundItem.outcome", Outcome.class);
}
