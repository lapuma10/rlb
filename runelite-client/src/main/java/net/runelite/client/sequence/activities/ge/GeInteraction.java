package net.runelite.client.sequence.activities.ge;

import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.event.KeyEvent;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.WorldView;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.NpcID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.sequence.dispatch.HumanizedInputDispatcher;
import net.runelite.client.sequence.views.OfferSide;

/**
 * Production {@link GeActions} backed by {@link HumanizedInputDispatcher}.
 *
 * <p>Phase A best-effort: widget lookups use {@link InterfaceID.GeOffers}
 * field names where pinned; dispatch falls through to {@code Escape}-based
 * dismiss / log warnings when the exact widget id is unknown. Methods are
 * fail-silent if the target widget is hidden — verification is the step's
 * job (its {@code check} polls the snapshot).
 *
 * <p>Threading: methods may be called from any worker thread. Widget reads
 * are gated on the client thread via {@link HumanizedInputDispatcher#runOnClient}.
 */
@Slf4j
public final class GeInteraction implements GeActions {

    /** GE clerk NPC ids (Varrock GE has six clerks; the lookup checks all of them). */
    private static final Set<Integer> GE_CLERK_NPC_IDS = Set.of(
        NpcID.GE_CLERK_1, NpcID.GE_CLERK_2, NpcID.GE_CLERK_3, NpcID.GE_CLERK_4
    );

    private final Client client;
    private final ClientThread clientThread;
    private final HumanizedInputDispatcher dispatcher;

    public GeInteraction(Client client, ClientThread clientThread, HumanizedInputDispatcher dispatcher) {
        this.client = client;
        this.clientThread = clientThread;
        this.dispatcher = dispatcher;
    }

    @Override
    public void openGrandExchange() {
        // Find a nearby GE clerk and right-click → "Exchange".
        try {
            NPC clerk = dispatcher.runOnClient(this::findNearestGeClerk);
            if (clerk == null) {
                log.warn("openGrandExchange: no GE clerk found on the loaded scene");
                return;
            }
            Rectangle bounds = dispatcher.runOnClient(() -> {
                Shape hull = clerk.getConvexHull();
                return hull == null ? null : hull.getBounds();
            });
            if (bounds == null || bounds.isEmpty()) {
                log.warn("openGrandExchange: clerk hull unresolved");
                return;
            }
            int cx = bounds.x + bounds.width / 2;
            int cy = bounds.y + bounds.height / 2;
            dispatcher.rightClickAndPickMenu(cx, cy, "Exchange");
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("openGrandExchange threw: {}", e.toString());
        }
    }

    private NPC findNearestGeClerk() {
        WorldView wv = client.getTopLevelWorldView();
        if (wv == null) return null;
        NPC closest = null;
        int closestDistSq = Integer.MAX_VALUE;
        var localPlayer = client.getLocalPlayer();
        if (localPlayer == null || localPlayer.getWorldLocation() == null) return null;
        var pl = localPlayer.getWorldLocation();
        for (NPC n : wv.npcs()) {
            if (n == null || !GE_CLERK_NPC_IDS.contains(n.getId())) continue;
            var loc = n.getWorldLocation();
            if (loc == null) continue;
            int dx = loc.getX() - pl.getX();
            int dy = loc.getY() - pl.getY();
            int dsq = dx * dx + dy * dy;
            if (dsq < closestDistSq) {
                closestDistSq = dsq;
                closest = n;
            }
        }
        return closest;
    }

    @Override
    public void clickOfferSlotButton(int slot, OfferSide side) {
        // The eight slot index containers in InterfaceID.GeOffers are
        // INDEX_0..INDEX_7. Empty slots show a + button; clicking it opens
        // the offer-setup view for that slot's side. The OSRS UI lets you
        // pick BUY or SELL after clicking the + slot.
        //
        // Simplification: we click the slot-index container; the post-click
        // BUY/SELL pick is handled by selectItem (which routes the offer
        // type via the search dialog).
        int widgetId = slotIndexWidget(slot);
        if (widgetId == 0) {
            log.warn("clickOfferSlotButton: slot {} out of range 0..7", slot);
            return;
        }
        clickWidget(widgetId, "clickOfferSlotButton(" + slot + "," + side + ")");
    }

    private static int slotIndexWidget(int slot) {
        return switch (slot) {
            case 0 -> InterfaceID.GeOffers.INDEX_0;
            case 1 -> InterfaceID.GeOffers.INDEX_1;
            case 2 -> InterfaceID.GeOffers.INDEX_2;
            case 3 -> InterfaceID.GeOffers.INDEX_3;
            case 4 -> InterfaceID.GeOffers.INDEX_4;
            case 5 -> InterfaceID.GeOffers.INDEX_5;
            case 6 -> InterfaceID.GeOffers.INDEX_6;
            case 7 -> InterfaceID.GeOffers.INDEX_7;
            default -> 0;
        };
    }

    @Override
    public void selectItem(int itemId, String displayName) {
        // The offer-setup search opens a chatbox; type the item name and the
        // result list is rendered. Phase A: type the displayName + Enter.
        // Production tuning may want to type only as many chars as needed
        // and click the matching result row directly.
        try {
            for (char c : displayName.toCharArray()) {
                dispatcher.typeChar(c);
            }
            dispatcher.tapKey(KeyEvent.VK_ENTER);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        // TODO: click the matching result row — requires identifying the
        // result-list widget (likely under InterfaceID.Chatbox.* with a
        // dynamic per-result child id). Until then, the "exact-match"
        // typing relies on Enter selecting the top result, which usually
        // matches when the displayName is unambiguous.
    }

    @Override
    public void setQuantity(int qty) {
        // The OSRS GE offer-setup interface has a "*X" button; clicking it
        // opens a chatbox numeric prompt. Type the quantity and Enter.
        // The exact widget id of *X varies; for Phase A we approximate by
        // typing into whatever chatbox is currently focused (caller has
        // pre-clicked SETUP / *X).
        //
        // TODO: dispatch a click on the *X widget first. Pinning the field
        // requires inspecting InterfaceID.GeOffers.SETUP_* in a live client.
        try {
            for (char c : Integer.toString(qty).toCharArray()) {
                dispatcher.typeChar(c);
            }
            dispatcher.tapKey(KeyEvent.VK_ENTER);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void setPrice(int priceEach) {
        try {
            for (char c : Integer.toString(priceEach).toCharArray()) {
                dispatcher.typeChar(c);
            }
            dispatcher.tapKey(KeyEvent.VK_ENTER);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void confirmOffer() {
        clickWidget(InterfaceID.GeOffers.SETUP_CONFIRM, "confirmOffer");
    }

    @Override
    public void collect(int slot) {
        // GeCollect.COLLECT_INV is the "Collect to inventory" button. Phase A
        // just clicks it once; if multiple slots are ready, COLLECTALL handles
        // them.
        clickWidget(InterfaceID.GeCollect.COLLECT_INV, "collect(" + slot + ")");
    }

    @Override
    public void closeGrandExchange() {
        try {
            dispatcher.tapKey(KeyEvent.VK_ESCAPE);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /** Resolve a widget's bounds on the client thread and dispatch a left-click
     *  at its center. Logs a warning + no-op if the widget is hidden. */
    private void clickWidget(int widgetId, String purpose) {
        try {
            Rectangle b = dispatcher.runOnClient(() -> {
                Widget w = client.getWidget(widgetId);
                if (w == null || w.isHidden()) return null;
                Rectangle r = w.getBounds();
                return r == null || r.isEmpty() ? null : r;
            });
            if (b == null) {
                log.warn("{}: widget {} unresolved/hidden", purpose, Integer.toHexString(widgetId));
                return;
            }
            dispatcher.clickCanvas(b.x + b.width / 2, b.y + b.height / 2);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
