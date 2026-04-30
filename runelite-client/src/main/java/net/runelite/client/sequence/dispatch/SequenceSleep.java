/*
 * Copyright (c) 2026, RuneLite
 * All rights reserved.
 */
package net.runelite.client.sequence.dispatch;

import net.runelite.api.Client;

/**
 * Single sleep helper for the sequence engine — refuses to sleep the OSRS
 * client thread.
 *
 * <p><b>Why this exists:</b> the OSRS client uses ONE thread to render frames,
 * run cs2 scripts, tick NPCs, process the click queue, and update varcs/
 * varbits. {@link Thread#sleep(long)} on that thread freezes the ENTIRE
 * game world for the sleep duration. Symptom: the bot's polling loop runs
 * but the cs2 script that would open the chatbox / spawn an NPC / dismiss
 * a menu can't execute because we keep putting the thread to sleep — so
 * everything looks "throttled" and the bot's checks all fail because game
 * state never advances. Lost a multi-hour debugging session to this in
 * 2026-04-30; do not repeat.
 *
 * <p><b>How to use:</b>
 * <ul>
 *   <li>From a <b>worker thread</b> (dispatcher worker, login-assistant
 *       daemon, any spawned thread): call {@link #sleep(ClientThread, long)}.
 *       Behaves like {@code Thread.sleep}.</li>
 *   <li>From the <b>client thread</b> ({@code Step.onStart}/{@code check}/
 *       {@code tick}, {@code @Subscribe} handlers, {@code clientThread.invoke}
 *       runnables): <b>do not call</b>. The method throws
 *       {@link IllegalStateException} so the misuse fails loudly during
 *       development instead of silently freezing the game in production.</li>
 * </ul>
 *
 * <p>If you need a delay inside a step, DON'T sleep — either:
 * <ol>
 *   <li>Dispatch the work asynchronously (e.g. a new
 *       {@link net.runelite.client.sequence.internal.ActionRequest.Kind})
 *       so the dispatcher worker handles it off-thread, OR</li>
 *   <li>Split the wait across game ticks: have {@code Step.check()} return
 *       {@code RUNNING} until a deadline tick passes. Each tick is a free
 *       poll opportunity, no sleeping required.</li>
 * </ol>
 *
 * <p>This helper is the only sanctioned way to {@code Thread.sleep} inside
 * the sequence engine path. Bare {@code Thread.sleep} calls will compile
 * and silently freeze the client; this helper makes that mistake noisy.
 */
public final class SequenceSleep
{
    private SequenceSleep() {}

    /**
     * Sleep for {@code ms} milliseconds. Throws {@link IllegalStateException}
     * if invoked on the OSRS client thread (sleeping it freezes the game).
     *
     * @param client OSRS API handle (use the injected one from your
     *               dispatcher / plugin / engine wiring; null is tolerated
     *               for tests).
     * @param ms     sleep duration in milliseconds. Negative or zero
     *               returns immediately.
     */
    public static void sleep(Client client, long ms) throws InterruptedException
    {
        if (ms <= 0) return;
        if (client != null && client.isClientThread())
        {
            throw new IllegalStateException(
                "SequenceSleep.sleep(" + ms + "ms) called on the OSRS client thread — "
                    + "this would freeze the render loop / cs2 / NPCs for the entire "
                    + "duration. Dispatch the work to a worker thread (new ActionRequest "
                    + "Kind for the dispatcher) or split the wait across ticks via "
                    + "Step.check returning RUNNING until enough ticks elapse.");
        }
        Thread.sleep(ms);
    }
}
