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
package net.runelite.client.plugins.sequencer;

import com.google.inject.Provides;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.events.GameTick;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.sequence.SequenceManager;
import net.runelite.client.sequence.activities.WalkStepFactory;
import net.runelite.client.sequence.dispatch.DirectInputDispatcher;
import net.runelite.client.sequence.internal.ClientObserver;

@Slf4j
@PluginDescriptor(
    name = "Sequencer",
    description = "State-driven sequence engine for OSRS workflows",
    tags = {"sequence", "automation", "engine"}
)
public class SequencerPlugin extends Plugin {

    @Inject private Client client;
    @Inject private ClientThread clientThread;
    @Inject private SequencerConfig config;
    @Inject private net.runelite.client.ui.ClientToolbar clientToolbar;
    private SequencerPanel panel;
    private net.runelite.client.ui.NavigationButton navButton;

    private SequenceManager manager;

    @Provides SequencerConfig provideConfig(ConfigManager cm) { return cm.getConfig(SequencerConfig.class); }

    @Override
    protected void startUp() {
        manager = SequenceManager.withDefaults();
        manager.setObserver(new ClientObserver(client));
        manager.setDispatcher(new DirectInputDispatcher(client));
        // EDT/AWT-key callers (panel buttons, hotkeys) marshal through ClientThread
        // so any Client reads inside the engine happen on the only thread that's
        // allowed to do that.
        manager.setScheduler(clientThread::invoke);
        manager.getRegistry().register(new WalkStepFactory());
        panel = new SequencerPanel(manager);
        java.awt.image.BufferedImage iconImg = net.runelite.client.util.ImageUtil.loadImageResource(getClass(), "/util/arrow_right.png");
        navButton = net.runelite.client.ui.NavigationButton.builder()
            .tooltip("Sequencer")
            .icon(iconImg)
            .priority(7)
            .panel(panel)
            .build();
        clientToolbar.addNavigation(navButton);
        log.info("Sequencer plugin started");
    }

    @Override
    protected void shutDown() {
        if (navButton != null) clientToolbar.removeNavigation(navButton);
        if (panel != null) panel.dispose();
        panel = null;
        navButton = null;
        if (manager != null) manager.stop();
        manager = null;
    }

    @Subscribe
    public void onGameTick(GameTick event) {
        if (manager != null && manager.getEngine() != null) {
            manager.getEngine().advanceTick();
        }
    }

    // Forward a curated set of RuneLite events to the engine. Steps that care
    // about specific event types narrow via instanceof in their onEvent.

    @Subscribe public void onItemContainerChanged(net.runelite.api.events.ItemContainerChanged e) { offer(e); }
    @Subscribe public void onAnimationChanged(net.runelite.api.events.AnimationChanged e)         { offer(e); }
    @Subscribe public void onChatMessage(net.runelite.api.events.ChatMessage e)                   { offer(e); }
    @Subscribe public void onMenuOptionClicked(net.runelite.api.events.MenuOptionClicked e)       { offer(e); }
    @Subscribe public void onGameStateChanged(net.runelite.api.events.GameStateChanged e)         { offer(e); }

    private void offer(Object event) { if (manager != null) manager.offerEvent(event); }

    public SequenceManager manager() { return manager; }
}
