package net.runelite.client.plugins.recorder.debug;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Stroke;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.plugins.recorder.RecorderConfig;
import net.runelite.client.plugins.recorder.transport.VerbMatcher;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/** Debug overlay for the Grand Exchange main interface. Paints translucent
 *  status fills over the 8 offer slot widgets and outlines the
 *  "Collect to inventory" toolbar button so the developer can see where
 *  the bot's CLICK_WIDGET dispatches will land.
 *
 *  <p>Colour key for slots (matches per-slot {@link GrandExchangeOfferState}):
 *  <ul>
 *    <li>EMPTY: translucent gray.</li>
 *    <li>BUYING / SELLING (in-progress): yellow.</li>
 *    <li>BOUGHT (completed buy): green.</li>
 *    <li>SOLD (completed sell): red.</li>
 *    <li>CANCELLED: muted gray (treated like empty).</li>
 *  </ul>
 *
 *  <p>Master toggle: {@link RecorderConfig#geCoreOverlay()}. Persisted via
 *  RuneLite's config system (survives restarts). Off by default. */
public final class GeDebugOverlay extends Overlay
{
    // Translucent fills (alpha 80) — enough to read the slot through.
    private static final Color FILL_EMPTY      = new Color(180, 180, 180,  60);
    private static final Color FILL_INPROGRESS = new Color(240, 220,  50,  80);
    private static final Color FILL_BOUGHT     = new Color( 40, 220,  80,  90);
    private static final Color FILL_SOLD       = new Color(230,  60,  60,  90);
    private static final Color FILL_CANCELLED  = new Color(120, 120, 120,  60);

    private static final Color COLLECT_OUTLINE = new Color(50, 200, 255, 230);
    private static final Color COLLECT_LABEL   = new Color(20, 30, 40, 200);
    private static final Color COLLECT_TEXT    = new Color(220, 240, 255, 255);

    private static final Stroke STROKE_SLOT    = new BasicStroke(1.5f);
    private static final Stroke STROKE_COLLECT = new BasicStroke(2.0f);

    private static final int[] SLOT_WIDGET_IDS = new int[] {
        InterfaceID.GeOffers.INDEX_0,
        InterfaceID.GeOffers.INDEX_1,
        InterfaceID.GeOffers.INDEX_2,
        InterfaceID.GeOffers.INDEX_3,
        InterfaceID.GeOffers.INDEX_4,
        InterfaceID.GeOffers.INDEX_5,
        InterfaceID.GeOffers.INDEX_6,
        InterfaceID.GeOffers.INDEX_7,
    };

    private final Client client;
    private final RecorderConfig config;

    public GeDebugOverlay(Client client, RecorderConfig config)
    {
        this.client = client;
        this.config = config;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
        setPriority(Overlay.PRIORITY_LOW);
    }

    @Override
    public Dimension render(Graphics2D g)
    {
        if (config == null || !config.geCoreOverlay()) return null;
        // Cheap visibility gate — UNIVERSE hidden means GE interface isn't up.
        Widget root = client.getWidget(InterfaceID.GeOffers.UNIVERSE);
        if (root == null || root.isHidden()) return null;

        GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
        paintSlots(g, offers);
        paintCollectButton(g);
        paintSetupButtons(g);
        return null;
    }

    /** When the per-slot offer setup is open, paint every visible setup
     *  descendant whose default action matches one of the stepping verbs
     *  the bot targets ({@code +1 / +10 / +100 / +1000 / -1 / Enter quantity
     *  / Enter price / Confirm}). Walks dynamic/static/nested children
     *  recursively — same shape as {@code GeInteraction.resolveSetupButtons}
     *  — so the orange outlines line up with the EXACT widgets the bot's
     *  button-stepping plan would target. */
    private void paintSetupButtons(Graphics2D g)
    {
        Widget setup = client.getWidget(InterfaceID.GeOffers.SETUP);
        if (setup == null || setup.isHidden()) return;
        g.setStroke(STROKE_SLOT);
        java.util.Deque<Widget> stack = new java.util.ArrayDeque<>();
        stack.push(setup);
        // Stop iterating once we've painted every interesting verb at most
        // once (mirrors the bot's "out.containsKey" short-circuit).
        java.util.Set<String> painted = new java.util.HashSet<>();
        while (!stack.isEmpty())
        {
            Widget w = stack.pop();
            if (w == null || w.isHidden()) continue;
            String matched = matchedSetupVerb(w);
            if (matched != null && !painted.contains(matched))
            {
                Rectangle b = w.getBounds();
                if (b != null && !b.isEmpty())
                {
                    painted.add(matched);
                    g.setColor(BUTTON_OUTLINE);
                    g.drawRect(b.x, b.y, b.width - 1, b.height - 1);
                    String label = matched + " (" + b.width + "x" + b.height + ")";
                    int lw = g.getFontMetrics().stringWidth(label) + 6;
                    int lh = g.getFontMetrics().getHeight();
                    int ly = b.y - lh - 1;
                    if (ly < 0) ly = b.y + b.height + 1;
                    g.setColor(BUTTON_LABEL_BG);
                    g.fillRect(b.x, ly, lw, lh);
                    g.setColor(COLLECT_TEXT);
                    g.drawString(label, b.x + 3, ly + g.getFontMetrics().getAscent());
                }
            }
            pushAll(stack, w.getDynamicChildren());
            pushAll(stack, w.getStaticChildren());
            pushAll(stack, w.getNestedChildren());
        }
    }

    private static final Color BUTTON_OUTLINE = new Color(255, 200, 80, 230);
    private static final Color BUTTON_LABEL_BG = new Color(20, 30, 40, 200);

    /** Returns the matched setup verb (one of "+1", "+10", "+100", "+1000",
     *  "-1", "Enter quantity", "Enter price", "Confirm") if {@code w}'s
     *  actions contain one, else null.
     *
     *  <p>Matching goes through {@link VerbMatcher#normalise} so the result
     *  agrees with what {@code GeInteraction.resolveSetupButtons} sees —
     *  RuneLite colour-tagged actions like {@code "<col=ffff00>+1</col>"}
     *  must strip to {@code "+1"} for the overlay to mark the same widget
     *  the bot's button-stepping plan would target. */
    private static String matchedSetupVerb(Widget w)
    {
        String[] actions = w.getActions();
        if (actions == null) return null;
        for (String a : actions)
        {
            if (a == null || a.isEmpty()) continue;
            String an = VerbMatcher.normalise(a);
            if (an.equals("+1") || an.equals("+10") || an.equals("+100")
                || an.equals("+1000") || an.equals("-1")) return an;
            if (an.contains("enter-quantity")) return "Enter quantity";
            if (an.contains("enter-price"))    return "Enter price";
            if (an.contains("confirm"))        return "Confirm";
        }
        return null;
    }

    private static void pushAll(java.util.Deque<Widget> stack, Widget[] arr)
    {
        if (arr == null) return;
        for (Widget c : arr) if (c != null) stack.push(c);
    }

    private void paintSlots(Graphics2D g, GrandExchangeOffer[] offers)
    {
        g.setStroke(STROKE_SLOT);
        for (int i = 0; i < SLOT_WIDGET_IDS.length; i++)
        {
            Widget slot = client.getWidget(SLOT_WIDGET_IDS[i]);
            if (slot == null || slot.isHidden()) continue;
            Rectangle b = slot.getBounds();
            if (b == null || b.isEmpty()) continue;

            GrandExchangeOfferState state = (offers != null && i < offers.length && offers[i] != null)
                ? offers[i].getState() : GrandExchangeOfferState.EMPTY;
            Color fill = fillFor(offers, i, state);
            g.setColor(fill);
            g.fillRect(b.x, b.y, b.width, b.height);
            g.setColor(new Color(fill.getRed(), fill.getGreen(), fill.getBlue(), 200));
            g.drawRect(b.x, b.y, b.width - 1, b.height - 1);
        }
    }

    /** Map an offer slot's state + fill progress to a visible colour.
     *  Distinguishes in-progress (yellow) from completed (green/red) — the
     *  RuneLite enum's BUYING/BOUGHT split already covers this for buys, but
     *  we also treat partial fills as in-progress so the user can spot a
     *  buy-limit stall while it's still happening. */
    private static Color fillFor(GrandExchangeOffer[] offers, int slot, GrandExchangeOfferState state)
    {
        switch (state)
        {
            case EMPTY:           return FILL_EMPTY;
            case CANCELLED_BUY:
            case CANCELLED_SELL:  return FILL_CANCELLED;
            case BOUGHT:          return FILL_BOUGHT;
            case SOLD:            return FILL_SOLD;
            case BUYING:
            case SELLING:
            default:
                // Partial-complete still BUYING/SELLING per the engine — show
                // yellow whether fill is 0 % or 99 %.
                return FILL_INPROGRESS;
        }
    }

    private void paintCollectButton(Graphics2D g)
    {
        Widget parent = client.getWidget(InterfaceID.GeOffers.COLLECTALL);
        if (parent == null || parent.isHidden()) return;
        Rectangle parentBounds = parent.getBounds();
        if (parentBounds == null || parentBounds.isEmpty()) return;

        // Paint the parent widget bounds in a muted color first — this is
        // what `client.getWidget(COLLECTALL).getBounds()` returns. Helps
        // see when the engine is reporting a "toolbar wrapper" instead of
        // just the button.
        g.setStroke(STROKE_COLLECT);
        g.setColor(new Color(120, 120, 200, 120));
        g.drawRect(parentBounds.x, parentBounds.y, parentBounds.width - 1, parentBounds.height - 1);

        // Walk for the actual clickable button child (one whose default
        // action is "Collect to inventory" or "Collect to bank") — that's
        // what a CLICK_WIDGET targeting COLLECTALL SHOULD land on.
        Widget actualButton = findCollectActionWidget(parent);
        Rectangle actualB = actualButton == null ? null : actualButton.getBounds();
        boolean foundChild = actualB != null && !actualB.isEmpty() && actualButton != parent;

        if (foundChild)
        {
            g.setColor(COLLECT_OUTLINE);
            g.drawRect(actualB.x, actualB.y, actualB.width - 1, actualB.height - 1);
        }

        String label = foundChild
            ? "COLLECT button (" + actualB.width + "x" + actualB.height
                + " inside " + parentBounds.width + "x" + parentBounds.height + " parent)"
            : "COLLECTALL (" + parentBounds.width + "x" + parentBounds.height + " — no child found, using parent)";
        int labelW = g.getFontMetrics().stringWidth(label) + 8;
        int labelH = g.getFontMetrics().getHeight();
        int lx = parentBounds.x;
        int ly = parentBounds.y + parentBounds.height + 2;
        g.setColor(COLLECT_LABEL);
        g.fillRect(lx, ly, labelW, labelH);
        g.setColor(COLLECT_TEXT);
        g.drawString(label, lx + 4, ly + g.getFontMetrics().getAscent());
    }

    /** Walks the widget subtree rooted at {@code parent} for the first
     *  visible widget whose default action is {@code Collect to inventory}
     *  or {@code Collect to bank}. Returns {@code parent} if {@code parent}
     *  itself carries one of those actions and no narrower child matches.
     *  Returns {@code null} if neither parent nor any descendant carries
     *  a Collect action. */
    private static Widget findCollectActionWidget(Widget parent)
    {
        Widget best = hasCollectAction(parent) ? parent : null;
        Widget narrow = walkForCollectAction(parent.getDynamicChildren());
        if (narrow != null) return narrow;
        narrow = walkForCollectAction(parent.getStaticChildren());
        if (narrow != null) return narrow;
        narrow = walkForCollectAction(parent.getNestedChildren());
        if (narrow != null) return narrow;
        return best;
    }

    private static Widget walkForCollectAction(Widget[] kids)
    {
        if (kids == null) return null;
        for (Widget k : kids)
        {
            if (k == null || k.isHidden()) continue;
            if (hasCollectAction(k)) return k;
            Widget deeper = findCollectActionWidget(k);
            if (deeper != null && deeper != k && hasCollectAction(deeper)) return deeper;
        }
        return null;
    }

    private static boolean hasCollectAction(Widget w)
    {
        String[] actions = w.getActions();
        if (actions == null) return false;
        for (String a : actions)
        {
            if (a == null) continue;
            // Normalised match (strips colour tags, lowercases, replaces
            // whitespace with hyphens) so engine-coloured action text like
            // "<col=ff9040>Collect to inventory</col>" still matches.
            String an = VerbMatcher.normalise(a);
            if (an.contains("collect-to-inventory") || an.contains("collect-to-bank")) return true;
        }
        return false;
    }
}
