package net.runelite.client.sequence.activities.banking;

import net.runelite.client.sequence.*;
import net.runelite.client.sequence.affordance.BlockReason;
import net.runelite.client.sequence.blackboard.Blackboard;
import net.runelite.client.sequence.blackboard.BlackboardKey;
import net.runelite.client.sequence.blackboard.BlackboardScope;

/**
 * Waits for the bank container to be fully loaded after the bank widget opens.
 *
 * <p>canStart=true always. This step owns its typed timeout (BankNotReady), which
 * fires before the engine's generic timeoutTicks — check() returns Failed rather
 * than Running at the deadline.
 */
public final class WaitForBankReadyStep implements Step {

    public WaitForBankReadyStep() {}

    @Override public String name()                 { return "WaitForBankReady"; }
    @Override public int priority()                { return 100; }
    @Override public int timeoutTicks()            { return 10; }
    @Override public PreemptionPolicy preemptionPolicy() { return PreemptionPolicy.NEVER; }
    @Override public boolean isSafeToPause(WorldSnapshot s, Blackboard bb) { return true; }
    @Override public boolean canStart(WorldSnapshot s, Blackboard bb)       { return true; }

    @Override
    public void onStart(StepContext ctx) {
        ctx.bb().scope(BlackboardScope.STEP).put(K_START_TICK, ctx.snapshot().tick());
    }

    @Override public void onEvent(Object e, StepContext ctx) {}
    @Override public void tick(StepContext ctx) {}

    @Override
    public Completion check(WorldSnapshot s, Blackboard bb) {
        Blackboard step = bb.scope(BlackboardScope.STEP);
        int startTick = step.get(K_START_TICK).orElse(s.tick());
        boolean timedOut = s.tick() - startTick >= timeoutTicks();

        // Require BOTH the bank widget to be visible AND the container
        // to be populated. {@code ready()} is just {@code container != null},
        // and the BANK ItemContainer is STICKY from the previous bank
        // visit — once you've opened the bank in this session the
        // container reference persists even after you close the widget.
        // So a freshly-dispatched booth click that hasn't yet produced
        // a visible widget would still see {@code ready()=true} and pass
        // this gate immediately, only for the next step to read the
        // widget, find it hidden, and fail with BankNotOpen. The widget
        // check must come first.
        if (!s.bank().open()) {
            if (timedOut) return Completion.failed(new BlockReason.BankNotOpen());
            return Completion.RUNNING;
        }
        if (s.bank().ready()) return new Completion.Succeeded("bank open + ready");

        if (timedOut) return Completion.failed(new BlockReason.BankNotReady());
        return Completion.RUNNING;
    }

    @Override
    public Recovery onFailure(Failure f, WorldSnapshot s, Blackboard bb) {
        return new Recovery.Abort(f.reason());
    }

    private static final BlackboardKey<Integer> K_START_TICK =
        BlackboardKey.of("waitBankReady.startTick", Integer.class);
}
