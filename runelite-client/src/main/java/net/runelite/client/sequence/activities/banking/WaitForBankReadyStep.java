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

        if (s.bank().ready()) return new Completion.Succeeded("bank ready");

        if (!s.bank().open()) return Completion.failed(new BlockReason.BankNotOpen());

        int startTick = step.get(K_START_TICK).orElse(s.tick());
        if (s.tick() - startTick >= timeoutTicks()) {
            return Completion.failed(new BlockReason.BankNotReady());
        }
        return Completion.RUNNING;
    }

    @Override
    public Recovery onFailure(Failure f, WorldSnapshot s, Blackboard bb) {
        return new Recovery.Abort(f.reason());
    }

    private static final BlackboardKey<Integer> K_START_TICK =
        BlackboardKey.of("waitBankReady.startTick", Integer.class);
}
