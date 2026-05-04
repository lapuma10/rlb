package net.runelite.client.sequence.activities.ge;

import java.awt.Rectangle;
import java.util.concurrent.atomic.AtomicBoolean;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.sequence.dispatch.HumanizedInputDispatcher;
import org.junit.Test;

import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link GeInteraction#runCollectFromDetail} — specifically
 * the coin-refund scenario (BUY offer item=1933 + leftover coins=995) where
 * the coins widget is not yet visible when the item button is first scanned
 * but appears ~800ms later.
 *
 * <p>Regression: before the fix, a flat 700ms sleep sampled DETAILS_COLLECT
 * mid-transition (item widget still fading, coins not rendered yet).  The
 * {@code anyNew==false} early-exit then broke the loop, leaving coins
 * uncollected and the offer slot stuck in COMPLETE until a step timeout and
 * retry.
 *
 * <p>Widget states are driven by Mockito sequential returns so we don't
 * need a live OSRS client.  {@link HumanizedInputDispatcher#runOnClient}
 * is stubbed to execute the supplier synchronously (no ClientThread).
 * {@link net.runelite.client.sequence.dispatch.SequenceSleep#sleep} passes
 * through to {@link Thread#sleep} when the client argument is null, so the
 * tests are real-time (expect ~1–3 s due to poll waits).
 */
public class GeInteractionCollectTest {

    /** Flour (item used in the live repro from the bug report). */
    private static final int FLOUR = 1933;
    private static final int COINS = 995;

    private static final Rectangle ITEM_BOUNDS  = new Rectangle(10, 10, 50, 50);
    private static final Rectangle COINS_BOUNDS = new Rectangle(70, 10, 50, 50);

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Wire dispatcher.runOnClient to actually execute the supplier
     *  synchronously (mirrors what the real dispatcher does on its worker
     *  thread, but without a ClientThread). */
    @SuppressWarnings("unchecked")
    private static void wireRunOnClient(HumanizedInputDispatcher dispatcher) throws Exception {
        doAnswer(inv -> ((java.util.function.Supplier<?>) inv.getArgument(0)).get())
            .when(dispatcher).runOnClient(any());
    }

    private static Widget itemWidget() {
        Widget w = mock(Widget.class);
        when(w.isHidden()).thenReturn(false);
        when(w.getBounds()).thenReturn(ITEM_BOUNDS);
        when(w.getActions()).thenReturn(new String[]{"Collect-notes", "Collect-items", "Bank", "Examine"});
        when(w.getItemId()).thenReturn(FLOUR);
        return w;
    }

    private static Widget coinsWidget(boolean initiallyHidden) {
        Widget w = mock(Widget.class);
        when(w.getBounds()).thenReturn(COINS_BOUNDS);
        when(w.getActions()).thenReturn(new String[]{"Collect", "Bank", "Examine"});
        when(w.getItemId()).thenReturn(COINS);
        when(w.isHidden()).thenReturn(initiallyHidden);
        return w;
    }

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------

    /**
     * Regression: coins widget is NOT visible on the first DETAILS_COLLECT
     * scan (only item=1933 is returned by firstPass).  Coins appear after the
     * item is clicked (~800ms later in production).  The fix uses
     * {@code pollForNewOrClearedButtons} which polls every 150ms instead of
     * sleeping a flat 700ms; on poll 3 coins become visible and are clicked.
     *
     * <p>Fixture mirrors the live log:
     * <pre>
     *   idx=2: Collect-notes item=1933   ← visible from the start
     *   idx=3: Collect       item=995    ← hidden at first, visible after item click
     * </pre>
     */
    @Test
    public void coinsAppearAfterItemClick_bothCollected() throws Exception {
        HumanizedInputDispatcher dispatcher = mock(HumanizedInputDispatcher.class);
        Client client = mock(Client.class);
        wireRunOnClient(dispatcher);
        doNothing().when(dispatcher).boundsClickOnWorker(any(), anyString());
        doNothing().when(dispatcher).widgetClickOnWorker(anyInt());

        Widget itemW  = itemWidget();
        Widget coinsW = coinsWidget(/* initiallyHidden= */ true);

        // Coins widget isHidden() call sequence:
        //   call 1 (firstPass scan)        → true  (coins not yet rendered)
        //   call 2 (pollForNew poll 1)      → true
        //   call 3 (pollForNew poll 2)      → true
        //   call 4 (pollForNew poll 3)      → false ← coins appear, ~450ms after item click
        //   call 5+ (subsequent scans)      → false
        when(coinsW.isHidden())
            .thenReturn(true)
            .thenReturn(true)
            .thenReturn(true)
            .thenReturn(false)
            .thenReturn(false);

        // After coins are clicked the detail view closes: make DETAILS_COLLECT
        // return null so pollForNewOrClearedButtons exits on the very first poll
        // of the second reopen-loop iteration (avoids the full 2s timeout).
        AtomicBoolean coinsClicked = new AtomicBoolean(false);
        doAnswer(inv -> {
            Rectangle b = inv.getArgument(0);
            if (COINS_BOUNDS.equals(b)) coinsClicked.set(true);
            return null;
        }).when(dispatcher).boundsClickOnWorker(any(), anyString());

        Widget container = mock(Widget.class);
        when(container.isHidden()).thenReturn(false);
        when(container.getDynamicChildren()).thenReturn(new Widget[]{itemW, coinsW});
        when(client.getWidget(InterfaceID.GeOffers.DETAILS_COLLECT))
            .thenAnswer(inv -> coinsClicked.get() ? null : container);

        GeInteraction ge = new GeInteraction(client, null, dispatcher);
        ge.runCollectFromDetail(0);

        // Both buttons must be clicked, item before coins.
        org.mockito.InOrder order = inOrder(dispatcher);
        order.verify(dispatcher).boundsClickOnWorker(ITEM_BOUNDS, "Collect-notes");
        order.verify(dispatcher).boundsClickOnWorker(COINS_BOUNDS, "Collect");
    }

    /**
     * Both DETAILS_COLLECT children are visible from the first scan — no
     * late-appearing coins.  The firstPass loop must click both buttons
     * without needing the reopen loop at all.
     */
    @Test
    public void bothButtonsVisibleFromStart_clickedInFirstPass() throws Exception {
        HumanizedInputDispatcher dispatcher = mock(HumanizedInputDispatcher.class);
        Client client = mock(Client.class);
        wireRunOnClient(dispatcher);

        // After item+coins are both clicked, container disappears so the reopen
        // loop's first poll exits quickly rather than waiting 2s.
        AtomicBoolean bothClicked = new AtomicBoolean(false);
        int[] clickCount = {0};
        doAnswer(inv -> {
            if (++clickCount[0] >= 2) bothClicked.set(true);
            return null;
        }).when(dispatcher).boundsClickOnWorker(any(), anyString());
        doNothing().when(dispatcher).widgetClickOnWorker(anyInt());

        Widget itemW  = itemWidget();
        Widget coinsW = coinsWidget(/* initiallyHidden= */ false);

        Widget container = mock(Widget.class);
        when(container.isHidden()).thenReturn(false);
        when(container.getDynamicChildren()).thenReturn(new Widget[]{itemW, coinsW});
        when(client.getWidget(InterfaceID.GeOffers.DETAILS_COLLECT))
            .thenAnswer(inv -> bothClicked.get() ? null : container);

        GeInteraction ge = new GeInteraction(client, null, dispatcher);
        ge.runCollectFromDetail(0);

        verify(dispatcher).boundsClickOnWorker(ITEM_BOUNDS, "Collect-notes");
        verify(dispatcher).boundsClickOnWorker(COINS_BOUNDS, "Collect");
        // INDEX_0 (View offer) must NOT be clicked before both buttons are collected —
        // the GE CS2 hides INDEX_0 while GE_SELECTEDSLOT > 0 (detail view open).
        // Here both buttons exist in DETAILS_COLLECT before any click, so the
        // openOfferDetail path should not be taken.
        verify(dispatcher, never()).widgetClickOnWorker(eq(InterfaceID.GeOffers.INDEX_0));
    }

    /**
     * DETAILS_COLLECT is empty on the first scan — the detail view was not yet
     * open when the worker started.  {@code reopenDetailView} is called; after
     * it succeeds, both item and coins are collected.
     */
    @Test
    public void detailNotOpenOnFirstScan_reopensThenCollectsBoth() throws Exception {
        HumanizedInputDispatcher dispatcher = mock(HumanizedInputDispatcher.class);
        Client client = mock(Client.class);
        wireRunOnClient(dispatcher);

        AtomicBoolean bothClicked = new AtomicBoolean(false);
        int[] clickCount = {0};
        doAnswer(inv -> {
            if (++clickCount[0] >= 2) bothClicked.set(true);
            return null;
        }).when(dispatcher).boundsClickOnWorker(any(), anyString());
        doNothing().when(dispatcher).widgetClickOnWorker(anyInt());

        Widget itemW  = itemWidget();
        Widget coinsW = coinsWidget(/* initiallyHidden= */ false);

        // Container: hidden on first scan, visible after INDEX_0 is "clicked"
        // (reopenDetailView Case 4).
        AtomicBoolean detailOpened = new AtomicBoolean(false);
        doAnswer(inv -> {
            detailOpened.set(true);
            return null;
        }).when(dispatcher).widgetClickOnWorker(InterfaceID.GeOffers.INDEX_0);

        Widget container = mock(Widget.class);
        when(container.getDynamicChildren()).thenReturn(new Widget[]{itemW, coinsW});
        when(container.isHidden()).thenAnswer(inv ->
            !detailOpened.get() || bothClicked.get());

        // INDEX_0 is visible (main GE grid showing — detail not yet open).
        Widget indexWidget = mock(Widget.class);
        when(indexWidget.isHidden()).thenReturn(false);
        when(client.getWidget(InterfaceID.GeOffers.INDEX_0)).thenReturn(indexWidget);
        when(client.getWidget(InterfaceID.GeOffers.DETAILS_COLLECT)).thenReturn(container);

        GeInteraction ge = new GeInteraction(client, null, dispatcher);
        ge.runCollectFromDetail(0);

        org.mockito.InOrder order = inOrder(dispatcher);
        order.verify(dispatcher).boundsClickOnWorker(ITEM_BOUNDS, "Collect-notes");
        order.verify(dispatcher).boundsClickOnWorker(COINS_BOUNDS, "Collect");
    }

    /**
     * Verifies the test harness itself: {@code wireRunOnClient} must actually
     * invoke the supplier so that {@code findCollectButtons} reaches
     * {@code client.getWidget(DETAILS_COLLECT)}.  If runOnClient were stubbed
     * to return null, findCollectButtons would return empty on every call and
     * the test would pass trivially without exercising the real logic.
     */
    @Test
    public void emptyDetailsCollect_noClicksDispatched() throws Exception {
        HumanizedInputDispatcher dispatcher = mock(HumanizedInputDispatcher.class);
        Client client = mock(Client.class);
        wireRunOnClient(dispatcher);
        doNothing().when(dispatcher).widgetClickOnWorker(anyInt());

        // Container null everywhere — detail view was never opened and
        // reopenDetailView also cannot open it.
        when(client.getWidget(InterfaceID.GeOffers.DETAILS_COLLECT)).thenReturn(null);
        when(client.getWidget(InterfaceID.GeOffers.INDEX_0)).thenReturn(null);

        GeInteraction ge = new GeInteraction(client, null, dispatcher);
        try {
            ge.runCollectFromDetail(0);
        } catch (Exception e) {
            fail("runCollectFromDetail should return gracefully, threw: " + e);
        }

        verify(dispatcher, never()).boundsClickOnWorker(any(), isNull());
    }
}
