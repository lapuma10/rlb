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
package net.runelite.client.sequence.dispatch;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.sequence.InputMode;
import net.runelite.client.sequence.internal.ActionRequest;

@Slf4j
@RequiredArgsConstructor
public final class DirectInputDispatcher implements InputDispatcher {
    private final Client client;

    @Override
    public void dispatch(ActionRequest req) {
        switch (req.getKind()) {
            case WALK -> walk(req.getTile());
            case CLICK_TILE -> walk(req.getTile()); // walking IS a tile click for now
            case CLICK_NPC -> npcOption(req.getNpcIndex(), req.getOption());
            case CLICK_GAME_OBJECT, CLICK_GROUND_ITEM, CLICK_WIDGET, CLICK_INV_ITEM, KEY ->
                log.debug("dispatch kind {} not yet implemented", req.getKind());
        }
    }

    private void walk(WorldPoint target) {
        if (target == null) return;
        LocalPoint local = LocalPoint.fromWorld(client.getTopLevelWorldView(), target);
        if (local == null) {
            log.debug("walk target {} not in current scene", target);
            return;
        }
        client.menuAction(local.getSceneX(), local.getSceneY(),
            MenuAction.WALK, 0, 0, "Walk here", "");
    }

    private void npcOption(int npcIndex, String option) {
        // resolution helper kept stub for v1; future tasks fill this out
        log.debug("npcOption({}, {}) not yet implemented", npcIndex, option);
    }

    @Override public void cancel(ActionRequest req) { /* in-flight cancellation N/A for direct */ }
    @Override public boolean isBusy() { return false; }
    @Override public InputMode mode() { return InputMode.DIRECT; }
}
