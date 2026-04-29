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

import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.sequence.PlayerView;
import net.runelite.client.sequence.WorldSnapshot;
import net.runelite.client.sequence.views.GrandExchangeView;
import net.runelite.client.sequence.views.InteractionView;
import net.runelite.client.sequence.views.InventoryView;

/**
 * Production {@link Observer} composing the per-domain observers
 * (player / inventory / interaction / grand-exchange). Reads happen on the
 * client thread (caller marshals via {@code clientThread::invoke}).
 *
 * <p>{@code GrandExchangeObserver} arrives in Task 6 — until then {@code
 * grandExchange()} returns {@link GrandExchangeView#empty()}. Banking will
 * add {@code BankObserver} / {@code WidgetObserver} on rebase.
 */
public final class ClientObserver implements Observer {
    private final Client client;
    private final InventoryObserver inventoryObserver;
    private final InteractionObserver interactionObserver;
    private final GrandExchangeObserver grandExchangeObserver;

    public ClientObserver(Client client) {
        this.client = client;
        this.inventoryObserver = new InventoryObserver(client);
        this.interactionObserver = new InteractionObserver(client);
        this.grandExchangeObserver = new GrandExchangeObserver(client);
    }

    @Override
    public WorldSnapshot snapshot(int currentTick) {
        // Player view: preserve existing semantics (LoginRunner depends on it).
        Player p = client.getLocalPlayer();
        PlayerView pv = (p == null) ? null : new ClientPlayerView(
            p.getWorldLocation(), p.getAnimation(),
            client.getBoostedSkillLevel(net.runelite.api.Skill.HITPOINTS),
            client.getRealSkillLevel(net.runelite.api.Skill.HITPOINTS));

        InventoryView inv = inventoryObserver.read(currentTick);
        InteractionView interaction = interactionObserver.read(currentTick);
        GrandExchangeView ge = grandExchangeObserver.read(currentTick);

        return new ImmutableWorldSnapshot(currentTick, pv, inv, interaction, ge);
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
}
