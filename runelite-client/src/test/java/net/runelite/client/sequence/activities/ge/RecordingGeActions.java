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
    @Override public void selectItem(int itemId, String displayName) {
        calls.add("selectItem(itemId=" + itemId + ", name=" + displayName + ")");
    }
    @Override public void setQuantity(int qty)                { calls.add("setQuantity(" + qty + ")"); }
    @Override public void setPrice(int priceEach)             { calls.add("setPrice(" + priceEach + ")"); }
    @Override public void confirmOffer()                      { calls.add("confirmOffer()"); }
    @Override public void collect(int slot)                   { calls.add("collect(slot=" + slot + ")"); }
    @Override public void closeGrandExchange()                { calls.add("closeGrandExchange()"); }
}
