package net.runelite.client.sequence.dispatch;

/**
 * A unit of work the dispatcher worker runs to completion off the client
 * thread. Wraps the multi-step blocking flows (right-click → wait →
 * type → wait → press Enter) that {@code BankInteraction}-style adapters
 * implement, so engine steps can enqueue them without ever calling them
 * synchronously from {@code Step.onStart}.
 *
 * <p>See the "Threading model" section at the top of {@code CLAUDE.md}
 * for the rule and the bank-withdraw-X regression that motivated this.
 */
@FunctionalInterface
public interface BlockingTask
{
    void run() throws InterruptedException;
}
