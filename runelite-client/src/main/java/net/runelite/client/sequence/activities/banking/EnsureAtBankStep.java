package net.runelite.client.sequence.activities.banking;

import net.runelite.api.coords.WorldArea;
import net.runelite.client.sequence.*;
import net.runelite.client.sequence.affordance.BlockReason;
import net.runelite.client.sequence.affordance.DiagnosticReason;
import net.runelite.client.sequence.blackboard.Blackboard;
import net.runelite.client.sequence.blackboard.BlackboardKey;
import net.runelite.client.sequence.blackboard.BlackboardScope;

import java.util.Optional;

/**
 * Guard step: verifies the player is within the given bank area.
 * No dispatch — purely positional check. Fatal if out of area.
 */
public final class EnsureAtBankStep implements Step {

    private final WorldArea bankArea;

    public EnsureAtBankStep(WorldArea bankArea) {
        this.bankArea = bankArea;
    }

    @Override public String name()                 { return "EnsureAtBank(" + bankArea + ")"; }
    @Override public int priority()                { return 100; }
    @Override public int timeoutTicks()            { return 2; }
    @Override public PreemptionPolicy preemptionPolicy() { return PreemptionPolicy.NEVER; }
    @Override public boolean isSafeToPause(WorldSnapshot s, Blackboard bb) { return true; }
    @Override public boolean canStart(WorldSnapshot s, Blackboard bb)       { return true; }

    @Override
    public void onStart(StepContext ctx) {
        WorldSnapshot s = ctx.snapshot();
        Blackboard step = ctx.bb().scope(BlackboardScope.STEP);
        PlayerView player = s.player();
        if (player == null || !bankArea.contains(player.worldLocation())) {
            step.put(K_PRECONDITION_FAILURE, new BlockReason.NotAtLocation(bankArea));
        }
    }

    @Override public void onEvent(Object e, StepContext ctx) {}
    @Override public void tick(StepContext ctx) {}

    @Override
    public Completion check(WorldSnapshot s, Blackboard bb) {
        Blackboard step = bb.scope(BlackboardScope.STEP);
        Optional<DiagnosticReason> pre = step.get(K_PRECONDITION_FAILURE);
        if (pre.isPresent()) return Completion.failed(pre.get());
        return new Completion.Succeeded("at bank");
    }

    @Override
    public Recovery onFailure(Failure f, WorldSnapshot s, Blackboard bb) {
        return new Recovery.Abort(f.reason());
    }

    private static final BlackboardKey<DiagnosticReason> K_PRECONDITION_FAILURE =
        BlackboardKey.of("ensureAtBank.preconditionFailure", DiagnosticReason.class);
}
