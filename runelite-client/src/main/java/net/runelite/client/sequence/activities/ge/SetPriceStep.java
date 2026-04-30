package net.runelite.client.sequence.activities.ge;

import net.runelite.client.sequence.Completion;
import net.runelite.client.sequence.Failure;
import net.runelite.client.sequence.PreemptionPolicy;
import net.runelite.client.sequence.Recovery;
import net.runelite.client.sequence.Step;
import net.runelite.client.sequence.StepContext;
import net.runelite.client.sequence.WorldSnapshot;
import net.runelite.client.sequence.affordance.GeBlockReason;
import net.runelite.client.sequence.blackboard.Blackboard;
import net.runelite.client.sequence.blackboard.BlackboardKey;
import net.runelite.client.sequence.blackboard.BlackboardScope;
import net.runelite.client.sequence.dispatch.InputDispatcher;

/**
 * Sets the price-each in the offer-setup widget. Mirror of
 * {@link SetQuantityStep}.
 */
public final class SetPriceStep implements Step {

    private static final BlackboardKey<GeBlockReason> K_PRECONDITION =
        BlackboardKey.of("setPrice.precondition", GeBlockReason.class);
    /** Set to true after {@code GeActions.setPrice} returns true. */
    private static final BlackboardKey<Boolean> K_PROMPT_SEEN =
        BlackboardKey.of("setPrice.promptSeen", Boolean.class);
    /** Same as SetQuantityStep — defer dispatch until dispatcher is idle. */
    private static final BlackboardKey<Boolean> K_NEEDS_DISPATCH =
        BlackboardKey.of("setPrice.needsDispatch", Boolean.class);
    /** Tick the worker first went idle (see SetQuantityStep). */
    private static final BlackboardKey<Integer> K_FIRST_IDLE_TICK =
        BlackboardKey.of("setPrice.firstIdleTick", Integer.class);
    private static final int POST_IDLE_FALLBACK_TICKS = 5;

    private final int priceEach;
    private final GeActions ge;
    private InputDispatcher dispatcher;

    public SetPriceStep(int priceEach, GeActions ge) {
        if (priceEach <= 0) throw new IllegalArgumentException("priceEach must be > 0");
        if (ge == null)     throw new IllegalArgumentException("GeActions must not be null");
        this.priceEach = priceEach;
        this.ge = ge;
    }

    @Override public String name()                              { return "SetPrice(" + priceEach + ")"; }
    @Override public int priority()                             { return 100; }
    @Override public int timeoutTicks()                         { return 30; }
    @Override public PreemptionPolicy preemptionPolicy()        { return PreemptionPolicy.WHEN_SAFE; }
    @Override public boolean isSafeToPause(WorldSnapshot s, Blackboard b) { return true; }
    @Override public boolean canStart(WorldSnapshot s, Blackboard b) { return true; }

    @Override
    public void onStart(StepContext ctx) {
        WorldSnapshot s = ctx.snapshot();
        Blackboard step = ctx.bb().scope(BlackboardScope.STEP);
        this.dispatcher = ctx.dispatcher();
        if (!s.grandExchange().offerSetupOpen()) {
            step.put(K_PRECONDITION, new GeBlockReason.GeOfferSetupNotOpen());
            return;
        }
        // Defer dispatch — same rationale as SetQuantityStep.onStart.
        step.put(K_NEEDS_DISPATCH, true);
    }

    @Override public void onEvent(Object event, StepContext ctx) {}

    @Override
    public void tick(StepContext ctx) {
        Blackboard step = ctx.bb().scope(BlackboardScope.STEP);
        if (!Boolean.TRUE.equals(step.get(K_NEEDS_DISPATCH).orElse(false))) return;
        if (dispatcher == null || dispatcher.isBusy()) return;
        step.put(K_NEEDS_DISPATCH, false);
        boolean queued = ge.setPrice(priceEach);
        if (!queued) {
            step.put(K_PRECONDITION,
                new GeBlockReason.GeChatboxPromptTimeout("setPrice"));
        }
    }

    @Override
    public Completion check(WorldSnapshot s, Blackboard bb) {
        Blackboard step = bb.scope(BlackboardScope.STEP);
        GeBlockReason pre = step.get(K_PRECONDITION).orElse(null);
        if (pre != null) return Completion.failed(pre);
        if (!s.grandExchange().offerSetupOpen()) {
            return Completion.failed(new GeBlockReason.GeOfferSetupNotOpen());
        }
        if (Boolean.TRUE.equals(step.get(K_NEEDS_DISPATCH).orElse(false))) {
            return Completion.RUNNING;
        }
        if (dispatcher != null && dispatcher.isBusy()) {
            return Completion.RUNNING;
        }
        if (step.get(K_FIRST_IDLE_TICK).isEmpty()) {
            step.put(K_FIRST_IDLE_TICK, s.tick());
        }
        boolean promptOpen = s.grandExchange().chatboxPromptOpen();
        if (promptOpen) step.put(K_PROMPT_SEEN, true);
        boolean promptSeen = Boolean.TRUE.equals(step.get(K_PROMPT_SEEN).orElse(null));
        if (!promptOpen && promptSeen) {
            return new Completion.Succeeded("price submitted");
        }
        int firstIdle = step.get(K_FIRST_IDLE_TICK).orElse(s.tick());
        if (s.tick() - firstIdle >= POST_IDLE_FALLBACK_TICKS) {
            return new Completion.Succeeded("price submitted (post-idle fallback)");
        }
        return Completion.RUNNING;
    }

    @Override
    public Recovery onFailure(Failure f, WorldSnapshot s, Blackboard bb) {
        if (f.diagnostic() instanceof GeBlockReason.GeOfferSetupNotOpen) {
            return new Recovery.Abort("offer-setup not open");
        }
        if (f.diagnostic() instanceof GeBlockReason.GeChatboxPromptTimeout) {
            return new Recovery.Abort("chatbox prompt did not open");
        }
        return new Recovery.Retry(2);
    }
}
