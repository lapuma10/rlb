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
import net.runelite.client.sequence.views.GrandExchangeOfferView;
import net.runelite.client.sequence.views.OfferSide;

/**
 * Collects the proceeds of the in-flight offer (slot from
 * {@code K_GE_OFFER_SLOT} SEQUENCE-scope) into the player's inventory.
 *
 * <p>Reads the slot's {@link OfferSide} from the snapshot to compute which
 * inventory delta to expect (BUY → itemId increases; SELL → coins
 * increase). Persists the pre-collect inventory count to STEP scope and
 * waits for the slot to become EMPTY AND the inventory count to rise above
 * that baseline.
 *
 * <p>Already-satisfied: slot already EMPTY (collect happened externally).
 *
 * <p>Failure: {@link GeBlockReason.GeCollectFailed(slot, expectedDeltaItemId,
 * observedDelta)} when the slot transitions to EMPTY but the expected
 * inventory delta is not observed within the timeout.
 */
public final class CollectOfferStep implements Step {

    private static final int COINS_ITEM_ID = 995;

    private static final BlackboardKey<GeBlockReason> K_PRECONDITION =
        BlackboardKey.of("collectOffer.precondition", GeBlockReason.class);
    private static final BlackboardKey<Integer> K_EXPECTED_DELTA_ITEM_ID =
        BlackboardKey.of("collectOffer.expectedDeltaItemId", Integer.class);
    private static final BlackboardKey<Integer> K_EXPECTED_DELTA_START =
        BlackboardKey.of("collectOffer.expectedDeltaStart", Integer.class);
    private static final BlackboardKey<Integer> K_START_TICK =
        BlackboardKey.of("collectOffer.startTick", Integer.class);
    private static final BlackboardKey<Boolean> K_ALREADY_EMPTY =
        BlackboardKey.of("collectOffer.alreadyEmpty", Boolean.class);

    private final GeActions ge;

    public CollectOfferStep(GeActions ge) {
        if (ge == null) throw new IllegalArgumentException("GeActions must not be null");
        this.ge = ge;
    }

    @Override public String name()                              { return "CollectOffer"; }
    @Override public int priority()                             { return 100; }
    @Override public int timeoutTicks()                         { return 8; }
    @Override public PreemptionPolicy preemptionPolicy()        { return PreemptionPolicy.WHEN_SAFE; }
    @Override public boolean isSafeToPause(WorldSnapshot s, Blackboard b) { return true; }
    @Override public boolean canStart(WorldSnapshot s, Blackboard b) { return true; }

    @Override
    public void onStart(StepContext ctx) {
        WorldSnapshot s = ctx.snapshot();
        Blackboard step = ctx.bb().scope(BlackboardScope.STEP);

        if (!s.grandExchange().open()) {
            step.put(K_PRECONDITION, new GeBlockReason.GeNotOpen());
            return;
        }

        Integer slotBox = ctx.bb().scope(BlackboardScope.SEQUENCE)
            .get(GeBlackboardKeys.K_GE_OFFER_SLOT).orElse(null);
        if (slotBox == null) {
            step.put(K_PRECONDITION, new GeBlockReason.GeOfferRejected("no offer slot recorded"));
            return;
        }
        int slot = slotBox;
        var offers = s.grandExchange().offers();
        if (slot < 0 || slot >= offers.size()) {
            step.put(K_PRECONDITION, new GeBlockReason.GeOfferRejected("slot out of range: " + slot));
            return;
        }
        GrandExchangeOfferView o = offers.get(slot);

        // Compute expected delta target.
        int expectedItemId = (o.side() == OfferSide.SELL) ? COINS_ITEM_ID : o.itemId();
        if (expectedItemId == 0 && o.isEmpty()) {
            // Slot is already EMPTY at onStart — already-satisfied path. Use
            // a sentinel so check returns Succeeded immediately.
            step.put(K_ALREADY_EMPTY, true);
            return;
        }
        step.put(K_EXPECTED_DELTA_ITEM_ID, expectedItemId);
        step.put(K_EXPECTED_DELTA_START, s.inventory().count(expectedItemId));
        step.put(K_START_TICK, s.tick());

        // Already-satisfied: slot is empty AND we already know what to look
        // for. (e.g., collect happened externally between Wait and Collect.)
        if (o.isEmpty()) {
            step.put(K_ALREADY_EMPTY, true);
            return;
        }

        ge.collect(slot);
    }

    @Override public void onEvent(Object event, StepContext ctx) {}
    @Override public void tick(StepContext ctx) {}

    @Override
    public Completion check(WorldSnapshot s, Blackboard bb) {
        Blackboard step = bb.scope(BlackboardScope.STEP);
        GeBlockReason pre = step.get(K_PRECONDITION).orElse(null);
        if (pre != null) return Completion.failed(pre);

        if (Boolean.TRUE.equals(step.get(K_ALREADY_EMPTY).orElse(null))) {
            return new Completion.Succeeded("slot already empty");
        }

        Integer slotBox = bb.scope(BlackboardScope.SEQUENCE)
            .get(GeBlackboardKeys.K_GE_OFFER_SLOT).orElse(null);
        if (slotBox == null) {
            return Completion.failed(new GeBlockReason.GeOfferRejected("slot key missing during check"));
        }
        int slot = slotBox;
        GrandExchangeOfferView o = s.grandExchange().offers().get(slot);

        int expectedItemId = step.get(K_EXPECTED_DELTA_ITEM_ID).orElse(-1);
        int startCount = step.get(K_EXPECTED_DELTA_START).orElse(0);
        int now = (expectedItemId > 0) ? s.inventory().count(expectedItemId) : 0;
        int delta = now - startCount;

        boolean slotEmpty = o.isEmpty();
        boolean deltaObserved = expectedItemId > 0 && delta > 0;

        if (slotEmpty && deltaObserved) {
            return new Completion.Succeeded("collected (delta=" + delta + ")");
        }

        int startTick = step.get(K_START_TICK).orElse(s.tick());
        if (s.tick() - startTick >= timeoutTicks()) {
            return Completion.failed(new GeBlockReason.GeCollectFailed(slot, expectedItemId, delta));
        }
        return Completion.RUNNING;
    }

    @Override
    public Recovery onFailure(Failure f, WorldSnapshot s, Blackboard bb) {
        if (f.diagnostic() instanceof GeBlockReason.GeNotOpen)        return new Recovery.Abort("ge not open");
        if (f.diagnostic() instanceof GeBlockReason.GeOfferRejected)  return new Recovery.Abort("offer slot missing");
        if (f.diagnostic() instanceof GeBlockReason.GeCollectFailed)  return new Recovery.Retry(2);
        return new Recovery.Retry(2);
    }
}
