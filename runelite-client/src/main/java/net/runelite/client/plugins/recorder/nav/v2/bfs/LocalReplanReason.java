package net.runelite.client.plugins.recorder.nav.v2.bfs;

/** Lane-3-local subset of spec §3 {@code ReplanReason}. The kernel only
 *  emits two reasons today: {@link #TARGET_UNREACHABLE} (BFS queue exhausted
 *  without finding the goal) and {@link #EXECUTOR_TIMEOUT} (expansion-tile
 *  budget exhausted before goal reached).
 *
 *  <p>The names match spec §3 ReplanReason so callers can map 1-to-1 without
 *  inventing new identifiers. Once Lane 1 ships the canonical
 *  {@code ReplanReason} enum, this type either becomes a thin re-export or
 *  is replaced.
 */
public enum LocalReplanReason
{
	/** BFS queue exhausted without finding goal — true unreachability. */
	TARGET_UNREACHABLE,
	/** Expansion-tile budget exceeded before goal was found. */
	EXECUTOR_TIMEOUT
}
