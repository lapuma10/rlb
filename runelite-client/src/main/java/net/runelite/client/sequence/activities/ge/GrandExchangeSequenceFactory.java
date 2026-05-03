package net.runelite.client.sequence.activities.ge;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;
import net.runelite.api.Client;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.client.sequence.Step;
import net.runelite.client.sequence.activities.EnsureNoBlockingInterfaceStep;
import net.runelite.client.sequence.activities.banking.BankActions;
import net.runelite.client.sequence.activities.banking.CloseBankStep;
import net.runelite.client.sequence.activities.banking.HaveAtLeastInInventoryStep;
import net.runelite.client.sequence.activities.banking.OpenBankStep;
import net.runelite.client.sequence.activities.banking.WaitForBankReadyStep;
import net.runelite.client.sequence.activities.banking.WithdrawItemStep;
import net.runelite.client.sequence.activities.banking.WithdrawQuantity;
import net.runelite.client.sequence.composite.LinearSequence;
import net.runelite.client.sequence.composite.Selector;
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

    /** Allow-list of widget root ids the GE-Core reactive treats as expected
     *  (not blockers). */
    public static final Set<Integer> GE_ROOTS = Set.of(
        InterfaceID.GeOffers.UNIVERSE,
        InterfaceID.GeCollect.UNIVERSE,
        // Popupoverlay surfaces the "Your offer is much higher than the
        // guide price" warning during ConfirmOffer. ConfirmOfferStep
        // dismisses it itself (Yes for price-checks, No otherwise) — the
        // reactive must NOT Escape it out from under us.
        InterfaceID.Popupoverlay.UNIVERSE
    );

    /** Bank-Phase-B reactive allow-list adds the bank widget so the reactive
     *  doesn't try to dismiss the bank itself while WithdrawItemStep is
     *  running. */
    public static final Set<Integer> BANK_ROOTS = Set.of(
        InterfaceID.Bankmain.UNIVERSE
    );

    /** Combined allow-list for bank-prep variants: GE roots + bank root. */
    private static final Set<Integer> GE_AND_BANK_ROOTS = unionOf(GE_ROOTS, BANK_ROOTS);

    private static Set<Integer> unionOf(Set<Integer> a, Set<Integer> b) {
        Set<Integer> out = new HashSet<>(a);
        out.addAll(b);
        return Set.copyOf(out);
    }

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
        return buyCore(intent, geArea, ge, null);
    }

    /** Same as {@link #buyCore(BuyItemIntent, WorldArea, GeActions)} but
     *  threads a {@link Client} into {@link CollectOfferStep} for item-name
     *  log resolution. */
    public static GrandExchangeSequencePlan buyCore(
            BuyItemIntent intent,
            WorldArea geArea,
            GeActions ge,
            @Nullable Client client) {
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
            .then(new PickSearchResultStep(intent.itemId(), intent.displayName(), ge))
            .then(new SetQuantityStep(intent.quantity(), ge))
            .then(new SetPriceStep(priceEach, ge))
            .then(new ConfirmOfferStep(intent.itemId(), OfferSide.BUY, intent.quantity(), priceEach, ge));

        Step root = new LinearSequence("BuyItemAtGECore")
            .then(new EnsureAtGrandExchangeStep(geArea))
            .then(new OpenGrandExchangeStep(ge))
            .then(new CollectAllCompletedOffersStep(ge))
            .then(new EnsureInventoryForBuyStep(intent.quantity(), priceEach))
            .then(new EnsureNoConflictingOfferStep(intent.itemId(), OfferSide.BUY))
            .then(createOffer)
            .then(new WaitForOfferStep(intent.waitPolicy()))
            .then(new CollectOfferStep(ge, client));

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
        return sellCore(intent, geArea, ge, null);
    }

    /** Same as {@link #sellCore(SellItemIntent, WorldArea, GeActions)} but
     *  threads a {@link Client} into {@link CollectOfferStep} for item-name
     *  log resolution. */
    public static GrandExchangeSequencePlan sellCore(
            SellItemIntent intent,
            WorldArea geArea,
            GeActions ge,
            @Nullable Client client) {
        if (intent == null) throw new IllegalArgumentException("intent must not be null");
        if (geArea == null) throw new IllegalArgumentException("geArea must not be null");
        if (ge == null)     throw new IllegalArgumentException("GeActions must not be null");

        int priceEach = exactPrice(intent.pricePolicy());

        Step createOffer = new LinearSequence("CreateSellOffer")
            .then(new StartOfferStep(OfferSide.SELL, ge))
            .then(new SelectSellItemStep(intent.itemId(), ge))
            .then(new SetQuantityStep(intent.quantity(), ge))
            .then(new SetPriceStep(priceEach, ge))
            .then(new ConfirmOfferStep(intent.itemId(), OfferSide.SELL, intent.quantity(), priceEach, ge));

        Step root = new LinearSequence("SellItemAtGECore")
            .then(new EnsureAtGrandExchangeStep(geArea))
            .then(new OpenGrandExchangeStep(ge))
            .then(new CollectAllCompletedOffersStep(ge))
            .then(new EnsureInventoryForSellStep(intent.itemId(), intent.quantity()))
            .then(new EnsureNoConflictingOfferStep(intent.itemId(), OfferSide.SELL))
            .then(createOffer)
            .then(new WaitForOfferStep(intent.waitPolicy()))
            .then(new CollectOfferStep(ge, client));

        List<Step> reactives = List.of(new EnsureNoBlockingInterfaceStep(GE_ROOTS));
        return new GrandExchangeSequencePlan(root, reactives);
    }

    private static int exactPrice(PricePolicy p) {
        if (p instanceof PricePolicy.Exact e) return e.coinsEach();
        throw new UnsupportedOperationException("only PricePolicy.Exact is supported in this proof");
    }

    // ─── Phase B: bank-prep variants ────────────────────────────────────────

    /** OSRS coin item id (used for the buy-side bank withdraw). */
    private static final int COINS_ITEM_ID = 995;

    /** "Nice" buffered coin amounts the bank-prep flow rounds the
     *  required total UP to. We never withdraw the exact total because
     *  small price drift / partial-fill gaps shouldn't bust the buy.
     *  Per user spec: 2k → 10k, 15k → 50k, 99k → 100k, 300k → 500k.
     *  The {1, 5} × 10^n sequence skips 5×10^n at every k → 10k,
     *  M → 10M boundary so a {@code 5k} withdraw (a more conspicuous
     *  bot-tell at the bank stand) never appears. */
    private static final long[] NICE_COIN_BUFFERS = {
        10L,             50L,             100L,             500L,
        1_000L,          10_000L,         50_000L,          100_000L,         500_000L,
        1_000_000L,      10_000_000L,     50_000_000L,      100_000_000L,     500_000_000L,
        1_000_000_000L,  10_000_000_000L
    };

    /** Round {@code total} UP to the next entry in {@link #NICE_COIN_BUFFERS}.
     *  Examples: {@code 2k → 10k, 15k → 50k, 99k → 100k, 300k → 500k}.
     *  Caps at {@link Integer#MAX_VALUE} because {@code BankActions.withdrawX}
     *  takes an int. */
    static int roundUpToNiceCoinAmount(long total) {
        if (total <= 0) return 0;
        for (long n : NICE_COIN_BUFFERS) {
            if (n > total) {
                return n > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) n;
            }
        }
        return Integer.MAX_VALUE;
    }

    /**
     * Buy with bank prep: withdraw coins from a bank booth at the GE first,
     * then run the GE Core buy sequence. Player must already be in
     * {@code geArea} (covers the bank booths inside the GE).
     */
    public static GrandExchangeSequencePlan buyWithBankPrep(
            BuyItemIntent intent,
            WorldArea geArea,
            BankActions bank,
            GeActions ge) {
        return buyWithBankPrep(intent, geArea, bank, ge, null);
    }

    /** Same as {@link #buyWithBankPrep(BuyItemIntent, WorldArea, BankActions, GeActions)}
     *  but threads a {@link Client} into {@link CollectOfferStep} for item-name
     *  log resolution. */
    public static GrandExchangeSequencePlan buyWithBankPrep(
            BuyItemIntent intent,
            WorldArea geArea,
            BankActions bank,
            GeActions ge,
            @Nullable Client client) {
        if (intent == null) throw new IllegalArgumentException("intent must not be null");
        if (geArea == null) throw new IllegalArgumentException("geArea must not be null");
        if (bank == null)   throw new IllegalArgumentException("BankActions must not be null");
        if (ge == null)     throw new IllegalArgumentException("GeActions must not be null");

        int priceEach = exactPrice(intent.pricePolicy());
        long totalCost = (long) intent.quantity() * (long) priceEach;
        if (totalCost > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                "totalCost overflows int: " + intent.quantity() + " * " + priceEach);
        }
        int totalCostInt = (int) totalCost;
        // Round up to a buffered amount so we never withdraw the exact
        // cost — see roundUpToNiceCoinAmount.
        int withdrawCoinAmount = roundUpToNiceCoinAmount(totalCost);

        Step createOffer = new LinearSequence("CreateBuyOffer")
            .then(new StartOfferStep(OfferSide.BUY, ge))
            .then(new SelectItemStep(intent.itemId(), intent.displayName(), ge))
            .then(new PickSearchResultStep(intent.itemId(), intent.displayName(), ge))
            .then(new SetQuantityStep(intent.quantity(), ge))
            .then(new SetPriceStep(priceEach, ge))
            .then(new ConfirmOfferStep(intent.itemId(), OfferSide.BUY, intent.quantity(), priceEach, ge));

        // Bank sub-flow is conditional: if the player already has enough
        // coins to fund the trade (inventory.count(coins) >= totalCost),
        // skip opening the bank entirely. Otherwise withdraw the rounded
        // buffered amount FLAT (not "top-up to the buffer") so a starting
        // balance of 4_999 doesn't produce a 45_001-coin withdraw — we
        // pull the full 50_000 either way.
        Step bankWithdraw = new LinearSequence("BankWithdrawCoins")
            .then(new OpenBankStep(Set.of(), bank))
            .then(new WaitForBankReadyStep())
            .then(new WithdrawItemStep(COINS_ITEM_ID,
                new WithdrawQuantity.WithdrawAmount(withdrawCoinAmount), bank))
            .then(new CloseBankStep(bank));
        Step ensureCoins = new Selector("EnsureCoinsForBuy")
            .option(new HaveAtLeastInInventoryStep(COINS_ITEM_ID, totalCostInt, "Coins"))
            .option(bankWithdraw);

        Step root = new LinearSequence("BuyItemAtGEWithBankPrep")
            .then(new EnsureAtGrandExchangeStep(geArea))
            .then(ensureCoins)
            // GE Core sub-flow
            .then(new OpenGrandExchangeStep(ge))
            .then(new CollectAllCompletedOffersStep(ge))
            .then(new EnsureInventoryForBuyStep(intent.quantity(), priceEach))
            .then(new EnsureNoConflictingOfferStep(intent.itemId(), OfferSide.BUY))
            .then(createOffer)
            .then(new WaitForOfferStep(intent.waitPolicy()))
            .then(new CollectOfferStep(ge, client));

        // Reactive needs to allow BOTH bank and GE roots so it doesn't try to
        // dismiss whichever interface is open during the active sub-flow.
        List<Step> reactives = List.of(new EnsureNoBlockingInterfaceStep(GE_AND_BANK_ROOTS));
        return new GrandExchangeSequencePlan(root, reactives);
    }

    /**
     * Sell with bank prep: withdraw the sell items from a bank booth at the
     * GE first, then run the GE Core sell sequence.
     */
    public static GrandExchangeSequencePlan sellWithBankPrep(
            SellItemIntent intent,
            WorldArea geArea,
            BankActions bank,
            GeActions ge) {
        return sellWithBankPrep(intent, geArea, bank, ge, null);
    }

    /** Same as {@link #sellWithBankPrep(SellItemIntent, WorldArea, BankActions, GeActions)}
     *  but threads a {@link Client} into {@link CollectOfferStep} for item-name
     *  log resolution. */
    public static GrandExchangeSequencePlan sellWithBankPrep(
            SellItemIntent intent,
            WorldArea geArea,
            BankActions bank,
            GeActions ge,
            @Nullable Client client) {
        if (intent == null) throw new IllegalArgumentException("intent must not be null");
        if (geArea == null) throw new IllegalArgumentException("geArea must not be null");
        if (bank == null)   throw new IllegalArgumentException("BankActions must not be null");
        if (ge == null)     throw new IllegalArgumentException("GeActions must not be null");

        int priceEach = exactPrice(intent.pricePolicy());

        Step createOffer = new LinearSequence("CreateSellOffer")
            .then(new StartOfferStep(OfferSide.SELL, ge))
            .then(new SelectSellItemStep(intent.itemId(), ge))
            .then(new SetQuantityStep(intent.quantity(), ge))
            .then(new SetPriceStep(priceEach, ge))
            .then(new ConfirmOfferStep(intent.itemId(), OfferSide.SELL, intent.quantity(), priceEach, ge));

        Step root = new LinearSequence("SellItemAtGEWithBankPrep")
            .then(new EnsureAtGrandExchangeStep(geArea))
            // Bank sub-flow — withdraw the sell items.
            .then(new OpenBankStep(Set.of(), bank))
            .then(new WaitForBankReadyStep())
            .then(new WithdrawItemStep(intent.itemId(), new WithdrawQuantity.AtLeast(intent.quantity()), bank))
            .then(new CloseBankStep(bank))
            // GE Core sub-flow
            .then(new OpenGrandExchangeStep(ge))
            .then(new CollectAllCompletedOffersStep(ge))
            .then(new EnsureInventoryForSellStep(intent.itemId(), intent.quantity()))
            .then(new EnsureNoConflictingOfferStep(intent.itemId(), OfferSide.SELL))
            .then(createOffer)
            .then(new WaitForOfferStep(intent.waitPolicy()))
            .then(new CollectOfferStep(ge, client));

        List<Step> reactives = List.of(new EnsureNoBlockingInterfaceStep(GE_AND_BANK_ROOTS));
        return new GrandExchangeSequencePlan(root, reactives);
    }
}
