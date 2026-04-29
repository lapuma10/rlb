package net.runelite.client.sequence.activities.ge;

import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.sequence.Completion;
import net.runelite.client.sequence.Failure;
import net.runelite.client.sequence.PlayerView;
import net.runelite.client.sequence.PreemptionPolicy;
import net.runelite.client.sequence.Recovery;
import net.runelite.client.sequence.Step;
import net.runelite.client.sequence.StepContext;
import net.runelite.client.sequence.WorldSnapshot;
import net.runelite.client.sequence.affordance.GeBlockReason;
import net.runelite.client.sequence.blackboard.Blackboard;
import net.runelite.client.sequence.blackboard.BlackboardKey;
import net.runelite.client.sequence.blackboard.BlackboardScope;

/**
 * Pure guard: succeeds if the player is inside {@code geArea}; fails fatally
 * with {@link GeBlockReason.NotAtGrandExchange} otherwise.
 *
 * <p>This is NOT a walker. The Phase A proof assumes the user is already at
 * the GE; the script aborts if they're not. Phase B (or later) can wrap
 * this with a walker step.
 */
public final class EnsureAtGrandExchangeStep implements Step {

    private static final BlackboardKey<GeBlockReason> K_PRECONDITION =
        BlackboardKey.of("ensureAtGE.precondition", GeBlockReason.class);

    private final WorldArea geArea;

    public EnsureAtGrandExchangeStep(WorldArea geArea) {
        if (geArea == null) throw new IllegalArgumentException("geArea must not be null");
        this.geArea = geArea;
    }

    @Override public String name()                                 { return "EnsureAtGrandExchange"; }
    @Override public int priority()                                { return 100; }
    @Override public int timeoutTicks()                            { return 4; }
    @Override public PreemptionPolicy preemptionPolicy()           { return PreemptionPolicy.NEVER; }
    @Override public boolean isSafeToPause(WorldSnapshot s, Blackboard b) { return true; }
    @Override public boolean canStart(WorldSnapshot s, Blackboard b) { return true; }

    @Override
    public void onStart(StepContext ctx) {
        WorldSnapshot s = ctx.snapshot();
        Blackboard step = ctx.bb().scope(BlackboardScope.STEP);
        PlayerView pv = s.player();
        if (pv == null || pv.worldLocation() == null) {
            step.put(K_PRECONDITION, new GeBlockReason.NotAtGrandExchange(geArea));
            return;
        }
        WorldPoint loc = pv.worldLocation();
        if (!geArea.contains(loc)) {
            step.put(K_PRECONDITION, new GeBlockReason.NotAtGrandExchange(geArea));
        }
    }

    @Override public void onEvent(Object event, StepContext ctx) {}
    @Override public void tick(StepContext ctx) {}

    @Override
    public Completion check(WorldSnapshot s, Blackboard bb) {
        Blackboard step = bb.scope(BlackboardScope.STEP);
        return step.get(K_PRECONDITION)
            .<Completion>map(Completion::failed)
            .orElseGet(() -> new Completion.Succeeded("at GE"));
    }

    @Override
    public Recovery onFailure(Failure f, WorldSnapshot s, Blackboard bb) {
        return new Recovery.Abort(f.reason());
    }
}
