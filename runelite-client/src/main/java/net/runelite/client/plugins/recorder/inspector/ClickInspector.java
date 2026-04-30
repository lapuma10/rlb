package net.runelite.client.plugins.recorder.inspector;

import java.awt.Rectangle;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Singleton;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.VarClientIntChanged;
import net.runelite.api.events.VarClientStrChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.widgets.Widget;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;

/**
 * Logs every UI click plus the varbit / varc state changes it triggers,
 * so a flow's signals can be catalogued by playing it through once.
 *
 * <p>Output: {@code [click-inspector]}-tagged lines on the regular client
 * log. One click line per accepted {@link MenuOptionClicked}, one var
 * line per varbit / varplayer / varcInt / varcStr change inside the
 * current click's window.
 *
 * <p>Window: 3 ticks after each click. Each var change inside the window
 * re-arms it to {@code currentTick + 3} (so a chained flow — Withdraw-X
 * → chatbox open → user types → server commits → inventory tick — gets
 * the whole cascade logged). Hard cap is 30 ticks past the click so a
 * runaway re-arm caused by unrelated gameplay churn can't keep the log
 * open forever.
 *
 * <p>Toggle on / off via {@link #setEnabled}. The inspector registers
 * itself on the EventBus only while enabled.
 *
 * <p>See {@code docs/superpowers/specs/2026-04-30-click-inspector-design.md}
 * for the full spec.
 */
@Slf4j
@Singleton
public final class ClickInspector {

    private static final int  WINDOW_TICKS   = 3;
    private static final int  HARD_CAP_TICKS = 30;
    private static final String TAG = "[click-inspector]";

    /** Menu actions that aren't useful for cataloguing UI signals —
     *  walking, examining, dismissing menus. Anything else (widget
     *  clicks, item ops, NPC / object ops) is logged. */
    private static final EnumSet<MenuAction> SKIPPED_ACTIONS = EnumSet.of(
        MenuAction.WALK,
        MenuAction.CANCEL,
        MenuAction.EXAMINE_NPC,
        MenuAction.EXAMINE_OBJECT,
        MenuAction.EXAMINE_ITEM,
        MenuAction.EXAMINE_ITEM_GROUND
    );

    /** Var names that fire constantly during gameplay (clocks, regen
     *  tickers, tooltips, camera) and aren't useful for cataloguing UI
     *  signals. Matching changes are dropped — neither logged nor
     *  allowed to re-arm the post-click window — so each click's burst
     *  is bounded by genuine state changes only. Extend as new noise
     *  surfaces in the log. */
    private static final java.util.Set<String> NOISE_EXACT = java.util.Set.of(
        "DODGER_TRACKER",
        "LAST_REGEN_RATE",
        "LAST_SPEC_REGEN_SYNCH",
        "BUSY",
        "OBJDIALOG_UPDATEDELAY",
        "POLL_ID",
        "BUFF_BAR_DODGER_UPDATE",
        "TOPLEVEL_MAINMODAL_BG_TRANS",
        "TOPLEVEL_MAINMODAL_BG_COLOUR"
    );
    private static final String[] NOISE_PREFIXES = {
        "DATE_",          // DATE_MILLISECONDS_PAST_MINUTE / DATE_SECONDS_PAST_MINUTE
        "TOOLTIP_",       // TOOLTIP_BUILT / TOOLTIP_TIME
        "SBH_",           // stat-bar HUD restore-down ticker
        "CAMERA_ZOOM_",   // CAMERA_ZOOM_BIG / CAMERA_ZOOM_SMALL
        "CHAT_LAST"       // CHAT_LASTSCROLLPOS / CHAT_LASTSCROLLSIZE / CHAT_LASTREBUILD
    };
    // Varplayer changes are dropped wholesale right now — the resolver has
    // no VarPlayerID scan, so every varp would print as varp#NNN with no
    // way to tell signal from background-tick noise. If you need a
    // specific varp later, either add VarPlayerID to IdConstantResolver
    // (5 LOC) or open the dropped branch in onVarbitChanged and allowlist
    // by raw id.

    private static boolean isNoise(String name) {
        if (NOISE_EXACT.contains(name)) return true;
        for (String p : NOISE_PREFIXES) if (name.startsWith(p)) return true;
        return false;
    }

    private final Client client;
    private final EventBus eventBus;
    private final IdConstantResolver resolver;

    private volatile boolean enabled;
    private int windowEndTick = -1;
    private int hardCapTick   = -1;

    // Prev-value caches. Var-change events carry the new value (varbit)
    // or only the id (varc int/str), never the previous value — we
    // track it ourselves so the log line can show {prev}->{new}. Cache
    // is updated unconditionally on every event so a click that fires
    // *after* a var change still shows a meaningful prev on the next.
    //
    // Asymmetry: int-typed caches use {@code Integer prev = put(...)} +
    // null-on-first-put — first-sight prints "?" then real values. The
    // varcStr cache uses {@code containsKey} explicitly because a
    // genuinely-null prev value (varc unset) is meaningful and must not
    // be mistaken for first-sight.
    //
    // Memory: these maps grow as ids are observed. Unbounded over a long
    // session (worst case a few thousand entries — int+Integer or
    // int+String). Fine for a debug toggle; cleared on toggle-off path
    // would be wrong (we'd lose prevs for the next session).
    private final Map<Integer, Integer> prevVarp     = new HashMap<>();
    private final Map<Integer, Integer> prevVarbit   = new HashMap<>();
    private final Map<Integer, Integer> prevVarcInt  = new HashMap<>();
    private final Map<Integer, String>  prevVarcStr  = new HashMap<>();

    @Inject
    public ClickInspector(Client client, EventBus eventBus) {
        this.client = client;
        this.eventBus = eventBus;
        this.resolver = new IdConstantResolver();
    }

    /** Idempotent toggle. Registers / unregisters the EventBus listener.
     *  Called from the EDT (Swing checkbox listener); event callbacks fire
     *  on the client thread. The {@code volatile boolean enabled} guard
     *  inside each subscriber covers the brief window between
     *  {@code register(this)} returning and {@code enabled=true} —
     *  in-flight events during that window are dropped, which is fine
     *  for a debug toggle (you may miss the very first event of a
     *  session). */
    public void setEnabled(boolean on) {
        if (on == enabled) return;
        if (on) {
            eventBus.register(this);
            enabled = true;
            log.info("{} enabled", TAG);
        } else {
            eventBus.unregister(this);
            enabled = false;
            windowEndTick = -1;
            hardCapTick = -1;
            log.info("{} disabled", TAG);
        }
    }

    public boolean isEnabled() { return enabled; }

    @Subscribe
    public void onMenuOptionClicked(MenuOptionClicked e) {
        if (!enabled) return;
        MenuAction action = e.getMenuAction();
        if (action != null && SKIPPED_ACTIONS.contains(action)) return;
        int tick = client.getTickCount();
        windowEndTick = tick + WINDOW_TICKS;
        hardCapTick   = tick + HARD_CAP_TICKS;
        log.info("{} tick={} click {}", TAG, tick, formatClick(e));
    }

    @Subscribe
    public void onVarbitChanged(VarbitChanged e) {
        if (!enabled) return;
        int tick = client.getTickCount();
        int varpId   = e.getVarpId();
        int varbitId = e.getVarbitId();
        int value    = e.getValue();
        if (varbitId == -1) {
            // Varplayer change. The resolver has no VarPlayerID scan, so
            // every varp comes back unnamed — drop them all by default.
            // To re-enable a specific varp, add VarPlayerID to the resolver
            // (5 LOC) or allowlist by raw id here.
            prevVarp.put(varpId, value);
        } else {
            Integer prev = prevVarbit.put(varbitId, value);
            String name = resolver.varbit(varbitId);
            if (name.startsWith("varbit#")) return;   // unnamed → noise
            if (isNoise(name)) return;                 // named noise list
            if (tick <= windowEndTick) {
                log.info("{} tick={} varbit {} {}->{}", TAG, tick,
                    name, formatPrevInt(prev), value);
                rearm(tick);
            }
        }
    }

    @Subscribe
    public void onVarClientIntChanged(VarClientIntChanged e) {
        if (!enabled) return;
        int tick = client.getTickCount();
        int id    = e.getIndex();
        int value = client.getVarcIntValue(id);
        Integer prev = prevVarcInt.put(id, value);
        String name = resolver.varc(id);
        if (name.startsWith("varc#")) return;   // unnamed → noise
        if (isNoise(name)) return;               // named noise list
        if (tick <= windowEndTick) {
            log.info("{} tick={} varcInt {} {}->{}", TAG, tick,
                name, formatPrevInt(prev), value);
            rearm(tick);
        }
    }

    @Subscribe
    public void onVarClientStrChanged(VarClientStrChanged e) {
        if (!enabled) return;
        int tick = client.getTickCount();
        int id    = e.getIndex();
        String value = client.getVarcStrValue(id);
        // putIfAbsent semantics differ for null values; use containsKey
        // so we distinguish "first time seen" from "previously seen as null".
        boolean firstSight = !prevVarcStr.containsKey(id);
        String prev = prevVarcStr.put(id, value);
        String name = resolver.varc(id);
        if (name.startsWith("varc#")) return;   // unnamed → noise
        if (isNoise(name)) return;               // named noise list
        if (tick <= windowEndTick) {
            log.info("{} tick={} varcStr {} {}->{}", TAG, tick,
                name,
                firstSight ? "?" : formatStr(prev),
                formatStr(value));
            rearm(tick);
        }
    }

    private void rearm(int tick) {
        int newEnd = tick + WINDOW_TICKS;
        if (newEnd > hardCapTick) newEnd = hardCapTick;
        if (newEnd > windowEndTick) windowEndTick = newEnd;
    }

    /** Format mirrors RuneLite's built-in {@code WidgetInspector} so the
     *  same field labels work across both tools (devtools' Widget Inspector
     *  picker + dump for browsing, this for chronological click capture).
     *  Per click we emit: {@code verb / target / id / grp / chld / idx /
     *  named / bounds / text / actions / item / action} — empties dropped. */
    private String formatClick(MenuOptionClicked e) {
        StringBuilder sb = new StringBuilder();
        sb.append("verb='").append(safe(e.getMenuOption())).append("'");
        String target = e.getMenuTarget();
        if (target != null && !target.isEmpty()) {
            sb.append(" target='").append(target).append("'");
        }
        Widget w = e.getWidget();
        if (w != null) {
            int packed = w.getId();
            int grp  = (packed >>> 16) & 0xffff;
            int chld = packed & 0xffff;
            int idx  = w.getIndex();
            sb.append(String.format(" id=0x%04x_%04x grp=%d chld=%d", grp, chld, grp, chld));
            if (idx >= 0) sb.append(" idx=").append(idx);
            sb.append(" named=").append(resolver.widget(packed));
            Rectangle bounds = w.getBounds();
            if (bounds != null) {
                sb.append(" bounds=[").append(bounds.x).append(",").append(bounds.y)
                  .append(" ").append(bounds.width).append("x").append(bounds.height).append("]");
            }
            String text = w.getText();
            if (text != null && !text.isEmpty()) {
                sb.append(" text='").append(text).append("'");
            }
            String[] actions = w.getActions();
            if (actions != null) {
                StringBuilder ab = new StringBuilder();
                for (String a : actions) {
                    if (a == null || a.isEmpty()) continue;
                    if (ab.length() > 0) ab.append(",");
                    ab.append(a);
                }
                if (ab.length() > 0) sb.append(" actions=[").append(ab).append("]");
            }
            int itemId = w.getItemId();
            if (itemId > 0) sb.append(" item=").append(itemId);
        } else {
            int packed = e.getWidgetId();
            if (packed > 0) {
                int grp  = (packed >>> 16) & 0xffff;
                int chld = packed & 0xffff;
                sb.append(String.format(" id=0x%04x_%04x grp=%d chld=%d", grp, chld, grp, chld));
                int childIdx = e.getParam0();
                if (childIdx >= 0) sb.append(" idx=").append(childIdx);
                sb.append(" named=").append(resolver.widget(packed));
            }
        }
        if (e.isItemOp() && e.getItemId() > 0) {
            sb.append(" itemOp=").append(e.getItemOp())
              .append(" itemId=").append(e.getItemId());
        }
        int id = e.getId();
        if (id > 0) sb.append(" entryId=").append(id);
        MenuAction action = e.getMenuAction();
        if (action != null) sb.append(" action=").append(action.name());
        return sb.toString();
    }

    private static String formatStr(String s) {
        return s == null ? "null" : "'" + s + "'";
    }

    private static String formatPrevInt(Integer prev) {
        return prev == null ? "?" : prev.toString();
    }

    private static String safe(String s) { return s == null ? "" : s; }
}
