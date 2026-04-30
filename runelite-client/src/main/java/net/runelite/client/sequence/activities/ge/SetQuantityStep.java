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
 * Sets the quantity in the offer-setup widget. Dispatches via
 * {@code GeActions.setQuantity} on onStart. Phase A success criterion is
 * "offer-setup is still open after one chain"; Phase B / banking can wire
 * the real quantity-widget probe.
 */
public final class SetQuantityStep implements Step {

    private static final BlackboardKey<GeBlockReason> K_PRECONDITION =
        BlackboardKey.of("setQty.precondition", GeBlockReason.class);
    /** Set true the first tick we observe MESLAYERMODE != 0 after dispatch. */
    private static final BlackboardKey<Boolean> K_PROMPT_SEEN =
        BlackboardKey.of("setQty.promptSeen", Boolean.class);
    /** True while we still need to dispatch the click+type RUN_TASK. We
     *  defer dispatch until the dispatcher worker is idle so the prior
     *  step's chain (e.g. PickSearchResult clicking the result row) has
     *  fully landed before we click the Set Quantity widget. Without
     *  this gate the dispatch was silently dropped by the busy guard
     *  and the bot fell through to ConfirmOffer with default qty=1. */
    private static final BlackboardKey<Boolean> K_NEEDS_DISPATCH =
        BlackboardKey.of("setQty.needsDispatch", Boolean.class);
    /** Tick the worker first went idle after our dispatch — used for an
     *  idle-only snapshot-cadence fallback if the brief MESLAYERMODE!=0
     *  pulse was missed by the observer. */
    private static final BlackboardKey<Integer> K_FIRST_IDLE_TICK =
        BlackboardKey.of("setQty.firstIdleTick", Integer.class);
    /** Idle-tick fallback window after the worker has finished. Only
     *  meaningful AFTER {@code dispatcher.isBusy() == false}. 5 ticks
     *  (~3s) is generous for the snapshot observer to catch any post-
     *  dispatch state. */
    private static final int POST_IDLE_FALLBACK_TICKS = 5;

    private final int qty;
    private final GeActions ge;
    /** Engine-supplied dispatcher; captured in onStart from
     *  {@link StepContext#dispatcher()} and used by {@link #tick} /
     *  {@link #check} to gate dispatch + success on worker idleness. */
    private InputDispatcher dispatcher;

    public SetQuantityStep(int qty, GeActions ge) {
        if (qty <= 0)   throw new IllegalArgumentException("qty must be > 0");
        if (ge == null) throw new IllegalArgumentException("GeActions must not be null");
        this.qty = qty;
        this.ge = ge;
    }

    @Override public String name()                              { return "SetQuantity(" + qty + ")"; }
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
        // Defer dispatch — tick() will fire it the first tick the
        // dispatcher worker is idle. If we dispatched here while the
        // prior step's chain (PickSearchResult clicking the result row)
        // is still in flight, the busy guard would silently drop us.
        step.put(K_NEEDS_DISPATCH, true);
    }

    @Override public void onEvent(Object event, StepContext ctx) {}

    @Override
    public void tick(StepContext ctx) {
        Blackboard step = ctx.bb().scope(BlackboardScope.STEP);
        if (!Boolean.TRUE.equals(step.get(K_NEEDS_DISPATCH).orElse(false))) return;
        if (dispatcher == null || dispatcher.isBusy()) return;
        step.put(K_NEEDS_DISPATCH, false);
        boolean queued = ge.setQuantity(qty);
        if (!queued) {
            step.put(K_PRECONDITION,
                new GeBlockReason.GeChatboxPromptTimeout("setQuantity"));
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
        // Still deferred (waiting for prior chain) → keep running.
        if (Boolean.TRUE.equals(step.get(K_NEEDS_DISPATCH).orElse(false))) {
            return Completion.RUNNING;
        }
        // Worker still typing/clicking → keep running. Engine's lastBusyTick
        // logic also keeps timeoutTicks at bay while we wait.
        if (dispatcher != null && dispatcher.isBusy()) {
            return Completion.RUNNING;
        }
        // Worker has gone idle. Stamp the first idle tick so the fallback
        // window below counts only POST-dispatch idle ticks.
        if (step.get(K_FIRST_IDLE_TICK).isEmpty()) {
            step.put(K_FIRST_IDLE_TICK, s.tick());
        }
        // Live signal: prompt opened then closed (Enter pressed).
        boolean promptOpen = s.grandExchange().chatboxPromptOpen();
        if (promptOpen) step.put(K_PROMPT_SEEN, true);
        boolean promptSeen = Boolean.TRUE.equals(step.get(K_PROMPT_SEEN).orElse(null));
        if (!promptOpen && promptSeen) {
            return new Completion.Succeeded("quantity submitted");
        }
        // Snapshot-cadence fallback — ONLY after the worker has been idle
        // for a short window. This means the RUN_TASK genuinely finished
        // (no longer lying about a click that's still in flight). The
        // observer occasionally polls between cs2 transitions and misses
        // the brief MESLAYERMODE != 0 pulse.
        int firstIdle = step.get(K_FIRST_IDLE_TICK).orElse(s.tick());
        if (s.tick() - firstIdle >= POST_IDLE_FALLBACK_TICKS) {
            return new Completion.Succeeded("quantity submitted (post-idle fallback)");
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
