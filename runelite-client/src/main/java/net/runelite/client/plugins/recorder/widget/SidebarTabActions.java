package net.runelite.client.plugins.recorder.widget;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Varbits;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.sequence.dispatch.HumanizedInputDispatcher;

/** Switches the active OSRS sidebar tab by clicking its actual icon
 *  widget — never via F-keys (the user can rebind), never via
 *  hardcoded pixels (layout swaps move them). Use:
 *
 *  <pre>{@code
 *  SidebarTab prev = sidebarTabs.currentTab();   // snapshot
 *  if (sidebarTabs.openTab(SidebarTab.COMBAT)) {
 *      // do combat-tab work
 *      sidebarTabs.openTab(prev);                // restore
 *  }
 *  }</pre>
 *
 *  <p>The "open" call is non-blocking — it dispatches a single click
 *  through the humanized input pipeline and returns. Callers should
 *  re-poll {@link #currentTab()} on the next tick to confirm the engine
 *  processed the switch before using the new tab's widgets.
 *
 *  <p><b>Position freshness:</b> bounds are looked up at click time by
 *  {@link WidgetActions#clickWidget(int)} → {@code
 *  PixelResolver.resolveWidget} → {@code client.getWidget(id).getBounds()}.
 *  No pixel is ever cached. */
@Slf4j
public final class SidebarTabActions
{
    private final Client client;
    private final ClientThread clientThread;
    private final WidgetActions widgets;

    public SidebarTabActions(Client client,
                             ClientThread clientThread,
                             HumanizedInputDispatcher dispatcher)
    {
        this(client, clientThread,
            new WidgetActions(client, clientThread, dispatcher));
    }

    /** Test seam — inject a pre-built WidgetActions. */
    public SidebarTabActions(Client client,
                             ClientThread clientThread,
                             WidgetActions widgets)
    {
        this.client = client;
        this.clientThread = clientThread;
        this.widgets = widgets;
    }

    /** Returns the active sidebar tab, or null if the engine reports a
     *  value we don't have a {@link SidebarTab} entry for. The engine
     *  stores the active tab index in {@link Varbits#SIDE_PANELS}. */
    public SidebarTab currentTab()
    {
        int idx = client.getVarbitValue(Varbits.SIDE_PANELS);
        SidebarTab[] vals = SidebarTab.values();
        if (idx < 0 || idx >= vals.length) return null;
        return vals[idx];
    }

    /** Returns true if {@code tab} is currently the active side panel. */
    public boolean isOpen(SidebarTab tab)
    {
        return tab != null && tab == currentTab();
    }

    /** Switch to {@code tab}. No-op (returns true) if already on it.
     *  Otherwise probes each layout's icon widget id for the requested
     *  tab and clicks the first one that's visible.
     *
     *  <p>Returns:
     *  <ul>
     *    <li>{@code true} — already on this tab OR a click was queued.
     *    <li>{@code false} — no visible icon widget was found for any
     *        known layout. The user is in a state we can't navigate
     *        (login screen, tutorial, custom layout we don't support).
     *  </ul> */
    public boolean openTab(SidebarTab tab)
    {
        if (tab == null) return false;
        if (isOpen(tab)) return true;
        for (int widgetId : tab.iconWidgetIds())
        {
            if (widgets.isVisible(widgetId))
            {
                boolean clicked = widgets.clickWidget(widgetId);
                if (clicked)
                {
                    log.info("sidebar: opening {} via widget id 0x{}",
                        tab, Integer.toHexString(widgetId));
                    return true;
                }
            }
        }
        log.debug("sidebar: no visible icon widget for {} — layout not "
            + "recognised or all widgets hidden", tab);
        return false;
    }

    /** Convenience for a click-then-wait pattern: dispatch the open,
     *  then poll the active-tab varbit until it matches OR
     *  {@code timeoutMs} elapses. Returns true if the switch landed,
     *  false on timeout / null tab. Caller is on a worker thread —
     *  this method sleeps. */
    public boolean openTabAndWait(SidebarTab tab, long timeoutMs)
        throws InterruptedException
    {
        if (tab == null) return false;
        if (isOpen(tab)) return true;
        if (!openTab(tab)) return false;
        long until = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < until)
        {
            if (isOpen(tab)) return true;
            Thread.sleep(60);
        }
        return false;
    }
}
