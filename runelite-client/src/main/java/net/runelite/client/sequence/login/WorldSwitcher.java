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

import net.runelite.api.Client;

/**
 * Switches the OSRS world. Mechanism TBD per spec §7 — see
 * docs/superpowers/specs/2026-04-26-login-overhaul-design.md §7.
 *
 * Currently stubbed: throws UnsupportedOperationException. Recovery from
 * WORLD_FULL / MEMBER_WORLD will surface this as WORLD_SWITCH_FAILED via
 * the runner's Exception catch in §6.2.
 *
 * To complete: pick B1 (canvas-pixel humanized) or B2 (client.changeWorld
 * engine-setter carve-out) and implement switchTo() accordingly.
 */
public final class WorldSwitcher
{
    private final Client client;

    public WorldSwitcher(Client client)
    {
        this.client = client;
    }

    /**
     * Switch to the given world. Blocks until the switch is observed via
     * client.getWorld() or fails with an exception.
     */
    public void switchTo(int targetWorldId) throws InterruptedException
    {
        throw new UnsupportedOperationException(
            "WorldSwitcher mechanism TBD — see spec §7 (B1 canvas-pixel vs B2 engine-setter). "
            + "Pick one and implement before relying on world-switch recovery.");
    }
}
