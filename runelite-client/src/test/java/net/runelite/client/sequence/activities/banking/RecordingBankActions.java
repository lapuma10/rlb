package net.runelite.client.sequence.activities.banking;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Test double for {@link BankActions}: records every method call as a string
 * of the form {@code "methodName(arg,arg,...)"} for assertion in tests.
 * Return values are void; configurable deposit/withdraw results are deferred to
 * Task 6 when the real implementations are wired (return types may change then).
 */
public final class RecordingBankActions implements BankActions {

    private final List<String> calls = new ArrayList<>();

    /** Returns an unmodifiable view of all recorded calls in invocation order. */
    public List<String> calls() {
        return Collections.unmodifiableList(calls);
    }

    /** Clear the recorded call list between test assertions. */
    public void reset() {
        calls.clear();
    }

    @Override
    public void clickBankBoothRandom() {
        calls.add("clickBankBoothRandom()");
    }

    @Override
    public void depositAll(int itemId) {
        calls.add("depositAll(" + itemId + ")");
    }

    @Override
    public void withdrawOne(int itemId) {
        calls.add("withdrawOne(" + itemId + ")");
    }

    @Override
    public void withdrawAll(int itemId) {
        calls.add("withdrawAll(" + itemId + ")");
    }

    @Override
    public void withdrawX(int itemId, int qty) {
        calls.add("withdrawX(" + itemId + "," + qty + ")");
    }

    @Override
    public void closeBank() {
        calls.add("closeBank()");
    }
}
