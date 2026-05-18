package net.runelite.client.plugins.recorder.quest.steps;

import java.util.Optional;
import java.util.function.Predicate;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.plugins.recorder.quest.QuestStepThreads;
import net.runelite.client.plugins.recorder.scene.SceneScanner;
import net.runelite.client.sequence.*;
import net.runelite.client.sequence.blackboard.Blackboard;
import net.runelite.client.sequence.blackboard.BlackboardKey;
import net.runelite.client.sequence.blackboard.BlackboardScope;
import net.runelite.client.sequence.dispatch.HumanizedInputDispatcher;
import net.runelite.client.sequence.internal.ActionRequest;
import net.runelite.client.sequence.views.InventoryView;
import net.runelite.client.sequence.views.ItemStack;

/**
 * Use an inventory item on a world game object. Two-click flow under one
 * RUN_TASK: Use-click the inventory slot, then default-click the resolved
 * game object — the engine interprets the pair as "Use {item} on {object}".
 *
 * <p>For Ernest: poisoned fish food on fountain, spade on compost heap
 * (search verb also works there).
 *
 * <p>Completion is caller-supplied — typically a varbit advance, a follow-up
 * dialog, or an inventory change (item consumed / reward received).
 */
@Slf4j
public final class UseItemOnObjectStep implements Step {

    private final SceneScanner scanner;
    private final ClientThread clientThread;
    private final HumanizedInputDispatcher dispatcher;
    private final int itemId;
    private final String objectName;
    private final int searchRadius;
    private final String displayName;
    private final Predicate<WorldSnapshot> doneWhen;

    public UseItemOnObjectStep(SceneScanner scanner,
                               ClientThread clientThread,
                               HumanizedInputDispatcher dispatcher,
                               int itemId,
                               String objectName,
                               int searchRadius,
                               String displayName,
                               Predicate<WorldSnapshot> doneWhen) {
        if (itemId <= 0) throw new IllegalArgumentException("itemId must be > 0");
        this.scanner = scanner;
        this.clientThread = clientThread;
        this.dispatcher = dispatcher;
        this.itemId = itemId;
        this.objectName = objectName;
        this.searchRadius = searchRadius;
        this.displayName = displayName != null ? displayName : ("use#" + itemId + "→" + objectName);
        this.doneWhen = doneWhen;
    }

    @Override public String name()                                          { return "Use(" + displayName + ")"; }
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
            log.info("quest-use-on-obj: {} already satisfied — skipping dispatch", displayName);
            return;
        }

        InventoryView inv = ctx.snapshot().inventory();
        Optional<ItemStack> itemSlot = inv.findAnyForm(itemId);
        if (itemSlot.isEmpty()) {
            log.warn("quest-use-on-obj: item#{} not in inventory — will retry", itemId);
            return;
        }
        final int slot = itemSlot.get().slot();

        SceneScanner.Match match = QuestStepThreads.onClient(clientThread,
            () -> scanner.findGameObjectByName(objectName, searchRadius));
        if (match == null || match.gameObject == null || match.tile == null) {
            log.warn("quest-use-on-obj: object '{}' not on scene within {} tiles — will retry",
                objectName, searchRadius);
            return;
        }
        final var targetTile = match.tile;

        ActionRequest req = ActionRequest.builder()
            .kind(ActionRequest.Kind.RUN_TASK)
            .channel(ActionRequest.Channel.MOUSE)
            .task(() -> {
                dispatcher.invSlotClickOnWorker(slot, "Use");
                dispatcher.gameObjectClickOnWorker(targetTile, null);
            })
            .taskName("QUEST_USE_" + displayName.toUpperCase().replace(' ', '_'))
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
            return new Completion.Succeeded(displayName + " — doneWhen satisfied");
        }
        return Completion.RUNNING;
    }

    @Override
    public Recovery onFailure(Failure f, WorldSnapshot s, Blackboard bb) {
        return new Recovery.Retry(3);
    }

    private enum Outcome { FRESH, ALREADY_SATISFIED }

    private static final BlackboardKey<Outcome> K_OUTCOME =
        BlackboardKey.of("useItemOnObject.outcome", Outcome.class);
}
