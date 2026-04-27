/*
 * Copyright (c) 2025, Mantas
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
package net.runelite.client.plugins.recorder.hotkey;

import lombok.RequiredArgsConstructor;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.plugins.recorder.RecorderManager;
import net.runelite.client.plugins.recorder.RecorderState;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.awt.Frame;
import java.io.IOException;

/** Bridges configured hotkeys to RecorderManager. The marker hotkey:
 *  - if IDLE, starts recording; - if RECORDING, opens the marker dialog. */
@RequiredArgsConstructor
public final class HotkeyHandler
{
    private final RecorderManager manager;
    private final ClientThread clientThread;
    private final MarkerDialog dialog = new MarkerDialog();

    public void onMarkerHotkey()
    {
        SwingUtilities.invokeLater(() -> {
            if (manager.getState() == RecorderState.IDLE)
            {
                clientThread.invoke(() -> {
                    try { manager.start(); } catch (IOException e) { /* logged in manager */ }
                });
            }
            else if (manager.getState() == RecorderState.RECORDING)
            {
                // Pair: open before showing modal dialog, close once after it returns
                // (regardless of accept/cancel). MarkerDialog.show is modal so this
                // sequence runs in order: open → dialog blocks → marker (if accept) → close.
                manager.recordMarkerDialogOpen();
                Frame owner = JOptionPane.getRootFrame();
                dialog.show(owner, manager::recordMarker);
                manager.recordMarkerDialogClose();
            }
        });
    }

    public void onToggleHotkey()
    {
        if (manager.getState() == RecorderState.IDLE)
        {
            clientThread.invoke(() -> {
                try { manager.start(); } catch (IOException e) { /* logged */ }
            });
        }
        else if (manager.getState() == RecorderState.RECORDING)
        {
            SwingUtilities.invokeLater(() -> {
                String label = JOptionPane.showInputDialog(null,
                    "Intent label for this recording?", "Stop recording", JOptionPane.PLAIN_MESSAGE);
                if (label == null) return;
                clientThread.invoke(() -> {
                    try { manager.stop(label); } catch (IOException e) { /* logged */ }
                });
            });
        }
    }
}
