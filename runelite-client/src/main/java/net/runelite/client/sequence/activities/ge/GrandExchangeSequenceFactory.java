package net.runelite.client.sequence.activities.ge;

import java.util.List;
import java.util.Set;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.client.sequence.Step;
import net.runelite.client.sequence.activities.EnsureNoBlockingInterfaceStep;
import net.runelite.client.sequence.composite.LinearSequence;
import net.runelite.client.sequence.views.OfferSide;

/**
 * Builds GE Core buy / sell sequences. Phase A only: assumes the player is
 * already at the GE with coins / sell items in inventory.
 *
 * <p>Phase B (after banking lands) will add {@code buyWithBankPrep} and
 * {@code sellWithBankPrep} that prepend a bank sub-flow
 * (OpenBank → WaitForBankReady → WithdrawItem → CloseBank) before the GE
 * sequence and adjust the reactive allow-list to include
 * {@code Bankmain.UNIVERSE}.
 */
public final class GrandExchangeSequenceFactory {

    /** Allow-list of widget root ids that the reactive
     *  {@link EnsureNoBlockingInterfaceStep} treats as "expected" rather than
     *  blockers to dismiss. Phase A: GE-only. Phase B will add
     *  {@code Bankmain.UNIVERSE}. */
    public static final Set<Integer> GE_ROOTS = Set.of(
        InterfaceID.GeOffers.UNIVERSE,
        InterfaceID.GeCollect.UNIVERSE
    );

    private GrandExchangeSequenceFactory() {}

    /**
     * Buy {@code intent.quantity()} of {@code intent.itemId()} at
     * {@link PricePolicy.Exact}'s coinsEach. Player must already be at the
     * GE with enough coins in inventory.
     */
    public static GrandExchangeSequencePlan buyCore(
            BuyItemIntent intent,
            WorldArea geArea,
            GeActions ge) {
        if (intent == null) throw new IllegalArgumentException("intent must not be null");
        if (geArea == null) throw new IllegalArgumentException("geArea must not be null");
        if (ge == null)     throw new IllegalArgumentException("GeActions must not be null");

        int priceEach = exactPrice(intent.pricePolicy());
        // Overflow check: caller's UI should reject this earlier, but defend
        // here in case quantity * priceEach exceeds Integer range.
        long totalCost = (long) intent.quantity() * (long) priceEach;
        if (totalCost > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                "totalCost overflows int: " + intent.quantity() + " * " + priceEach);
        }

        Step createOffer = new LinearSequence("CreateBuyOffer")
            .then(new StartOfferStep(OfferSide.BUY, ge))
            .then(new SelectItemStep(intent.itemId(), intent.displayName(), ge))
            .then(new SetQuantityStep(intent.quantity(), ge))
            .then(new SetPriceStep(priceEach, ge))
            .then(new ConfirmOfferStep(intent.itemId(), OfferSide.BUY, intent.quantity(), priceEach, ge));

        Step root = new LinearSequence("BuyItemAtGECore")
            .then(new EnsureAtGrandExchangeStep(geArea))
            .then(new OpenGrandExchangeStep(ge))
            .then(new EnsureInventoryForBuyStep(intent.quantity(), priceEach))
            .then(new EnsureNoConflictingOfferStep(intent.itemId(), OfferSide.BUY))
            .then(createOffer)
            .then(new WaitForOfferStep(intent.waitPolicy()))
            .then(new CollectOfferStep(ge));

        List<Step> reactives = List.of(new EnsureNoBlockingInterfaceStep(GE_ROOTS));
        return new GrandExchangeSequencePlan(root, reactives);
    }

    /**
     * Sell {@code intent.quantity()} of {@code intent.itemId()} at
     * {@link PricePolicy.Exact}'s coinsEach. Player must already be at the
     * GE with enough of the item in inventory.
     */
    public static GrandExchangeSequencePlan sellCore(
            SellItemIntent intent,
            WorldArea geArea,
            GeActions ge) {
        if (intent == null) throw new IllegalArgumentException("intent must not be null");
        if (geArea == null) throw new IllegalArgumentException("geArea must not be null");
        if (ge == null)     throw new IllegalArgumentException("GeActions must not be null");

        int priceEach = exactPrice(intent.pricePolicy());

        Step createOffer = new LinearSequence("CreateSellOffer")
            .then(new StartOfferStep(OfferSide.SELL, ge))
            .then(new SelectItemStep(intent.itemId(), intent.displayName(), ge))
            .then(new SetQuantityStep(intent.quantity(), ge))
            .then(new SetPriceStep(priceEach, ge))
            .then(new ConfirmOfferStep(intent.itemId(), OfferSide.SELL, intent.quantity(), priceEach, ge));

        Step root = new LinearSequence("SellItemAtGECore")
            .then(new EnsureAtGrandExchangeStep(geArea))
            .then(new OpenGrandExchangeStep(ge))
            .then(new EnsureInventoryForSellStep(intent.itemId(), intent.quantity()))
            .then(new EnsureNoConflictingOfferStep(intent.itemId(), OfferSide.SELL))
            .then(createOffer)
            .then(new WaitForOfferStep(intent.waitPolicy()))
            .then(new CollectOfferStep(ge));

        List<Step> reactives = List.of(new EnsureNoBlockingInterfaceStep(GE_ROOTS));
        return new GrandExchangeSequencePlan(root, reactives);
    }

    private static int exactPrice(PricePolicy p) {
        if (p instanceof PricePolicy.Exact e) return e.coinsEach();
        throw new UnsupportedOperationException("only PricePolicy.Exact is supported in this proof");
    }
}
