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
    /** Tick onStart fired — used for the elapsed-tick fallback when the
     *  prompt-open pulse is missed (snapshot polling cadence vs cs2 tick
     *  timing). */
    private static final BlackboardKey<Integer> K_START_TICK =
        BlackboardKey.of("setQty.startTick", Integer.class);
    /** Ticks to wait for the async dispatcher worker to complete the
     *  typing if we never observed the MESLAYERMODE pulse (the snapshot
     *  observer polls between cs2 transitions and can miss a fast prompt
     *  open/close cycle). 5 ticks ~= 3 seconds of game time. */
    private static final int DISPATCH_FALLBACK_TICKS = 5;

    private final int qty;
    private final GeActions ge;

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
        if (!s.grandExchange().offerSetupOpen()) {
            step.put(K_PRECONDITION, new GeBlockReason.GeOfferSetupNotOpen());
            return;
        }
        step.put(K_START_TICK, s.tick());
        boolean queued = ge.setQuantity(qty);
        if (!queued) {
            step.put(K_PRECONDITION,
                new GeBlockReason.GeChatboxPromptTimeout("setQuantity"));
        }
    }

    @Override public void onEvent(Object event, StepContext ctx) {}
    @Override public void tick(StepContext ctx) {}

    @Override
    public Completion check(WorldSnapshot s, Blackboard bb) {
        Blackboard step = bb.scope(BlackboardScope.STEP);
        GeBlockReason pre = step.get(K_PRECONDITION).orElse(null);
        if (pre != null) return Completion.failed(pre);
        if (!s.grandExchange().offerSetupOpen()) {
            return Completion.failed(new GeBlockReason.GeOfferSetupNotOpen());
        }
        // Async typing path. Two ways to declare success:
        //   1. Live signal: numeric prompt opened (MESLAYERMODE != 0) THEN
        //      closed (back to 0) — the engine clears the mode on Enter.
        //   2. Fallback: enough ticks elapsed for the dispatcher worker to
        //      have finished the type+Enter chain AND the prompt isn't
        //      currently open. Covers fast cs2 cycles where the snapshot
        //      observer's poll misses the brief MODE=7 window, and tests
        //      where RecordingGeActions doesn't actually transition state.
        boolean promptOpen = s.grandExchange().chatboxPromptOpen();
        if (promptOpen) step.put(K_PROMPT_SEEN, true);
        boolean promptSeen = Boolean.TRUE.equals(step.get(K_PROMPT_SEEN).orElse(null));
        int startTick = step.get(K_START_TICK).orElse(s.tick());
        int elapsed = s.tick() - startTick;
        if (!promptOpen && (promptSeen || elapsed >= DISPATCH_FALLBACK_TICKS)) {
            return new Completion.Succeeded("quantity submitted");
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
