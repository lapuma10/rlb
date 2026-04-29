package net.runelite.client.plugins.recorder.combat;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.plugins.recorder.widget.WidgetActions;
import net.runelite.client.sequence.dispatch.HumanizedInputDispatcher;

/** Public API for the combat-tab side panel: query + set the active
 *  combat style, query + set auto-retaliate. Mirrors the shape of
 *  {@code BankInteraction} so other scripts can call into it without
 *  knowing about the underlying widget IDs / varplayers.
 *
 *  <p>All mutating calls return a boolean:
 *  <ul>
 *    <li>{@code true}  — the requested state is already correct OR a
 *        click was queued. Caller should re-poll on the next tick to
 *        verify the engine processed the change.</li>
 *    <li>{@code false} — the combat tab isn't open (so the click would
 *        either fall through to the canvas behind it or get silently
 *        dropped). Caller should open the combat tab and retry.</li>
 *  </ul>
 *
 *  <p>The combat tab is the right-side panel showing the four attack
 *  style buttons and the auto-retaliate toggle. We do NOT currently
 *  open it automatically — the OSRS engine's tab-switch widget IDs
 *  vary by sidebar layout (fixed / resizable-classic / resizable-modern),
 *  and a misfired tab click can land on a sidebar slot we don't intend.
 *  The user is expected to leave the combat tab open while the bot runs
 *  (matches V1 / V2 behaviour). When that's not the case, this util
 *  refuses to dispatch — it does NOT silently click hidden widgets,
 *  which was the cause of the player drifting toward Al-Kharid in the
 *  V3 stuck-in-combat bug. */
@Slf4j
public final class CombatTabActions
{
    /** VarPlayer 43: 0..3 → which of the four attack style buttons is
     *  active (Aggressive / Accurate / Controlled / Defensive depends
     *  on the equipped weapon). */
    private static final int VARP_ATTACK_STYLE = VarPlayerID.COM_MODE;
    /** VarPlayer 172: 0 = ON, 1 = OFF. Counter-intuitive but the
     *  in-game widget toggles this exact varplayer. */
    private static final int VARP_AUTO_RETALIATE = VarPlayerID.OPTION_NODEF;

    private static final int WIDGET_RETALIATE = InterfaceID.CombatInterface.RETALIATE;
    private static final int[] STYLE_WIDGET_IDS = {
        InterfaceID.CombatInterface._0,
        InterfaceID.CombatInterface._1,
        InterfaceID.CombatInterface._2,
        InterfaceID.CombatInterface._3,
    };

    private final Client client;
    private final WidgetActions widgets;

    public CombatTabActions(Client client,
                            ClientThread clientThread,
                            HumanizedInputDispatcher dispatcher)
    {
        this(client, new WidgetActions(client, clientThread, dispatcher));
    }

    /** Test seam: inject a pre-built WidgetActions. */
    public CombatTabActions(Client client, WidgetActions widgets)
    {
        this.client = client;
        this.widgets = widgets;
    }

    /** True if the combat-tab panel is the active side-panel tab —
     *  specifically, when the first style button widget is visible up
     *  through every ancestor. */
    public boolean isCombatTabOpen()
    {
        return widgets.isVisible(STYLE_WIDGET_IDS[0]);
    }

    /** The currently-active combat style index (0..3). */
    public int currentStyleIndex()
    {
        return client.getVarpValue(VARP_ATTACK_STYLE);
    }

    /** True iff auto-retaliate is currently enabled. */
    public boolean isAutoRetaliateOn()
    {
        return client.getVarpValue(VARP_AUTO_RETALIATE) == 0;
    }

    /** Set the active combat style. No-op (returns true) if already on
     *  the desired style. Returns false if the combat tab is closed —
     *  caller should open it and retry. */
    public boolean setStyle(CombatStyleIndex desired)
    {
        if (currentStyleIndex() == desired.widgetIndex()) return true;
        if (!isCombatTabOpen())
        {
            log.debug("setStyle({}) — combat tab closed; user must open it", desired);
            return false;
        }
        boolean clicked = widgets.clickWidget(STYLE_WIDGET_IDS[desired.widgetIndex()]);
        if (clicked)
        {
            log.info("CombatTab: style click {} → {} (current varp={})",
                currentStyleIndex(), desired, currentStyleIndex());
        }
        return clicked;
    }

    /** Set auto-retaliate ON or OFF. No-op (returns true) if already in
     *  the desired state. Returns false if the combat tab is closed. */
    public boolean setAutoRetaliate(boolean desiredOn)
    {
        boolean currentOn = isAutoRetaliateOn();
        if (currentOn == desiredOn) return true;
        if (!isCombatTabOpen())
        {
            log.debug("setAutoRetaliate({}) — combat tab closed; user must open it",
                desiredOn);
            return false;
        }
        boolean clicked = widgets.clickWidget(WIDGET_RETALIATE);
        if (clicked)
        {
            log.info("CombatTab: retaliate click → {} (current varp={})",
                desiredOn ? "ON" : "OFF",
                client.getVarpValue(VARP_AUTO_RETALIATE));
        }
        return clicked;
    }
}
