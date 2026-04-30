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
 * Sets the price-each in the offer-setup widget. Mirror of
 * {@link SetQuantityStep}.
 */
public final class SetPriceStep implements Step {

    private static final BlackboardKey<GeBlockReason> K_PRECONDITION =
        BlackboardKey.of("setPrice.precondition", GeBlockReason.class);
    /** Set to true after {@code GeActions.setPrice} returns true. */
    private static final BlackboardKey<Boolean> K_TYPED =
        BlackboardKey.of("setPrice.typed", Boolean.class);

    private final int priceEach;
    private final GeActions ge;

    public SetPriceStep(int priceEach, GeActions ge) {
        if (priceEach <= 0) throw new IllegalArgumentException("priceEach must be > 0");
        if (ge == null)     throw new IllegalArgumentException("GeActions must not be null");
        this.priceEach = priceEach;
        this.ge = ge;
    }

    @Override public String name()                              { return "SetPrice(" + priceEach + ")"; }
    @Override public int priority()                             { return 100; }
    @Override public int timeoutTicks()                         { return 6; }
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
        boolean typed = ge.setPrice(priceEach);
        step.put(K_TYPED, typed);
        if (!typed) {
            step.put(K_PRECONDITION,
                new GeBlockReason.GeChatboxPromptTimeout("setPrice"));
        }
    }

    @Override public void onEvent(Object event, StepContext ctx) {}
    @Override public void tick(StepContext ctx) {}

    @Override
    public Completion check(WorldSnapshot s, Blackboard bb) {
        Blackboard step = bb.scope(BlackboardScope.STEP);
        GeBlockReason pre = step.get(K_PRECONDITION).orElse(null);
        if (pre != null) return Completion.failed(pre);
        if (Boolean.TRUE.equals(step.get(K_TYPED).orElse(null))
            && s.grandExchange().offerSetupOpen()) {
            return new Completion.Succeeded("price set");
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
