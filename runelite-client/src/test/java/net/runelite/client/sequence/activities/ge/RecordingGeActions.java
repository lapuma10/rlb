package net.runelite.client.sequence.activities.ge;

import java.util.ArrayList;
import java.util.List;
import net.runelite.client.sequence.views.OfferSide;

/**
 * Test {@link GeActions} that records every call as a human-readable string.
 * Tests assert on the captured list to verify the step library dispatched
 * the right humanized actions in the right order.
 */
public final class RecordingGeActions implements GeActions {

    private final List<String> calls = new ArrayList<>();

    public List<String> calls() { return List.copyOf(calls); }

    public int callCount() { return calls.size(); }

    public void clear() { calls.clear(); }

    @Override public void openGrandExchange()                 { calls.add("openGrandExchange()"); }
    @Override public void clickOfferSlotButton(int slot, OfferSide side) {
        calls.add("clickOfferSlotButton(slot=" + slot + ", side=" + side + ")");
    }
    @Override public void openOfferDetail(int slot)           { calls.add("openOfferDetail(slot=" + slot + ")"); }
    @Override public boolean selectItem(int itemId, String displayName) {
        calls.add("selectItem(itemId=" + itemId + ", name=" + displayName + ")");
        return true;
    }
    @Override public boolean pickSearchResult(int itemId) {
        calls.add("pickSearchResult(itemId=" + itemId + ")");
        return true;
    }
    @Override public boolean setQuantity(int qty)             { calls.add("setQuantity(" + qty + ")"); return true; }
    @Override public boolean setPrice(int priceEach)          { calls.add("setPrice(" + priceEach + ")"); return true; }
    @Override public void confirmOffer()                      { calls.add("confirmOffer()"); }
    @Override public void dismissPriceWarning(boolean accept) {
        calls.add("dismissPriceWarning(" + (accept ? "Yes" : "No") + ")");
    }
    @Override public void collect(int slot)                   { calls.add("collect(slot=" + slot + ")"); }
    @Override public void collectAll()                        { calls.add("collectAll()"); }
    @Override public void closeGrandExchange()                { calls.add("closeGrandExchange()"); }
}
