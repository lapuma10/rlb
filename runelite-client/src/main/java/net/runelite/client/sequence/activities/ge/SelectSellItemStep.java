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
import net.runelite.client.sequence.views.ItemStack;

/**
 * Sell-offer item selection: clicks the target item directly from the player's
 * inventory. Required by the OSRS GE sell flow — after
 * {@link StartOfferStep} opens the sell-setup interface, the GE shows the
 * player's inventory and waits for an inventory left-click. There is no search
 * chatbox for sells (unlike buy offers where {@link SelectItemStep} types a name).
 *
 * <p>Accepts either form via {@code inventory.findAnyForm(itemId)} — clicks
 * the noted stack when present (e.g. after {@code withdrawAsNoteX}) or the
 * unnoted slot otherwise. The GE accepts both forms for sell offers.
 */
public final class SelectSellItemStep implements Step {

    private static final BlackboardKey<GeBlockReason> K_PRECONDITION =
        BlackboardKey.of("selectSellItem.precondition", GeBlockReason.class);

    private final int itemId;
    private final GeActions ge;
    private InputDispatcher dispatcher;

    public SelectSellItemStep(int itemId, GeActions ge) {
        if (itemId <= 0) throw new IllegalArgumentException("itemId must be > 0");
        if (ge == null)  throw new IllegalArgumentException("GeActions must not be null");
        this.itemId = itemId;
        this.ge = ge;
    }

    @Override public String name()                              { return "SelectSellItem(item=" + itemId + ")"; }
    @Override public int priority()                             { return 100; }
    @Override public int timeoutTicks()                         { return 10; }
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
        // Find any slot whose canonical (unnoted) id matches — covers both
        // noted and unnoted forms transparently. We click using the actual
        // slot's itemId so CLICK_INV_ITEM resolves to the on-screen icon.
        ItemStack found = s.inventory().findAnyForm(itemId).orElse(null);
        if (found == null) {
            step.put(K_PRECONDITION,
                new GeBlockReason.InsufficientSellItems(itemId, 1, 0));
            return;
        }
        ge.selectSellItemFromInventory(found.slot(), found.itemId());
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
        // Wait for the inventory-click dispatch chain to finish before
        // letting SetQuantityStep dispatch into the worker.
        if (dispatcher != null && dispatcher.isBusy()) {
            return Completion.RUNNING;
        }
        // Don't trust the dispatch — verify the engine actually attached
        // an item to the sell setup. GE_NEWOFFER_QUANTITY transitions
        // 0 → suggested-qty in the same tick the inv item is accepted
        // (verified via click-inspector 2026-05-01: noted Pie shell
        // click → varbit GE_NEWOFFER_QUANTITY 0 → 19). If we never see
        // it move, the click missed (bad widget routing, hidden parent,
        // wrong slot) and the step times out — better than silently
        // marching on to SetQuantityStep which would burn 18s waiting
        // for a chatbox prompt that never opens.
        if (s.grandExchange().newOfferQuantity() <= 0) {
            return Completion.RUNNING;
        }
        return new Completion.Succeeded("inventory item attached to sell setup");
    }

    @Override
    public Recovery onFailure(Failure f, WorldSnapshot s, Blackboard bb) {
        if (f.diagnostic() instanceof GeBlockReason.GeOfferSetupNotOpen) {
            return new Recovery.Abort("offer-setup not open");
        }
        if (f.diagnostic() instanceof GeBlockReason.InsufficientSellItems) {
            return new Recovery.Abort("item not in inventory");
        }
        return new Recovery.Retry(2);
    }
}
