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
package net.runelite.client.plugins.recorder.mining;

import lombok.extern.slf4j.Slf4j;

/**
 * Walk-to-bank-and-deposit strategy. Stub today — the chicken routine's
 * banking flow will inform the full implementation. The mining loop calls
 * this when a configured strategy is "Bank" and inventory hits 28; today
 * it just logs and returns, which causes the loop to abort cleanly.
 *
 * <p>Future: walk to a configured bank tile, click banker / chest with
 * "Bank", deposit-all-but-pickaxe via the deposit interface, walk back.
 * Each step uses {@code CLICK_NPC} or {@code CLICK_GAME_OBJECT} via the
 * humanized dispatcher — same constraints as everything else here.
 */
@Slf4j
public final class BankDepositStrategy implements BankingStrategy
{
    @Override public String label() { return "Bank (stub)"; }

    @Override
    public void empty(MiningLoopContext ctx)
    {
        log.warn("BankDepositStrategy not implemented — bank flow deferred");
    }
}
