package net.runelite.client.plugins.recorder.nav;

/** Hint to the navigator about how to balance speed, variety, and caution.
 *
 *  <p>Round-1 scope: only {@link #VARIED} is implemented. The other modes
 *  are forward-compatible names so future work can add them without
 *  changing the {@link NavRequest} signature. Calling V2 with any other
 *  mode in round 1 falls through to {@code VARIED} behavior with a
 *  warning log. */
public enum BehaviorMode
{
    /** Reasonable defaults; no specific bias. Round-1: falls through to {@link #VARIED}. */
    NORMAL,

    /** Prefer the cheapest path; minimize variety. Round-1: falls through to {@link #VARIED}. */
    EFFICIENT,

    /** Prefer route alternation when alternates exist; add edge-cost noise inside a single corridor. */
    VARIED,

    /** Bias toward known-safe edges; reject high-failure-count tiles aggressively. Round-1: falls through to {@link #VARIED}. */
    CAUTIOUS
}
