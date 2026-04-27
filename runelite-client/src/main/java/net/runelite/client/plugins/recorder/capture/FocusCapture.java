/*
 * Copyright (c) 2025, Lifeless
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
package net.runelite.client.plugins.recorder.capture;

import net.runelite.client.plugins.recorder.buffer.RecordingBuffer;
import net.runelite.client.plugins.recorder.events.Events;
import java.awt.event.WindowEvent;
import java.awt.event.WindowFocusListener;

/** Window focus tracking — alt-tab is a strong "human" signal. The plugin
 *  attaches this to the RuneLite frame on startUp. */
public final class FocusCapture implements WindowFocusListener
{
    private volatile RecordingBuffer buffer;

    public void setBuffer(RecordingBuffer buf) { this.buffer = buf; }

    @Override
    public void windowGainedFocus(WindowEvent e)
    {
        RecordingBuffer b = buffer;
        if (b == null) return;
        b.enqueue((seq, tMs) -> new Events.Focus(seq, tMs, 0, true));
    }

    @Override
    public void windowLostFocus(WindowEvent e)
    {
        RecordingBuffer b = buffer;
        if (b == null) return;
        b.enqueue((seq, tMs) -> new Events.Focus(seq, tMs, 0, false));
    }
}
