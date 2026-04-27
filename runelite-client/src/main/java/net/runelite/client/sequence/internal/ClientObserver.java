/*
 * Copyright (c) 2024, RuneLite
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
package net.runelite.client.sequence.internal;

import lombok.RequiredArgsConstructor;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.sequence.PlayerView;
import net.runelite.client.sequence.WorldSnapshot;

@RequiredArgsConstructor
public final class ClientObserver implements Observer {
    private final Client client;

    @Override
    public WorldSnapshot snapshot(int currentTick) {
        Player p = client.getLocalPlayer();
        PlayerView pv = (p == null) ? null : new ClientPlayerView(
            p.getWorldLocation(), p.getAnimation(),
            client.getBoostedSkillLevel(net.runelite.api.Skill.HITPOINTS),
            client.getRealSkillLevel(net.runelite.api.Skill.HITPOINTS));
        return new ClientWorldSnapshot(currentTick, pv);
    }

    private static final class ClientPlayerView implements PlayerView {
        private final WorldPoint worldLocation;
        private final int animation;
        private final int health;
        private final int maxHealth;

        ClientPlayerView(WorldPoint worldLocation, int animation, int health, int maxHealth) {
            this.worldLocation = worldLocation;
            this.animation = animation;
            this.health = health;
            this.maxHealth = maxHealth;
        }

        @Override public WorldPoint worldLocation() { return worldLocation; }
        @Override public int animation() { return animation; }
        @Override public boolean isIdle() { return animation == -1; }
        @Override public int health() { return health; }
        @Override public int maxHealth() { return maxHealth; }
    }

    private static final class ClientWorldSnapshot implements WorldSnapshot {
        private final int tick;
        private final PlayerView player;

        ClientWorldSnapshot(int tick, PlayerView player) {
            this.tick = tick;
            this.player = player;
        }

        @Override public int tick() { return tick; }
        @Override public PlayerView player() { return player; }
    }
}
