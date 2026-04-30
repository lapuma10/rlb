package net.runelite.client.sequence.activities.banking;

/**
 * Actions that banking steps can dispatch through.
 * Implemented by {@link net.runelite.client.plugins.recorder.farm.BankInteraction}.
 *
 * <p>All methods declare {@link InterruptedException} because they dispatch
 * humanized input on a worker thread that may be interrupted during
 * shutdown.
 */
public interface BankActions {
    /** Click a random bank booth tile to open the bank. */
    void clickBankBoothRandom() throws InterruptedException;

    /** Deposit all of the given item from inventory into the bank. */
    void depositAll(int itemId) throws InterruptedException;

    /** Withdraw exactly one of the given item from the bank. */
    void withdrawOne(int itemId) throws InterruptedException;

    /** Withdraw all of the given item from the bank. */
    void withdrawAll(int itemId) throws InterruptedException;

    /** Withdraw a specific quantity of the given item from the bank. */
    void withdrawX(int itemId, int qty) throws InterruptedException;

    /** Withdraw {@code qty} of {@code itemId} as a noted stack — single
     *  inventory slot regardless of qty. The implementation is responsible
     *  for ensuring the bank's "Note" toggle is on for this withdraw and
     *  restoring it afterwards if it changed it. */
    void withdrawAsNoteX(int itemId, int qty) throws InterruptedException;

    /** Close the bank widget. */
    void closeBank() throws InterruptedException;
}
