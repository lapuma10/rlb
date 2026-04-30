package net.runelite.client.sequence.activities.ge;

import java.util.ArrayList;
import java.util.List;
import net.runelite.api.coords.WorldArea;
import net.runelite.client.sequence.SequenceState;
import net.runelite.client.sequence.WorldSnapshot;
import net.runelite.client.sequence.activities.EnsureNoBlockingInterfaceStep;
import net.runelite.client.sequence.views.OfferSide;
import net.runelite.client.sequence.views.OfferStatus;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class GrandExchangeSequenceFactoryTest {

    /** Varrock GE rough rectangle. */
    private static final WorldArea GE_AREA = new WorldArea(3140, 3470, 30, 30, 0);

    @Test
    public void planExposesRootAndReactives() {
        BuyItemIntent intent = new BuyItemIntent(
            4151, "Abyssal whip", 1, new PricePolicy.Exact(1_500_000), OfferWaitPolicy.until(50));
        GrandExchangeSequencePlan plan = GrandExchangeSequenceFactory.buyCore(
            intent, GE_AREA, new RecordingGeActions());

        assertEquals(1, plan.reactiveSteps().size());
        assertSame(EnsureNoBlockingInterfaceStep.class, plan.reactiveSteps().get(0).getClass());
    }

    @Test
    public void buyCoreHappyPath() {
        // Snapshot timeline driving each step to success in order.
        // FixtureObserver sticks at the last snapshot once exhausted, so we
        // pad with the final state for plenty of ticks.
        List<WorldSnapshot> snaps = new ArrayList<>();

        // tick 0..1: at GE, GE closed, coins available
        snaps.add(snapAtGe().tick(0).geOpen(false).build());
        snaps.add(snapAtGe().tick(1).geOpen(false).build());
        // GE opens
        snaps.add(snapAtGe().tick(2).geOpen(true).build());
        snaps.add(snapAtGe().tick(3).geOpen(true).build());
        snaps.add(snapAtGe().tick(4).geOpen(true).build());
        // Setup opens after StartOffer click. Async TYPE_CHATBOX dispatch
        // means each typing step now polls snapshot for a state transition:
        //   SelectItemStep: searchResultsPopulated() flips true
        //   SetQuantityStep / SetPriceStep: chatboxPromptMode goes 0 -> 7 -> 0
        //   PickSearchResultStep: search results closed
        // Provide snapshots that simulate those transitions in order.
        // Tick 5: setup just opened, no search results yet.
        snaps.add(snapAtGe().tick(5).geOpen(true).geSetupOpen(true).build());
        // Tick 6: SelectItem typed → search list rendered.
        snaps.add(snapAtGe().tick(6).geOpen(true).geSetupOpen(true)
            .searchResultsPopulated(true).build());
        // Tick 7: PickSearchResult clicked → search closed.
        snaps.add(snapAtGe().tick(7).geOpen(true).geSetupOpen(true).build());
        // Tick 8-9: SetQuantity numeric prompt opens (mode 7) then closes.
        snaps.add(snapAtGe().tick(8).geOpen(true).geSetupOpen(true)
            .chatboxPromptMode(7).build());
        snaps.add(snapAtGe().tick(9).geOpen(true).geSetupOpen(true).build());
        // Tick 10-11: SetPrice numeric prompt opens then closes.
        snaps.add(snapAtGe().tick(10).geOpen(true).geSetupOpen(true)
            .chatboxPromptMode(7).build());
        snaps.add(snapAtGe().tick(11).geOpen(true).geSetupOpen(true).build());
        // Tick 12: ConfirmOffer fires; setup still open until offer arrives.
        snaps.add(snapAtGe().tick(12).geOpen(true).geSetupOpen(true).build());
        // Offer appears in slot 0 after Confirm click — matching our 4151/BUY/1@1.5M
        snaps.add(snapAtGe().tick(13).geOpen(true).geSetupOpen(true)
            .offer(0, OfferSide.BUY, OfferStatus.ACTIVE, 4151, 1, 0, 1_500_000)
            .build());
        snaps.add(snapAtGe().tick(14).geOpen(true).geSetupOpen(true)
            .offer(0, OfferSide.BUY, OfferStatus.ACTIVE, 4151, 1, 0, 1_500_000)
            .build());
        // Offer fills
        snaps.add(snapAtGe().tick(15).geOpen(true).geSetupOpen(true)
            .offer(0, OfferSide.BUY, OfferStatus.COMPLETE, 4151, 1, 1, 1_500_000)
            .build());
        // After collect: slot empty, inventory has the bought item
        for (int t = 16; t < 40; t++) {
            snaps.add(new GeSnapBuilder()
                .tick(t).player(3160, 3490, 0)
                .invCoins(1_000_000).invItem(4151, 1)
                .geOpen(true)
                .build());
        }

        BuyItemIntent intent = new BuyItemIntent(
            4151, "Abyssal whip", 1, new PricePolicy.Exact(1_500_000), OfferWaitPolicy.until(8));
        RecordingGeActions ge = new RecordingGeActions();
        GrandExchangeSequencePlan plan = GrandExchangeSequenceFactory.buyCore(intent, GE_AREA, ge);

        GeEngineHarness h = new GeEngineHarness().queue(snaps);
        h.run(plan.root());
        h.advance(30);

        // DEBUG
        System.out.println("=== test debug: state=" + h.state());
        for (String c : ge.calls()) System.out.println("=== call: " + c);

        assertEquals("buy-core happy path should complete", SequenceState.IDLE, h.state());

        List<String> calls = ge.calls();
        assertTrue("openGrandExchange dispatched", calls.contains("openGrandExchange()"));
        assertTrue("clickOfferSlotButton dispatched",
            calls.contains("clickOfferSlotButton(slot=0, side=BUY)"));
        assertTrue("selectItem dispatched",
            calls.contains("selectItem(itemId=4151, name=Abyssal whip)"));
        assertTrue("setQuantity dispatched", calls.contains("setQuantity(1)"));
        assertTrue("setPrice dispatched", calls.contains("setPrice(1500000)"));
        assertTrue("confirmOffer dispatched", calls.contains("confirmOffer()"));
        assertTrue("collect dispatched", calls.contains("collect(slot=0)"));
    }

    @Test
    public void buyCoreInsufficientCoinsAborts() {
        // At GE, GE open, but only 100 coins (need 1.5M).
        WorldSnapshot s = new GeSnapBuilder()
            .tick(0).player(3160, 3490, 0).invCoins(100).geOpen(true)
            .build();
        List<WorldSnapshot> snaps = new ArrayList<>();
        for (int t = 0; t < 6; t++) snaps.add(s);

        BuyItemIntent intent = new BuyItemIntent(
            4151, "Abyssal whip", 1, new PricePolicy.Exact(1_500_000), OfferWaitPolicy.until(50));
        RecordingGeActions ge = new RecordingGeActions();
        GrandExchangeSequencePlan plan = GrandExchangeSequenceFactory.buyCore(intent, GE_AREA, ge);

        GeEngineHarness h = new GeEngineHarness().queue(snaps);
        h.run(plan.root());
        h.advance(6);

        assertEquals(SequenceState.FAILED, h.state());
        // No GE-actions click should have been dispatched: we aborted before
        // reaching the create-offer sub-sequence.
        assertEquals("no GE clicks before insufficient-coins abort", 0, ge.callCount());
    }

    /** Player at the GE with plenty of coins (default). */
    private static GeSnapBuilder snapAtGe() {
        return new GeSnapBuilder().player(3160, 3490, 0).invCoins(10_000_000);
    }
}
