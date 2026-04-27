/*
 * Copyright (c) 2025, https://github.com/runelite/runelite
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
package net.runelite.client.plugins.recorder.analyse;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.runelite.client.plugins.recorder.events.EventCodec;
import net.runelite.client.plugins.recorder.events.RecordedEvent;
import java.util.List;
import java.util.stream.Collectors;

/** Generates a single self-contained HTML file with embedded events JSON
 *  and a vanilla-JS scrubber. The user opens it by double-click. */
public final class HtmlViewerGenerator
{
    private static final String TEMPLATE = """
        <!DOCTYPE html>
        <html><head><meta charset="utf-8"><title>Recording</title>
        <style>
          body { background:#111; color:#eee; font:13px/1.4 system-ui; margin:0; padding:8px; }
          #wrap { display:grid; grid-template-columns:1fr 320px; grid-template-rows:auto 1fr auto; gap:8px; height:96vh; }
          #scrub { grid-column:1/3; }
          canvas { width:100%; height:100%; background:#000; border:1px solid #333; }
          #events { overflow:auto; background:#0a0a0a; border:1px solid #333; padding:6px; }
          #events div { font-family: ui-monospace, monospace; padding:1px 0; border-bottom:1px solid #1a1a1a; }
          #ctrl { grid-column:1/3; display:flex; gap:8px; align-items:center; }
          input[type=range] { flex:1; }
          button { background:#222; color:#eee; border:1px solid #444; padding:4px 12px; }
        </style></head><body>
        <div id="wrap">
          <input id="scrub" type="range" min="0" max="0" value="0">
          <canvas id="cv"></canvas>
          <div id="events"></div>
          <div id="ctrl"><button id="play">Play</button>
            <span>Speed:</span><button data-r="1">1×</button><button data-r="4">4×</button><button data-r="16">16×</button>
            <span id="t"></span></div>
        </div>
        <script>
        const EVENTS = __EVENTS__;
        const PHASES = __PHASES__;
        const cv = document.getElementById('cv');
        const ctx = cv.getContext('2d');
        const scrub = document.getElementById('scrub');
        const evList = document.getElementById('events');
        const tLabel = document.getElementById('t');
        const playBtn = document.getElementById('play');
        scrub.max = EVENTS.length;
        let pos = 0, playing = false, rate = 1, lastFrameMs = 0;

        function fitCanvas() { cv.width = cv.clientWidth; cv.height = cv.clientHeight; }
        window.addEventListener('resize', fitCanvas); fitCanvas();

        function render(idx) {
          ctx.fillStyle = '#000'; ctx.fillRect(0, 0, cv.width, cv.height);
          ctx.strokeStyle = 'rgba(120,200,255,0.5)'; ctx.lineWidth = 1;
          let count = 0; let started = false;
          ctx.beginPath();
          for (let i = idx - 1; i >= 0 && count < 100; i--) {
            const e = EVENTS[i];
            if (e.type === 'mousemove') {
              if (!started) { ctx.moveTo(e.x, e.y); started = true; }
              else ctx.lineTo(e.x, e.y);
              count++;
            }
          }
          ctx.stroke();
          for (let i = Math.max(0, idx - 50); i < idx; i++) {
            const e = EVENTS[i];
            if (e.type === 'menu_click') {
              ctx.fillStyle = '#ff5'; ctx.beginPath(); ctx.arc(e.x, e.y, 4, 0, 7); ctx.fill();
            }
          }
          let html = '';
          for (let i = Math.max(0, idx - 40); i < idx; i++) {
            const e = EVENTS[i];
            html += '<div>[' + e.t_ms + 'ms] ' + e.type + ' ' + (e.option || e.label || '') + '</div>';
          }
          evList.innerHTML = html;
          tLabel.textContent = idx + ' / ' + EVENTS.length + (EVENTS[idx] ? ' @ ' + EVENTS[idx].t_ms + 'ms' : '');
        }

        scrub.addEventListener('input', () => { pos = +scrub.value; render(pos); });
        playBtn.addEventListener('click', () => { playing = !playing; playBtn.textContent = playing ? 'Pause' : 'Play'; });
        document.querySelectorAll('button[data-r]').forEach(b => b.addEventListener('click', () => rate = +b.dataset.r));

        function tick(ts) {
          if (playing && pos < EVENTS.length) {
            if (lastFrameMs === 0) lastFrameMs = ts;
            const dt = ts - lastFrameMs; lastFrameMs = ts;
            const advance = Math.max(1, Math.floor(dt / 16) * rate);
            pos = Math.min(pos + advance, EVENTS.length);
            scrub.value = pos;
            render(pos);
          } else { lastFrameMs = ts; }
          requestAnimationFrame(tick);
        }
        requestAnimationFrame(tick);
        render(0);
        </script></body></html>
        """;

    public String generate(List<RecordedEvent> events, List<PhaseSegmenter.Phase> phases)
    {
        EventCodec codec = new EventCodec();
        String eventsJson = "[" + events.stream().map(codec::toJsonLine).collect(Collectors.joining(",")) + "]";
        Gson g = new GsonBuilder().disableHtmlEscaping().create();
        String phasesJson = g.toJson(phases);
        return TEMPLATE
            .replace("__EVENTS__", eventsJson)
            .replace("__PHASES__", phasesJson);
    }
}
