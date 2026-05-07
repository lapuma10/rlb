package net.runelite.client.plugins.recorder.nav;

/** Outcome of a single {@link Navigator#tick(NavRequest)} call.
 *
 *  <p>{@code IDLE} — no navigation has been started or one was just cancelled.
 *  {@code RUNNING} — navigation in progress, call {@code tick} again next loop iteration.
 *  {@code ARRIVED} — destination reached; the next {@code tick} on the same request is a no-op.
 *  {@code FAILED} — navigator gave up (stuck, error, missing data); caller decides recovery. */
public enum NavStatus
{
    IDLE,
    RUNNING,
    ARRIVED,
    FAILED
}
