package net.runelite.client.plugins.recorder.combat;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.sequence.dispatch.HumanizedInputDispatcher;
import net.runelite.client.sequence.internal.ActionRequest;

/**
 * Reads the active combat style (VarPlayer 43, {@code VarPlayerID.COM_MODE})
 * and issues a CLICK_WIDGET dispatch to one of the four combat-style buttons
 * when the active style does not match the desired one.
 *
 * <p>VarPlayer 43 values 0–3 directly match {@link CombatStyleIndex#widgetIndex()}.
 * The four widget IDs are {@link InterfaceID.CombatInterface#_0} through
 * {@link InterfaceID.CombatInterface#_3}.
 *
 * <p>A throttle of 2 seconds is enforced between successive dispatches to
 * give the game engine at least one tick to commit the VarPlayer update before
 * the style is re-read.
 */
@Slf4j
public class CombatStyleSwitcher
{
    /** VarPlayer 43 — which combat-style button is active (0/1/2/3). */
    private static final int VARP_ATTACK_STYLE = VarPlayerID.COM_MODE;

    /** Minimum milliseconds between two successive click dispatches. */
    private static final long THROTTLE_MS = 2_000L;

    private static final int[] STYLE_WIDGET_IDS = {
        InterfaceID.CombatInterface._0,
        InterfaceID.CombatInterface._1,
        InterfaceID.CombatInterface._2,
        InterfaceID.CombatInterface._3,
    };

    private final Client client;
    private final CombatDispatcher dispatcher;

    /** Epoch-ms of the last dispatched click. 0 = never dispatched. */
    private long lastDispatchMs = 0L;

    /**
     * @param client       RuneLite client (reads VarPlayer 43)
     * @param clientThread unused directly; kept for API symmetry with sibling classes
     * @param dispatcher   humanized input dispatcher wrapping {@link HumanizedInputDispatcher}
     */
    public CombatStyleSwitcher(Client client,
                               @SuppressWarnings("unused") ClientThread clientThread,
                               HumanizedInputDispatcher dispatcher)
    {
        this(client, CombatDispatcher.forHumanized(dispatcher));
    }

    /** Package-private constructor for testing (accepts the interface). */
    CombatStyleSwitcher(Client client, CombatDispatcher dispatcher)
    {
        this.client = client;
        this.dispatcher = dispatcher;
    }

    /**
     * Ensures the active combat style matches {@code desired}.
     *
     * @param desired the combat style to switch to
     * @return {@code true} if the style was already correct (no dispatch issued);
     *         {@code false} if a click was dispatched to switch the style
     */
    public boolean ensureStyle(CombatStyleIndex desired)
    {
        int current = client.getVarpValue(VARP_ATTACK_STYLE);
        if (current == desired.widgetIndex())
        {
            log.debug("combat style already correct ({})", desired);
            return true;
        }

        long now = System.currentTimeMillis();
        if (now - lastDispatchMs < THROTTLE_MS)
        {
            log.debug("combat style switch throttled ({}ms since last dispatch)",
                now - lastDispatchMs);
            return true;   // treat as "already handled" — caller should not re-dispatch
        }

        int widgetId = STYLE_WIDGET_IDS[desired.widgetIndex()];
        ActionRequest req = ActionRequest.builder()
            .kind(ActionRequest.Kind.CLICK_WIDGET)
            .channel(ActionRequest.Channel.MOUSE)
            .widgetId(widgetId)
            .build();
        log.info("switching combat style {} → {} (widgetId=0x{})",
            current, desired, Integer.toHexString(widgetId));
        dispatcher.dispatch(req);
        lastDispatchMs = now;
        return false;
    }
}
