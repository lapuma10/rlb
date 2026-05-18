package net.runelite.client.plugins.recorder.quest.steps;

import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.sequence.*;
import net.runelite.client.sequence.blackboard.Blackboard;
import net.runelite.client.sequence.blackboard.BlackboardKey;
import net.runelite.client.sequence.blackboard.BlackboardScope;
import net.runelite.client.sequence.dispatch.HumanizedInputDispatcher;
import net.runelite.client.sequence.internal.ActionRequest;
import net.runelite.client.sequence.views.InventoryView;
import net.runelite.client.sequence.views.ItemStack;

/**
 * Combine two inventory items via the engine's "Use X on Y" interaction:
 * Use-click slotA, then default-click slotB. Both clicks run on the
 * dispatcher worker inside one RUN_TASK so the engine treats the pair
 * as one busy-flagged chain.
 *
 * <p>For Ernest: Poison + Fish food → Poisoned fish food.
 *
 * <p>Completion: inventory contains {@code combinedItemId} AND no longer
 * contains both inputs.
 */
@Slf4j
public final class CombineItemsStep implements Step {

    private final HumanizedInputDispatcher dispatcher;
    private final int itemIdA;
    private final int itemIdB;
    private final int combinedItemId;
    private final String displayName;

    public CombineItemsStep(HumanizedInputDispatcher dispatcher,
                            int itemIdA,
                            int itemIdB,
                            int combinedItemId,
                            String displayName) {
        if (itemIdA <= 0 || itemIdB <= 0 || combinedItemId <= 0) {
            throw new IllegalArgumentException("all item ids must be > 0");
        }
        this.dispatcher = dispatcher;
        this.itemIdA = itemIdA;
        this.itemIdB = itemIdB;
        this.combinedItemId = combinedItemId;
        this.displayName = displayName != null ? displayName : ("combine#" + combinedItemId);
    }

    @Override public String name()                                          { return "Combine(" + displayName + ")"; }
    @Override public int priority()                                         { return 50; }
    @Override public int timeoutTicks()                                     { return 10; }
    @Override public PreemptionPolicy preemptionPolicy()                    { return PreemptionPolicy.WHEN_SAFE; }
    @Override public boolean isSafeToPause(WorldSnapshot s, Blackboard b)   { return true; }
    @Override public boolean canStart(WorldSnapshot s, Blackboard b)        { return s.player() != null; }

    @Override
    public void onStart(StepContext ctx) {
        Blackboard step = ctx.bb().scope(BlackboardScope.STEP);

        InventoryView inv = ctx.snapshot().inventory();
        if (inv.contains(combinedItemId)) {
            step.put(K_OUTCOME, Outcome.ALREADY_SATISFIED);
            log.info("quest-combine: {} already in inventory — skipping dispatch", displayName);
            return;
        }

        Optional<ItemStack> a = inv.findAnyForm(itemIdA);
        Optional<ItemStack> b = inv.findAnyForm(itemIdB);
        if (a.isEmpty() || b.isEmpty()) {
            log.warn("quest-combine: missing input item(s) — a={} b={} — will retry",
                a.isPresent(), b.isPresent());
            return;
        }
        final int slotA = a.get().slot();
        final int slotB = b.get().slot();

        ActionRequest req = ActionRequest.builder()
            .kind(ActionRequest.Kind.RUN_TASK)
            .channel(ActionRequest.Channel.MOUSE)
            .task(() -> {
                // Use slotA, then click slotB — the engine resolves the second
                // click as "Use {itemA} on {itemB}". null verb = left-click default.
                dispatcher.invSlotClickOnWorker(slotA, "Use");
                dispatcher.invSlotClickOnWorker(slotB, null);
            })
            .taskName("QUEST_COMBINE_" + displayName.toUpperCase().replace(' ', '_'))
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
        if (s.inventory().contains(combinedItemId)) {
            return new Completion.Succeeded(displayName + " combined");
        }
        return Completion.RUNNING;
    }

    @Override
    public Recovery onFailure(Failure f, WorldSnapshot s, Blackboard bb) {
        return new Recovery.Retry(3);
    }

    private enum Outcome { FRESH, ALREADY_SATISFIED }

    private static final BlackboardKey<Outcome> K_OUTCOME =
        BlackboardKey.of("combineItems.outcome", Outcome.class);
}
