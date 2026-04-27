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
package net.runelite.client.plugins.recorder.debug;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;
import net.runelite.client.RuneLite;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * Dumps every currently-visible widget to log + a sidecar txt file.
 * Use to identify widget IDs for the welcome screen sub-components,
 * login error banner, and in-game world picker (spec §9).
 */
@Slf4j
public final class WidgetDumper
{
    private WidgetDumper() {}

    /**
     * Walk client.getWidgetRoots(), log each, and write a sidecar.
     * Caller must invoke from the client thread (use dispatcher.runOnClient).
     *
     * @return path written
     */
    public static Path dump(Client client) throws IOException
    {
        Path dir = RuneLite.RUNELITE_DIR.toPath().resolve("recorder");
        Files.createDirectories(dir);
        Path out = dir.resolve("widget-dump-" + Instant.now().getEpochSecond() + ".txt");

        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(out)))
        {
            pw.println("# Widget dump @ " + Instant.now());
            Widget[] roots = client.getWidgetRoots();
            if (roots == null) { pw.println("(no roots)"); log.info("widget dump: no roots"); return out; }
            for (Widget r : roots) walk(r, 0, pw);
        }
        log.info("widget dump written to {}", out);
        return out;
    }

    private static void walk(Widget w, int depth, PrintWriter pw)
    {
        if (w == null) return;
        String pad = " ".repeat(depth * 2);
        int id = w.getId();
        int group = id >>> 16;
        int child = id & 0xFFFF;
        String text = w.getText();
        int spriteId = w.getSpriteId();
        java.awt.Rectangle b = null;
        try { b = w.getBounds(); } catch (Exception ignored) {}
        pw.printf("%s0x%04x_%04x hidden=%s text=%s sprite=%d bounds=%s%n",
            pad, group, child, w.isHidden(),
            text != null && !text.isEmpty() ? "\"" + text.replace('\n', ' ') + "\"" : "-",
            spriteId, b != null ? b.toString() : "?");
        Widget[] children = w.getChildren();
        if (children != null) for (Widget c : children) walk(c, depth + 1, pw);
        Widget[] dynamic = w.getDynamicChildren();
        if (dynamic != null) for (Widget c : dynamic) walk(c, depth + 1, pw);
    }
}
