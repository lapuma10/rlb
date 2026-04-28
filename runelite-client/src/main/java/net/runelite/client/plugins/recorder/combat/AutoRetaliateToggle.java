package net.runelite.client.plugins.recorder.combat;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.sequence.dispatch.HumanizedInputDispatcher;
import net.runelite.client.sequence.internal.ActionRequest;

/**
 * Reads the auto-retaliate VarPlayer and clicks the retaliate toggle widget
 * when its current state does not match the desired state.
 *
 * <p>VarPlayer 172 ({@code VarPlayerID.OPTION_NODEF}):
 * <ul>
 *   <li>0 = auto-retaliate ON</li>
 *   <li>1 = auto-retaliate OFF</li>
 * </ul>
 * The counter-intuitive 0 = ON, 1 = OFF encoding is an OSRS convention;
 * pressing the widget cycles the value and the sprite changes accordingly.
 *
 * <p>Widget: {@link InterfaceID.CombatInterface#RETALIATE}.
 *
 * <p>A 2-second throttle is enforced between dispatches so the engine has
 * at least one tick to commit the VarPlayer change before a re-read.
 */
@Slf4j
public class AutoRetaliateToggle
{
    /** VarPlayer 172 — 0 = ON, 1 = OFF. */
    private static final int VARP_AUTO_RETALIATE = VarPlayerID.OPTION_NODEF;

    private static final int WIDGET_RETALIATE = InterfaceID.CombatInterface.RETALIATE;

    /** Minimum milliseconds between two successive click dispatches. */
    private static final long THROTTLE_MS = 2_000L;

    private final Client client;
    private final CombatDispatcher dispatcher;

    /** Epoch-ms of the last dispatched click. 0 = never dispatched. */
    private long lastDispatchMs = 0L;

    /**
     * @param client       RuneLite client (reads VarPlayer 172)
     * @param clientThread unused directly; kept for API symmetry
     * @param dispatcher   humanized input dispatcher
     */
    public AutoRetaliateToggle(Client client,
                               @SuppressWarnings("unused") ClientThread clientThread,
                               HumanizedInputDispatcher dispatcher)
    {
        this(client, CombatDispatcher.forHumanized(dispatcher));
    }

    /** Package-private constructor for testing. */
    AutoRetaliateToggle(Client client, CombatDispatcher dispatcher)
    {
        this.client = client;
        this.dispatcher = dispatcher;
    }

    /**
     * Ensures auto-retaliate is ON (VarPlayer 172 = 0).
     * No-ops if already ON; dispatches a toggle click otherwise.
     *
     * @return {@code true} if already correct, {@code false} if a click was dispatched
     */
    public boolean ensureOn()
    {
        return ensureValue(0, "ON");
    }

    /**
     * Ensures auto-retaliate is OFF (VarPlayer 172 = 1).
     * No-ops if already OFF; dispatches a toggle click otherwise.
     *
     * @return {@code true} if already correct, {@code false} if a click was dispatched
     */
    public boolean ensureOff()
    {
        return ensureValue(1, "OFF");
    }

    private boolean ensureValue(int desired, String label)
    {
        int current = client.getVarpValue(VARP_AUTO_RETALIATE);
        if (current == desired)
        {
            log.debug("auto-retaliate already {}", label);
            return true;
        }

        long now = System.currentTimeMillis();
        if (now - lastDispatchMs < THROTTLE_MS)
        {
            log.debug("auto-retaliate toggle throttled ({}ms since last dispatch)",
                now - lastDispatchMs);
            return true;   // treat as handled — caller should not re-dispatch
        }

        ActionRequest req = ActionRequest.builder()
            .kind(ActionRequest.Kind.CLICK_WIDGET)
            .channel(ActionRequest.Channel.MOUSE)
            .widgetId(WIDGET_RETALIATE)
            .build();
        log.info("toggling auto-retaliate to {} (current varp={})", label, current);
        dispatcher.dispatch(req);
        lastDispatchMs = now;
        return false;
    }
}
