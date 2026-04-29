package net.runelite.client.plugins.recorder.widget;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.sequence.dispatch.HumanizedInputDispatcher;
import net.runelite.client.sequence.internal.ActionRequest;

/** Safe wrappers around widget interaction. Every click goes through a
 *  visibility check first — clicking a hidden widget falls through to
 *  the canvas behind it and the engine treats it as a tile walk, drifting
 *  the player away from where they should be.
 *
 *  <p>Used as a thin shim by domain-specific utilities (e.g. {@link
 *  net.runelite.client.plugins.recorder.combat.CombatTabActions}) that
 *  encapsulate "click this widget if and only if the parent panel is
 *  open". Other scripts can call {@link #isVisible(int)} directly when
 *  they need to gate behaviour on UI state.
 *
 *  <p>Threading: the visibility probes hop to the client thread; the
 *  click dispatch is fire-and-forget (HumanizedInputDispatcher serialises
 *  through its own busy flag). Safe to call from any worker thread. */
@Slf4j
public final class WidgetActions
{
    private final Client client;
    private final ClientThread clientThread;
    private final HumanizedInputDispatcher dispatcher;

    public WidgetActions(Client client,
                         ClientThread clientThread,
                         HumanizedInputDispatcher dispatcher)
    {
        this.client = client;
        this.clientThread = clientThread;
        this.dispatcher = dispatcher;
    }

    /** True when {@code widgetId} resolves to a Widget AND that widget +
     *  every ancestor up to the root layer reports {@code !isHidden()}.
     *  The OSRS engine only renders a widget when the entire ancestor
     *  chain is visible — checking just the leaf misses tabs that are
     *  collapsed at the root sidebar level. */
    public boolean isVisible(int widgetId)
    {
        return Boolean.TRUE.equals(onClient(() -> {
            Widget w = client.getWidget(widgetId);
            return w != null && !isHiddenIncludingAncestors(w);
        }));
    }

    /** Dispatch a CLICK_WIDGET against {@code widgetId} only if it's
     *  visible. Returns true if a click was queued, false if the widget
     *  isn't visible (caller is expected to open the parent panel
     *  first). Skips silently when {@link HumanizedInputDispatcher#isBusy()}
     *  is true — the dispatcher would drop the click anyway and the
     *  caller should re-attempt next tick. */
    public boolean clickWidget(int widgetId)
    {
        if (dispatcher.isBusy()) return false;
        if (!isVisible(widgetId)) return false;
        ActionRequest req = ActionRequest.builder()
            .kind(ActionRequest.Kind.CLICK_WIDGET)
            .channel(ActionRequest.Channel.MOUSE)
            .widgetId(widgetId)
            .build();
        dispatcher.dispatch(req);
        return true;
    }

    private static boolean isHiddenIncludingAncestors(Widget w)
    {
        for (Widget cur = w; cur != null; cur = cur.getParent())
        {
            if (cur.isHidden()) return true;
        }
        return false;
    }

    private <T> T onClient(java.util.function.Supplier<T> sup)
    {
        if (clientThread == null) return sup.get();
        if (client.isClientThread()) return sup.get();
        java.util.concurrent.atomic.AtomicReference<T> ref =
            new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.CountDownLatch latch =
            new java.util.concurrent.CountDownLatch(1);
        clientThread.invokeLater(() -> {
            try { ref.set(sup.get()); }
            catch (Throwable t) { log.warn("widget-actions: onClient threw", t); }
            finally { latch.countDown(); }
        });
        try
        {
            if (!latch.await(2000, java.util.concurrent.TimeUnit.MILLISECONDS))
            {
                log.warn("widget-actions: onClient timed out");
                return null;
            }
        }
        catch (InterruptedException ie)
        {
            Thread.currentThread().interrupt();
            return null;
        }
        return ref.get();
    }
}
