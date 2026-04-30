/*
 * Copyright (c) 2026, RuneLite
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.client.sequence.login;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.sequence.dispatch.SequenceSleep;

/**
 * Applies the recovery action for a recoverable LoginError. Caller invokes
 * only if error.recoverable() is true.
 *
 * Sleep durations are interruptible — InterruptedException propagates.
 *
 * See spec §6.1 (per-error recovery durations).
 */
@Slf4j
public final class LoginErrorRecovery
{
    private LoginErrorRecovery() {}

    public static void apply(LoginError error, LoginContext ctx) throws InterruptedException
    {
        switch (error)
        {
            case WORLD_FULL:
            case MEMBER_WORLD:
                doWorldSwitch(ctx);
                sleepRange(ctx, 2_000, 5_000);
                return;
            case JUST_LEFT_OTHER_WORLD:
                sleepRange(ctx, 6_000, 12_000);
                return;
            case CONNECTION_TIMEOUT:
            case TIMEOUT_NO_RESPONSE:
                sleepRange(ctx, 5_000, 30_000);
                return;
            case SERVER_OFFLINE:
                sleepRange(ctx, 30_000, 60_000);
                return;
            case UNKNOWN_LOGIN_ERROR:
                sleepRange(ctx, 8_000, 15_000);
                return;
            case CLIENT_THREAD_STUCK:
                sleepRange(ctx, 3_000, 6_000);
                return;
            default:
                throw new IllegalStateException("recovery requested for non-recoverable error: " + error);
        }
    }

    private static void doWorldSwitch(LoginContext ctx) throws InterruptedException
    {
        net.runelite.api.World[] worlds = ctx.getClient().getWorldList();
        if (worlds == null) worlds = new net.runelite.api.World[0];
        Integer target = new WorldPicker(ctx.getRng()).pickF2PNonPvP(worlds, ctx.getCurrentWorldId());
        if (target == null)
        {
            log.warn("[login] no candidate worlds for switch — staying on current world");
            return;
        }
        log.info("[login] switching world: {} -> {}", ctx.getCurrentWorldId(), target);
        try
        {
            new WorldSwitcher(ctx.getClient()).switchTo(target);
            ctx.setCurrentWorldId(target);
        }
        catch (UnsupportedOperationException uoe)
        {
            log.warn("[login] world-switch unavailable; staying on current world: {}", uoe.getMessage());
        }
    }

    private static void sleepRange(LoginContext ctx, int minMs, int maxMs) throws InterruptedException
    {
        long ms = minMs + ctx.getRng().nextInt(maxMs - minMs);
        log.info("[login] recovery sleep {}ms", ms);
        SequenceSleep.sleep(ctx.getClient(), ms);
    }
}
