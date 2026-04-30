package net.runelite.client.plugins.recorder.combat;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.EnumID;
import net.runelite.api.ParamID;
import net.runelite.api.Skill;
import net.runelite.api.StructComposition;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
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

    /** Minimum milliseconds between two successive click dispatches.
     *  Long enough that a single missed dispatch (e.g. dispatcher busy
     *  with an attack click) doesn't queue up a second toggle a tick
     *  later — at the old 2s value the loop ate dropped clicks every
     *  pass while combat owned the dispatcher. */
    private static final long THROTTLE_MS = 8_000L;

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

        // Skip if the shared dispatcher is mid-click (combat attack,
        // walker hop, gate open, etc.). Without this, the click is
        // silently dropped AND we burn the next 8s of throttle.
        if (dispatcher.isBusy())
        {
            log.debug("combat style switch skipped — dispatcher busy");
            return true;
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

    /**
     * Weapon-type-aware version of {@link #ensureStyle(CombatStyleIndex)}.
     * Resolves the correct slot for {@code skill} from the equipped weapon's
     * style table, then dispatches a click if the slot doesn't match.
     */
    public boolean ensureStyleForSkill(Skill skill)
    {
        int slot = resolveSlotForSkill(skill);
        if (slot < 0)
        {
            log.debug("ensureStyleForSkill({}): could not resolve slot", skill);
            return true;
        }
        int current = client.getVarpValue(VARP_ATTACK_STYLE);
        if (current == slot)
        {
            log.debug("combat style already correct ({} → slot {})", skill, slot);
            return true;
        }
        long now = System.currentTimeMillis();
        if (now - lastDispatchMs < THROTTLE_MS)
        {
            log.debug("combat style switch throttled ({}ms since last dispatch)",
                now - lastDispatchMs);
            return true;
        }
        if (dispatcher.isBusy())
        {
            log.debug("combat style switch skipped — dispatcher busy");
            return true;
        }
        int widgetId = STYLE_WIDGET_IDS[slot];
        ActionRequest req = ActionRequest.builder()
            .kind(ActionRequest.Kind.CLICK_WIDGET)
            .channel(ActionRequest.Channel.MOUSE)
            .widgetId(widgetId)
            .build();
        log.info("switching combat style {} → {} slot {} (widgetId=0x{})",
            current, skill, slot, Integer.toHexString(widgetId));
        dispatcher.dispatch(req);
        lastDispatchMs = now;
        return false;
    }

    /**
     * Returns the widget slot index (0–3) that primarily trains {@code skill}
     * for the currently equipped weapon, or {@code -1} if the lookup fails.
     * Falls back to the hardcoded {@link CombatStyleIndex} map on failure.
     * Must be called on the client thread.
     */
    int resolveSlotForSkill(Skill skill)
    {
        int weaponType = client.getVarbitValue(VarbitID.COMBAT_WEAPON_CATEGORY);
        int weaponStyleEnumId = client.getEnum(EnumID.WEAPON_STYLES).getIntValue(weaponType);
        if (weaponStyleEnumId != -1)
        {
            int[] structs = client.getEnum(weaponStyleEnumId).getIntVals();
            for (int i = 0; i < structs.length; i++)
            {
                if (structs[i] == 0) continue;
                StructComposition sc = client.getStructComposition(structs[i]);
                String name = sc.getStringValue(ParamID.ATTACK_STYLE_NAME);
                if (styleNameTrainsSkill(name, skill))
                {
                    return i;
                }
            }
        }
        return CombatStyleIndex.forSkill(skill)
            .map(CombatStyleIndex::widgetIndex)
            .orElse(-1);
    }

    private static boolean styleNameTrainsSkill(String styleName, Skill skill)
    {
        return switch (skill)
        {
            case ATTACK    -> "Accurate".equals(styleName) || "Punch".equals(styleName);
            case STRENGTH  -> "Aggressive".equals(styleName) || "Kick".equals(styleName);
            case DEFENCE   -> "Defensive".equals(styleName) || "Block".equals(styleName);
            default        -> false;
        };
    }
}
