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
        // Async typing means each step polls snapshot for transitions and
        // falls back to elapsed-tick success after ~5 ticks. SelectItem +
        // PickSearchResult + deferred-dispatch SetQuantity + SetPrice each
        // need a multi-tick fallback window, so total setup before
        // ConfirmOffer is ~25-35 engine ticks. Keep setupOpen=true wide.
        for (int t = 5; t < 50; t++) {
            snaps.add(snapAtGe().tick(t).geOpen(true).geSetupOpen(true).build());
        }
        // Offer appears in slot 0 after Confirm click — wide ACTIVE window.
        for (int t = 50; t < 65; t++) {
            snaps.add(snapAtGe().tick(t).geOpen(true).geSetupOpen(true)
                .offer(0, OfferSide.BUY, OfferStatus.ACTIVE, 4151, 1, 0, 1_500_000)
                .build());
        }
        // Offer fills — wide COMPLETE window so WaitForOfferStep observes
        // it through its first few check() calls.
        for (int t = 65; t < 75; t++) {
            snaps.add(snapAtGe().tick(t).geOpen(true).geSetupOpen(true)
                .offer(0, OfferSide.BUY, OfferStatus.COMPLETE, 4151, 1, 1, 1_500_000)
                .build());
        }
        // After collect: slot empty, inventory has the bought item.
        for (int t = 75; t < 130; t++) {
            snaps.add(new GeSnapBuilder()
                .tick(t).player(3160, 3490, 0)
                .invCoins(1_000_000).invItem(4151, 1)
                .geOpen(true)
                .build());
        }

        BuyItemIntent intent = new BuyItemIntent(
            4151, "Abyssal whip", 1, new PricePolicy.Exact(1_500_000), OfferWaitPolicy.until(50));
        RecordingGeActions ge = new RecordingGeActions();
        GrandExchangeSequencePlan plan = GrandExchangeSequenceFactory.buyCore(intent, GE_AREA, ge);

        GeEngineHarness h = new GeEngineHarness().queue(snaps);
        h.run(plan.root());
        h.advance(120);

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
