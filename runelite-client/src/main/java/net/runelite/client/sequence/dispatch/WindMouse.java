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
package net.runelite.client.sequence.dispatch;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Generates a humanized cursor path between two screen points. Output is a
 * list of (x, y, dt_ms) samples — feed each (x, y) into a synthesized
 * MOUSE_MOVED event and sleep dt_ms between them.
 *
 * The model is a cubic Bezier with two random control points pulled
 * perpendicular to the start→end vector, plus per-step gaussian noise.
 * Path tempo is shaped by a generalized ease curve {@code t^a / (t^a +
 * (1-t)^b)} where {@code a, b} are sampled per-path so the peak velocity
 * isn't always at the same fractional time. Each call also samples a pace
 * factor, noise sigma, and step cadence, so consecutive paths to the same
 * point differ visibly in tempo, curvature, and tremor.
 *
 * <p>With a small probability per path, a mid-flight hesitation sample is
 * injected — a 60–180ms pause with a couple pixels of tremor — to mimic
 * the brief stalls a real cursor makes when the user re-acquires the
 * target.
 */
public final class WindMouse
{
    /** One sample on the path. dtMs is delay before this sample is dispatched. */
    public record Sample(int x, int y, int dtMs) {}

    private final Random rng;
    /** How far the Bezier control points wander perpendicular to the line.
     *  Bigger = more pronounced curve. Scaled by total distance. */
    private final double controlOffsetSigma = 0.18;
    /** Lower/upper bounds on total path duration (ms). Wide range so that
     *  short hops can take 220ms (deliberate) and long sweeps can take
     *  over a second when {@code paceFactor} is high. */
    private final int minDurationMs = 220;
    private final int maxDurationMs = 1500;
    /** Probability that a path inserts a mid-flight hesitation sample. */
    private static final double HESITATION_PROB = 0.35;
    /** Probability that a path also inserts a SECOND, smaller pause —
     *  some real moves stutter twice. */
    private static final double SECOND_PAUSE_PROB = 0.20;
    /** Probability the path uses a tempo break: fast first half, slow
     *  second half (or vice-versa). Stacks on top of paceFactor. */
    private static final double TEMPO_BREAK_PROB = 0.30;

    public WindMouse() { this(new Random()); }
    public WindMouse(long seed) { this(new Random(seed)); }
    public WindMouse(Random rng) { this.rng = rng; }

    /** Generate a path from (x0,y0) to (x1,y1). Per-path envelope params
     *  (pace, ease asymmetry, noise) are sampled fresh on each call so
     *  consecutive paths look different even between the same two points. */
    public List<Sample> path(int x0, int y0, int x1, int y1)
    {
        double dx = x1 - x0, dy = y1 - y0;
        double dist = Math.hypot(dx, dy);

        // ---- Per-path envelope sampling ----
        // Heavy slow bias: ~92% of paths are deliberate (1.0×–1.9× the
        // baseline duration), and only ~8% are quick snaps (0.7×–0.95×).
        // This matches the ask "almost never super fast" — fast moves are
        // the exception, not the default.
        double paceFactor;
        if (rng.nextDouble() < 0.08)
        {
            paceFactor = 0.70 + rng.nextDouble() * 0.25;   // 0.70..0.95
        }
        else
        {
            paceFactor = clamp(1.20 + rng.nextGaussian() * 0.25, 1.0, 1.9);
        }
        // ease asymmetry: peak-velocity time shifts earlier (negative) or
        // later (positive). Real human cursor traces follow Fitts's-law:
        // peak velocity 25–35% in, then a long deceleration. Bias the mean
        // negative so most paths sit in that regime.
        double easeAsym = clamp(-0.15 + rng.nextGaussian() * 0.25, -0.55, 0.40);
        double easeA = 1.6 - easeAsym;
        double easeB = 1.6 + easeAsym;
        // Per-path tremor — some paths almost rail-straight (0.3),
        // some visibly shaky (1.4).
        double pathNoise = clamp(0.6 + rng.nextGaussian() * 0.32, 0.30, 1.4);
        // Step cadence — recorded human traces sit at ~5–12ms between
        // samples (downsampled OS events). 10–18ms keeps us close.
        int stepMs = 10 + rng.nextInt(9);   // 10..18

        // Tempo break: at some fractional point along the path, the cadence
        // changes. Mimics a user "deciding" mid-flight to slow down or
        // speed up. Picked when sampled, applied below.
        boolean tempoBreak = rng.nextDouble() < TEMPO_BREAK_PROB;
        double tempoBreakAt = 0.30 + rng.nextDouble() * 0.45;
        double tempoBreakRatio = 0.55 + rng.nextDouble() * 1.10;   // 0.55 .. 1.65

        // Empirical baseline: ~1.6 px/ms feels natural for typical hops,
        // with a 110ms reaction-time floor. Inflated by paceFactor.
        double baseDuration = dist / 1.6 + 110;
        int duration = (int) Math.max(minDurationMs,
            Math.min(maxDurationMs, baseDuration * paceFactor));
        int n = Math.max(3, duration / stepMs);

        // ---- Bezier control points: curve, not a straight line ----
        double perpX = -dy / Math.max(1, dist);
        double perpY = dx / Math.max(1, dist);
        double off1 = rng.nextGaussian() * dist * controlOffsetSigma;
        double off2 = rng.nextGaussian() * dist * controlOffsetSigma;
        double c1x = x0 + dx * 0.33 + perpX * off1;
        double c1y = y0 + dy * 0.33 + perpY * off1;
        double c2x = x0 + dx * 0.66 + perpX * off2;
        double c2y = y0 + dy * 0.66 + perpY * off2;

        // ---- Optional mid-path hesitations ----
        // Up to two stall points. The first is more pronounced (60..240ms);
        // the second, when present, is shorter (30..90ms). Disabled on
        // short hops (<80 px) — a pause on a tiny move looks more robotic
        // than smooth movement.
        boolean hesitate = dist >= 80 && rng.nextDouble() < HESITATION_PROB;
        double hesitateAt = 0.25 + rng.nextDouble() * 0.50;
        int hesitatePauseMs = 60 + rng.nextInt(180);
        int hesitateIdx = hesitate ? Math.max(1, Math.min(n - 1, (int) Math.round(n * hesitateAt))) : -1;

        boolean secondPause = hesitate && dist >= 140 && rng.nextDouble() < SECOND_PAUSE_PROB;
        // Place the second pause AFTER the first by at least 15% of path length.
        double secondAt = Math.min(0.92, hesitateAt + 0.15 + rng.nextDouble() * 0.30);
        int secondPauseMs = 30 + rng.nextInt(60);
        int secondIdx = secondPause ? Math.max(1, Math.min(n - 1, (int) Math.round(n * secondAt))) : -1;

        List<Sample> out = new ArrayList<>(n + 2);
        int prevX = x0, prevY = y0;
        for (int i = 1; i <= n; i++)
        {
            double linear = (double) i / n;
            double t = generalizedSmoothstep(linear, easeA, easeB);
            double mt = 1 - t;
            double bx = mt * mt * mt * x0
                + 3 * mt * mt * t * c1x
                + 3 * mt * t * t * c2x
                + t * t * t * x1;
            double by = mt * mt * mt * y0
                + 3 * mt * mt * t * c1y
                + 3 * mt * t * t * c2y
                + t * t * t * y1;
            if (i < n)
            {
                bx += rng.nextGaussian() * pathNoise;
                by += rng.nextGaussian() * pathNoise;
            }
            int sx = (int) Math.round(bx);
            int sy = (int) Math.round(by);
            if (sx == prevX && sy == prevY && i < n) continue;

            // Apply tempo break: past the break point, multiply the
            // baseline dt by tempoBreakRatio for the rest of the path.
            int effectiveStep = stepMs;
            if (tempoBreak && linear >= tempoBreakAt)
            {
                effectiveStep = (int) Math.max(6, Math.round(stepMs * tempoBreakRatio));
            }
            int dt = Math.max(4, effectiveStep + (int) Math.round(rng.nextGaussian() * 3));
            out.add(new Sample(sx, sy, dt));
            prevX = sx; prevY = sy;

            // First (larger) hesitation.
            if (i == hesitateIdx)
            {
                int tx = sx + (rng.nextBoolean() ? 1 : -1) * (1 + rng.nextInt(2));
                int ty = sy + (rng.nextBoolean() ? 1 : -1) * (1 + rng.nextInt(2));
                out.add(new Sample(tx, ty, hesitatePauseMs));
                prevX = tx; prevY = ty;
            }
            // Second (shorter) hesitation, if sampled. Less pronounced
            // tremor — it's the smaller of two stalls.
            if (i == secondIdx)
            {
                int tx = sx + (rng.nextBoolean() ? 1 : -1) * (1 + rng.nextInt(2));
                int ty = sy + (rng.nextBoolean() ? 1 : -1);
                out.add(new Sample(tx, ty, secondPauseMs));
                prevX = tx; prevY = ty;
            }
        }
        return out;
    }

    /** Generalized smoothstep: {@code t^a / (t^a + (1-t)^b)}. With
     *  {@code a==b==2} this approximates the classic 3t^2-2t^3 smoothstep
     *  shape; biasing {@code a < b} pulls peak velocity earlier in the
     *  path, {@code a > b} pulls it later. */
    private static double generalizedSmoothstep(double t, double a, double b)
    {
        if (t <= 0) return 0;
        if (t >= 1) return 1;
        double ta = Math.pow(t, a);
        double mb = Math.pow(1 - t, b);
        return ta / (ta + mb);
    }

    private static double clamp(double v, double lo, double hi)
    {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
