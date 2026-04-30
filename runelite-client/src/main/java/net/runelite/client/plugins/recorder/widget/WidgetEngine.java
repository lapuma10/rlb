package net.runelite.client.plugins.recorder.widget;

import java.awt.Rectangle;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.WorldView;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.plugins.recorder.transport.VerbMatcher;
import net.runelite.client.sequence.dispatch.HumanizedInputDispatcher;
import net.runelite.client.sequence.internal.ActionRequest;

/**
 * Higher-level widget interaction surface for scripts driving GE / bank /
 * dialogs / sidebar tabs. Sits on top of {@link HumanizedInputDispatcher}
 * and adds search-by-verb, search-by-itemId, and child-index navigation —
 * the primitives that the recipe JSONs (see
 * {@code src/main/resources/sequence/activities/ge/buy-flow-recipe.json})
 * describe.
 *
 * <p>Why this exists: {@link WidgetActions} clicks a widget by packed id
 * only. Many real targets are dynamic children that share the parent's
 * packed id — they're addressed by index or by their {@code getActions()}
 * verb, not by a unique id. This engine wraps that lookup pattern.
 *
 * <p>Every click does, in order:
 * <ol>
 *   <li>Resolve the target widget on the client thread.</li>
 *   <li>Walk {@code isHidden()} up the parent chain — clicking a hidden
 *       widget falls through to whatever's behind, drifting the player.</li>
 *   <li>Skip if the dispatcher is busy. Caller retries next tick.</li>
 *   <li>Dispatch via {@code CLICK_WIDGET} (named widget) or
 *       {@code CLICK_BOUNDS} (resolved child rectangle).</li>
 * </ol>
 *
 * <p>Returns {@code true} only when a click was actually queued.
 * {@code false} = widget not found, hidden, or dispatcher busy.
 *
 * <p>Threading: probes hop to the client thread; click dispatch is
 * fire-and-forget. Safe from any worker thread.
 */
@Slf4j
public final class WidgetEngine {

    private final Client client;
    private final ClientThread clientThread;
    private final HumanizedInputDispatcher dispatcher;

    public WidgetEngine(Client client,
                        ClientThread clientThread,
                        HumanizedInputDispatcher dispatcher) {
        this.client = client;
        this.clientThread = clientThread;
        this.dispatcher = dispatcher;
    }

    /** Click a widget by its packed id ({@code group<<16 | child}). Use
     *  this when you have a named constant from {@code InterfaceID} — e.g.
     *  {@code clickByPacked(InterfaceID.GeOffers.SETUP_CONFIRM)}. */
    public boolean clickByPacked(int packedId) {
        if (dispatcher.isBusy()) return false;
        if (!isVisible(packedId)) return false;
        ActionRequest req = ActionRequest.builder()
            .kind(ActionRequest.Kind.CLICK_WIDGET)
            .channel(ActionRequest.Channel.MOUSE)
            .widgetId(packedId)
            .build();
        dispatcher.dispatch(req);
        return true;
    }

    /** Click the {@code childIdx}'th child of {@code parentPacked}.
     *  Searches the parent's child arrays in order: dynamic → static →
     *  nested, picking whichever array's bounds the index falls into.
     *  Equivalent to OSBot's three-arg {@code Widgets.get(group, child,
     *  subChild)} which has the same multi-array semantics.
     *
     *  <p>Use when the recipe JSON gives an explicit {@code child_index}
     *  known stable across OSRS revisions. Prefer {@link #clickByVerb}
     *  for new flows — verbs survive child reordering, indices don't. */
    public boolean clickChild(int parentPacked, int childIdx) {
        if (dispatcher.isBusy()) return false;
        Widget child = onClient(() -> {
            Widget parent = client.getWidget(parentPacked);
            if (parent == null) return null;
            Widget[] dyn = parent.getDynamicChildren();
            if (dyn != null && childIdx >= 0 && childIdx < dyn.length) return dyn[childIdx];
            Widget[] sta = parent.getStaticChildren();
            if (sta != null && childIdx >= 0 && childIdx < sta.length) return sta[childIdx];
            Widget[] nes = parent.getNestedChildren();
            if (nes != null && childIdx >= 0 && childIdx < nes.length) return nes[childIdx];
            return null;
        });
        return clickWidgetBounds(child);
    }

    /** Find a child widget under {@code parentPacked} whose
     *  {@code getActions()} contains {@code verb}, then click it. Resilient
     *  to OSRS reordering child indices because the verb is the stable
     *  identifier — preferred over {@link #clickChild} for new flows.
     *
     *  <p>Match is performed by {@link VerbMatcher} — it strips RuneLite
     *  colour tags ({@code <col=ff9040>...</col>}), normalises whitespace
     *  and hyphens, and is case-insensitive. So
     *  {@code clickByVerb(parent, "Create Buy offer")} matches an action
     *  string {@code "Create <col=ff9040>Buy</col> offer"}, and
     *  {@code clickByVerb(parent, "+10")} matches {@code "+10"} verbatim. */
    public boolean clickByVerb(int parentPacked, String verb) {
        return clickFirstMatching(parentPacked, w -> hasAction(w, verb));
    }

    /** Find a child widget under {@code parentPacked} whose
     *  {@code getItemId()} equals {@code itemId}, then click it. For
     *  inventory / bank / GE-details slots — the slot index isn't stable
     *  but the item id is. */
    public boolean clickByItemId(int parentPacked, int itemId) {
        return clickFirstMatching(parentPacked, w -> w.getItemId() == itemId);
    }

    /** General-purpose: find the first child of {@code parentPacked}
     *  matching the predicate and click it. Lower-level escape hatch for
     *  callers that need a predicate beyond verb / itemId. The matcher
     *  runs on the client thread so it can read any {@link Widget}
     *  property safely. */
    public boolean clickFirstMatching(int parentPacked, Predicate<Widget> matcher) {
        if (dispatcher.isBusy()) return false;
        Widget hit = onClient(() -> findInParent(parentPacked, matcher));
        return clickWidgetBounds(hit);
    }

    /** Find (without clicking) the first VISIBLE child of {@code parentPacked}
     *  matching the predicate. Hidden subtrees are skipped — if you need
     *  to introspect "present but hidden", read the parent yourself and
     *  walk children manually.
     *
     *  <p>The returned {@link Widget} is a LIVE reference — calling any
     *  accessor on it from a worker thread will trip RuneLite's client-
     *  thread assertion. Either operate on it inside a
     *  {@link ClientThread#invokeLater} block, or restructure the call
     *  to use {@link #clickByVerb} / {@link #clickByItemId} which read +
     *  click on-thread. */
    public Optional<Widget> find(int parentPacked, Predicate<Widget> matcher) {
        return Optional.ofNullable(onClient(() -> findInParent(parentPacked, matcher)));
    }

    /** Right-click an NPC by its type id (e.g. 2464 for the GE Clerk) and
     *  pick the named menu verb (e.g. {@code "Exchange"}). The dispatcher
     *  does the hover-default check + right-click + menu match. The menu
     *  verb is the displayed text from the recipe's {@code click.verb}
     *  field, NOT the {@link net.runelite.api.MenuAction} enum constant
     *  (the enum is for menu-position routing only). */
    public boolean clickNpc(int npcTypeId, String verb) {
        if (dispatcher.isBusy()) return false;
        NPC npc = onClient(() -> findNpcByTypeId(npcTypeId));
        if (npc == null) return false;
        ActionRequest req = ActionRequest.builder()
            .kind(ActionRequest.Kind.CLICK_NPC)
            .channel(ActionRequest.Channel.MOUSE)
            .npcIndex(npc.getIndex())
            .verb(verb)
            .build();
        dispatcher.dispatch(req);
        return true;
    }

    /** {@code true} iff {@code packedId} resolves to a widget AND every
     *  ancestor up to the root reports {@code !isHidden()}. The OSRS
     *  engine only renders a widget when the entire chain is visible —
     *  checking just the leaf misses tabs that are collapsed at the
     *  sidebar level. */
    public boolean isVisible(int packedId) {
        return Boolean.TRUE.equals(onClient(
            () -> isChainVisible(client.getWidget(packedId))));
    }

    // ───────── internals ─────────

    private boolean clickWidgetBounds(Widget w) {
        if (w == null) return false;
        Rectangle bounds = onClient(() -> {
            if (!isChainVisible(w)) return null;
            Rectangle b = w.getBounds();
            return (b == null || b.isEmpty()) ? null : b;
        });
        if (bounds == null) return false;
        ActionRequest req = ActionRequest.builder()
            .kind(ActionRequest.Kind.CLICK_BOUNDS)
            .channel(ActionRequest.Channel.MOUSE)
            .bounds(bounds)
            .build();
        dispatcher.dispatch(req);
        return true;
    }

    /** Recursively scan the parent's children for a predicate match. Order:
     *  parent → dynamic → static → nested. First match wins. Skips hidden
     *  branches (a hidden parent can't have a clickable visible child).
     *  Must run on the client thread. */
    private Widget findInParent(int parentPacked, Predicate<Widget> matcher) {
        Widget parent = client.getWidget(parentPacked);
        if (parent == null) return null;
        return scanWidget(parent, matcher);
    }

    private static Widget scanWidget(Widget w, Predicate<Widget> matcher) {
        if (w == null || w.isHidden()) return null;
        if (matcher.test(w)) return w;
        Widget hit = scanArray(w.getDynamicChildren(), matcher);
        if (hit != null) return hit;
        hit = scanArray(w.getStaticChildren(), matcher);
        if (hit != null) return hit;
        return scanArray(w.getNestedChildren(), matcher);
    }

    private static Widget scanArray(Widget[] arr, Predicate<Widget> matcher) {
        if (arr == null) return null;
        for (Widget c : arr) {
            Widget hit = scanWidget(c, matcher);
            if (hit != null) return hit;
        }
        return null;
    }

    private static boolean hasAction(Widget w, String verb) {
        String[] actions = w.getActions();
        if (actions == null) return false;
        for (String a : actions) {
            if (a == null || a.isEmpty()) continue;
            if (VerbMatcher.matches(verb, a)) return true;
        }
        return false;
    }

    private NPC findNpcByTypeId(int npcTypeId) {
        WorldView wv = client.getTopLevelWorldView();
        if (wv == null) return null;
        for (NPC n : wv.npcs()) {
            if (n != null && n.getId() == npcTypeId) return n;
        }
        return null;
    }

    private static boolean isChainVisible(Widget w) {
        if (w == null) return false;
        for (Widget cur = w; cur != null; cur = cur.getParent()) {
            if (cur.isHidden()) return false;
        }
        return true;
    }

    private <T> T onClient(Supplier<T> sup) {
        if (clientThread == null) return sup.get();
        if (client.isClientThread()) return sup.get();
        java.util.concurrent.atomic.AtomicReference<T> ref =
            new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.CountDownLatch latch =
            new java.util.concurrent.CountDownLatch(1);
        clientThread.invokeLater(() -> {
            try { ref.set(sup.get()); }
            catch (Throwable t) { log.warn("widget-engine: onClient threw", t); }
            finally { latch.countDown(); }
        });
        try {
            if (!latch.await(2000, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                log.warn("widget-engine: onClient timed out");
                return null;
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return null;
        }
        return ref.get();
    }
}
