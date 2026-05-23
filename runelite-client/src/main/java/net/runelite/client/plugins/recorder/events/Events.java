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
package net.runelite.client.plugins.recorder.events;

import java.util.List;
import java.util.Map;

public final class Events {
    private Events() {}

    public record Tick(long seq, long tMs, int tick,
                       int worldX, int worldY, int worldPlane,
                       int animation, boolean idle,
                       int runEnergy, boolean runOn,
                       int hp, int maxHp) implements RecordedEvent {
        @Override public String type() { return "tick"; }
    }

    public record MouseMove(long seq, long tMs, int tick, int x, int y) implements RecordedEvent {
        @Override public String type() { return "mousemove"; }
    }

    public record MouseDown(long seq, long tMs, int tick, int button, int x, int y) implements RecordedEvent {
        @Override public String type() { return "mousedown"; }
    }

    public record MouseUp(long seq, long tMs, int tick, int button, int x, int y, long holdMs) implements RecordedEvent {
        @Override public String type() { return "mouseup"; }
    }

    public record Wheel(long seq, long tMs, int tick, int x, int y, int delta) implements RecordedEvent {
        @Override public String type() { return "wheel"; }
    }

    public record Key(long seq, long tMs, int tick, int keyCode, int modifiers, boolean down) implements RecordedEvent {
        @Override public String type() { return "key"; }
    }

    public record Focus(long seq, long tMs, int tick, boolean gained) implements RecordedEvent {
        @Override public String type() { return "focus"; }
    }

    public record MenuOpen(long seq, long tMs, int tick, int x, int y, List<MenuRow> rows) implements RecordedEvent {
        @Override public String type() { return "menu_open"; }
        public record MenuRow(String verb, String target, int targetId, String targetKind) {}
    }

    public record MenuClick(long seq, long tMs, int tick,
                            int rowIndex, int x, int y, long dwellMs,
                            String verb, String target, int targetId, String targetKind,
                            int param0, int param1, int actionId) implements RecordedEvent {
        @Override public String type() { return "menu_click"; }
    }

    public record WidgetClick(long seq, long tMs, int tick,
                              int widgetId, int itemId, int slot,
                              int bboxX, int bboxY, int bboxW, int bboxH,
                              int offsetX, int offsetY,
                              String widgetKind) implements RecordedEvent {
        @Override public String type() { return "widget_click"; }
    }

    public record WorldClick(long seq, long tMs, int tick,
                             String entityKind, int entityId, String entityName,
                             int worldX, int worldY, int worldPlane,
                             int screenX, int screenY) implements RecordedEvent {
        @Override public String type() { return "world_click"; }
    }

    public record InvChange(long seq, long tMs, int tick, List<SlotDelta> deltas) implements RecordedEvent {
        @Override public String type() { return "inv_change"; }
        public record SlotDelta(int slot, int beforeId, int beforeQty, int afterId, int afterQty) {}
    }

    public record EquipChange(long seq, long tMs, int tick, List<InvChange.SlotDelta> deltas) implements RecordedEvent {
        @Override public String type() { return "equip_change"; }
    }

    public record BankChange(long seq, long tMs, int tick, List<InvChange.SlotDelta> deltas) implements RecordedEvent {
        @Override public String type() { return "bank_change"; }
    }

    public record Chat(long seq, long tMs, int tick, String chatType, String sender, String message) implements RecordedEvent {
        @Override public String type() { return "chat"; }
    }

    public record XpChange(long seq, long tMs, int tick, String skill, int before, int after) implements RecordedEvent {
        @Override public String type() { return "xp_change"; }
    }

    public record Camera(long seq, long tMs, int tick, int yaw, int pitch, int zoom) implements RecordedEvent {
        @Override public String type() { return "camera"; }
    }

    public record Nearby(long seq, long tMs, int tick, List<NearbyEntity> entities) implements RecordedEvent {
        @Override public String type() { return "nearby"; }
        public record NearbyEntity(String kind, int id, String name, int worldX, int worldY, int worldPlane) {}
    }

    public record Marker(long seq, long tMs, int tick, String label) implements RecordedEvent {
        @Override public String type() { return "marker"; }
    }

    public record MarkerDialog(long seq, long tMs, int tick, boolean opened) implements RecordedEvent {
        @Override public String type() { return "marker_dialog"; }
    }

    /** Mode tag for the surrounding events: {@code "live"} when inputs come
     *  from the operator, {@code "bot_watch"} when a registered script is
     *  driving the dispatcher. Emitted once at the start of every recording
     *  (with the mode active at that moment), then on every transition while
     *  the recording is open. {@code scriptId} is the {@link
     *  net.runelite.client.plugins.recorder.session.SessionTracker} script
     *  id, or {@code null} when {@code mode == "live"}. */
    public record ScriptMode(long seq, long tMs, int tick,
                             String mode, String scriptId) implements RecordedEvent {
        @Override public String type() { return "script_mode"; }
    }
}
