package net.runelite.client.plugins.recorder.scripts;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.sequence.dispatch.HumanizedInputDispatcher;
import net.runelite.client.sequence.internal.ActionRequest;

/**
 * Clicks the in-game Logout button. Handles opening the side-panel tab
 * if needed before clicking the inner Logout panel.
 *
 * <p>Caller is responsible for ensuring the player is logged in and idle
 * (e.g. not in combat) — clicks during combat are silently dropped by the
 * engine.
 */
@Slf4j
@RequiredArgsConstructor
public final class LogoutHelper
{
    private final Client client;
    private final ClientThread clientThread;
    private final HumanizedInputDispatcher dispatcher;

    /** Multi-step logout sequence:
     *  1. Inspect whether the inner Logout panel is visible.
     *  2. If yes — dispatch CLICK_WIDGET on `Logout.LOGOUT` (the actual
     *     "Click here to logout" button). Caller stops on the next tick.
     *  3. If no — dispatch CLICK_WIDGET on the side-panel tab
     *     (`Toplevel.LOGOUT` for fixed layout, `ToplevelOsm.LOGOUT` for
     *     resizable). Caller retries next tick to issue the real click.
     *  Returns true if a click was dispatched (either tab-open or final),
     *  false if no widget was found in either pass (caller retries). */
    public boolean tryLogout() throws InterruptedException
    {
        // Step 1: inner panel open?
        Widget innerLogout = onClient(() -> {
            Widget w = client.getWidget(InterfaceID.Logout.LOGOUT);
            return (w != null && !w.isHidden()) ? w : null;
        });
        if (innerLogout != null)
        {
            log.info("logout: clicking inner LOGOUT widget");
            ActionRequest req = ActionRequest.builder()
                .kind(ActionRequest.Kind.CLICK_WIDGET)
                .channel(ActionRequest.Channel.MOUSE)
                .widgetId(InterfaceID.Logout.LOGOUT)
                .build();
            dispatcher.dispatch(req);
            return true;
        }
        // Step 2: open the side-panel tab.
        // STONE10 is the logout-orb tab. There are THREE layouts in OSRS:
        //   - Toplevel        (fixed classic)
        //   - ToplevelOsm     (resizable modern)
        //   - ToplevelPreEoc  (resizable classic / pre-EoC)
        // client.isResized() picks "resizable" but doesn't tell us which
        // resizable variant is active, so probe all three and use the
        // first visible one. Order matters only for log clarity — only
        // one is rendered at a time.
        Integer tabWidgetId = onClient(() -> {
            int[] candidates = {
                InterfaceID.Toplevel.STONE10,
                InterfaceID.ToplevelOsm.STONE10,
                InterfaceID.ToplevelPreEoc.STONE10,
            };
            for (int wid : candidates)
            {
                Widget w = client.getWidget(wid);
                if (w != null && !w.isHidden()) return wid;
            }
            return null;
        });
        if (tabWidgetId != null)
        {
            log.info("logout: opening side-panel logout tab (widget {})", Integer.toHexString(tabWidgetId));
            ActionRequest req = ActionRequest.builder()
                .kind(ActionRequest.Kind.CLICK_WIDGET)
                .channel(ActionRequest.Channel.MOUSE)
                .widgetId(tabWidgetId)
                .build();
            dispatcher.dispatch(req);
            return true;
        }
        log.debug("logout: neither inner panel nor side-panel tab visible — retry");
        return false;
    }

    private <T> T onClient(Supplier<T> sup) throws InterruptedException
    {
        AtomicReference<T> ref = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        clientThread.invokeLater(() ->
        {
            try { ref.set(sup.get()); }
            catch (Throwable th) { log.warn("logout: onClient threw", th); }
            finally { latch.countDown(); }
        });
        latch.await();
        return ref.get();
    }
}
